package org.gms.server.bot.freemarket.shopoffer;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.bot.BotTiming;
import org.gms.server.bot.dialogue.BotDialogueHandler;
import org.gms.server.maps.HiredMerchant;
import org.gms.server.maps.PlayerShop;
import org.gms.server.maps.PlayerShopItem;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.gms.util.Randomizer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 报价应答（移植自 SoloMapling FreeMarket.ShopOfferSystem.ShopOfferResponse）。
 * <p>
 * 台词来自 BotDialoguePack/ShopOfferDialogue.yaml（classpath 加载，节点：
 * AcceptResponse/CounterResponse/DeclineResponse/KickResponse/WelcomeResponse），
 * 支持 {item}/{price}/{player}/{listing_price}/{counter_price} 占位替换。
 * 店主发言走 {@link PlayerShop#chat(Character, String)}；成交与 AFK 改价都通过
 * setPrice + 商店物品更新封包广播，AFK 成交另发私聊通知报价玩家。
 */
@Slf4j
public class ShopOfferResponse {

    private static final String DIALOGUE_PATH = "ShopOfferDialogue.yaml";
    private static final String BOT_TYPE = "ShopOffer";

    /** AFK 改价延迟下限（分钟）。 */
    static final int AFK_DELAY_MIN_MINUTES = 5;
    /** AFK 改价延迟上限（分钟）。 */
    static final int AFK_DELAY_MAX_MINUTES = 30;

    public static void handlePresentOwner(Character player, PlayerShop shop, OfferParser.ParsedOffer offer, HaggleSession session) {
        if (player == null || shop == null || offer == null || session == null) {
            return;
        }
        // M5 修复：延迟回调店内复核。应答排队（2-6 秒）期间玩家可能已离店，
        // 离店后不得再成交/踢人/记 banned（离店时 PlayerShop 引用已被置空）。
        if (player.getPlayerShop() != shop) {
            return;
        }

        Character owner = shop.getOwner();
        // M1 修复：登记与上限判定原子化（返回登记后的累计次数），
        // 并发连发报价不会丢计数导致踢人上限失效。
        if (session.incrementAttempt() >= HaggleSession.MAX_ATTEMPTS) {
            String msg = getDialogueLine("KickResponse", offer, null);
            shopOwnerChat(shop, owner, msg);
            BotTiming.after(2000, () -> {
                // 踢人回调同样复核店内状态：已离店的玩家不再 ban（避免误记）
                if (player.getPlayerShop() == shop) {
                    shop.banPlayer(player.getName());
                }
            });
            ShopOfferSystem.getInstance().removeSession(player.getId(), owner.getId());
            return;
        }

        if (session.hasCounterPending()) {
            if (offer.getOfferPrice() >= session.getCounterPrice()) {
                acceptOffer(shop, owner, player, offer, session.getCounterPrice());
                return;
            }
        }

        OfferEvaluator.Decision decision = OfferEvaluator.evaluate(offer.getOfferPrice(), offer.getShopItem().getPrice());

        switch (decision) {
            case ACCEPT:
                acceptOffer(shop, owner, player, offer, offer.getOfferPrice());
                break;
            case COUNTER:
                long counterPrice = OfferEvaluator.calculateCounterPrice(offer.getOfferPrice(), offer.getShopItem().getPrice());
                session.setCounterPrice(counterPrice);
                Map<String, String> counterReplacements = buildReplacements(offer, player);
                counterReplacements.put("{counter_price}", formatPrice(counterPrice));
                String counterMsg = getDialogueLineWithReplacements("CounterResponse", counterReplacements);
                shopOwnerChat(shop, owner, counterMsg);
                log.info(I18nUtil.getLogMessage("ShopOfferSystem.log.counter", offer.getItemName(),
                        counterPrice, player.getName(), offer.getShopItem().getPrice()));
                break;
            case DECLINE:
                String declineMsg = getDialogueLine("DeclineResponse", offer, player);
                shopOwnerChat(shop, owner, declineMsg);
                break;
            default:
                break;
        }
    }

    public static void handleAFKOwner(Character player, PlayerShop shop, OfferParser.ParsedOffer offer, ShopOfferSystem system) {
        OfferEvaluator.Decision decision = OfferEvaluator.evaluate(offer.getOfferPrice(), offer.getShopItem().getPrice());

        if (decision == OfferEvaluator.Decision.DECLINE) {
            return;
        }

        long acceptedPrice = offer.getOfferPrice();
        String itemName = offer.getItemName();
        String ownerName = shop.getOwner().getName();
        int ownerId = shop.getOwner().getId();
        PlayerShopItem shopItem = offer.getShopItem();
        String roomLabel = getFMRoomLabel(shop.getMapId());
        int delay = afkDelayMillis();

        // M2 修复：原子尝试锁定（物品身份 key）。同一物品已有待处理改价时放弃，
        // 防止两个玩家几乎同时报价同一物品导致重复排程。
        if (!system.tryLockItem(ownerId, shopItem)) {
            log.info(I18nUtil.getLogMessage("ShopOfferSystem.log.itemLocked", player.getName()));
            return;
        }

        log.info(I18nUtil.getLogMessage("ShopOfferSystem.log.afkScheduled", itemName, acceptedPrice,
                player.getName(), delay / 60000));

        BotTiming.after(delay, () -> {
            shopItem.setPrice((int) acceptedPrice);
            shop.broadcast(PacketCreator.getPlayerShopItemUpdate(shop));
            // M2 修复：成交后释放锁，物品可再次参与议价（旧实现为永久锁）
            system.unlockItem(ownerId, shopItem);

            if (player.getClient() != null) {
                player.sendPacket(PacketCreator.getWhisperReceive(
                        ownerName, player.getClient().getChannel() - 1, false,
                        I18nUtil.getMessage("ShopOfferSystem.whisper.updated", itemName, formatPrice(acceptedPrice), roomLabel)
                ));
            }
        });
    }

    public static void handleHiredMerchantAFK(Character player, String ownerName, int ownerId, int mapId,
                                              PlayerShopItem shopItem, OfferParser.ParsedOffer offer,
                                              ShopOfferSystem system, Runnable broadcastUpdate) {
        OfferEvaluator.Decision decision = OfferEvaluator.evaluate(offer.getOfferPrice(), shopItem.getPrice());

        if (decision == OfferEvaluator.Decision.DECLINE) {
            return;
        }

        long acceptedPrice = offer.getOfferPrice();
        String itemName = offer.getItemName();
        String roomLabel = getFMRoomLabel(mapId);
        int delay = afkDelayMillis();

        // M2 修复：原子尝试锁定（物品身份 key），同一物品已有待处理改价时放弃
        if (!system.tryLockItem(ownerId, shopItem)) {
            log.info(I18nUtil.getLogMessage("ShopOfferSystem.log.hiredItemLocked", player.getName()));
            return;
        }

        log.info(I18nUtil.getLogMessage("ShopOfferSystem.log.afkScheduled", itemName, acceptedPrice,
                player.getName(), delay / 60000));

        BotTiming.after(delay, () -> {
            shopItem.setPrice((int) acceptedPrice);
            if (broadcastUpdate != null) {
                broadcastUpdate.run();
            }
            // M2 修复：成交后释放锁，物品可再次参与议价（旧实现为永久锁）
            system.unlockItem(ownerId, shopItem);

            if (player.getClient() != null) {
                player.sendPacket(PacketCreator.getWhisperReceive(
                        ownerName, player.getClient().getChannel() - 1, false,
                        I18nUtil.getMessage("ShopOfferSystem.whisper.updated", itemName, formatPrice(acceptedPrice), roomLabel)
                ));
            }
        });
    }

    /** 改价延迟：5-30 分钟闭区间均匀随机。 */
    static int afkDelayMillis() {
        return (AFK_DELAY_MIN_MINUTES + Randomizer.nextInt(AFK_DELAY_MAX_MINUTES - AFK_DELAY_MIN_MINUTES + 1)) * 60 * 1000;
    }

    private static void acceptOffer(PlayerShop shop, Character owner, Character player,
                                    OfferParser.ParsedOffer offer, long finalPrice) {
        String msg = getDialogueLine("AcceptResponse", offer, player);
        shopOwnerChat(shop, owner, msg);

        offer.getShopItem().setPrice((int) finalPrice);
        shop.broadcast(PacketCreator.getPlayerShopItemUpdate(shop));

        // PRESENT 成交即时生效、无待处理改价窗口，不需要物品锁（M2：旧实现此处
        // 加的 ownerId_itemIndex 永久锁既锁错对象也从不释放）；只结束议价会话。
        ShopOfferSystem.getInstance().removeSession(player.getId(), owner.getId());

        log.info(I18nUtil.getLogMessage("ShopOfferSystem.log.accepted", offer.getItemName(), finalPrice, player.getName()));
    }

    private static void shopOwnerChat(PlayerShop shop, Character owner, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        shop.chat(owner, message);
    }

    private static String getDialogueLine(String node, OfferParser.ParsedOffer offer, Character player) {
        Map<String, String> replacements = buildReplacements(offer, player);
        return getDialogueLineWithReplacements(node, replacements);
    }

    private static String getDialogueLineWithReplacements(String node, Map<String, String> replacements) {
        BotDialogueHandler.DialogueConstructor dialog =
                BotDialogueHandler.getDialogueCon(DIALOGUE_PATH, BOT_TYPE, node);
        if (dialog == null || dialog.getDialogue().isEmpty()) {
            return "";
        }

        List<String> lines = dialog.getDialogue();
        String line = lines.get(Randomizer.nextInt(lines.size()));

        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            line = line.replace(entry.getKey(), entry.getValue());
        }
        return line;
    }

    private static Map<String, String> buildReplacements(OfferParser.ParsedOffer offer, Character player) {
        Map<String, String> map = new HashMap<>();
        map.put("{item}", offer != null ? offer.getItemName() : "");
        map.put("{price}", offer != null ? formatPrice(offer.getOfferPrice()) : "");
        map.put("{player}", player != null ? player.getName() : "");
        map.put("{listing_price}", offer != null ? formatPrice(offer.getShopItem().getPrice()) : "");
        return map;
    }

    /** {player} 占位替换（M4：欢迎台词与报价台词共用同一替换逻辑，替换为玩家名）。 */
    public static String replacePlayerPlaceholder(String line, Character player) {
        if (line == null) {
            return null;
        }
        return line.replace("{player}", player != null ? player.getName() : "");
    }

    private static String getFMRoomLabel(int mapId) {
        int room = mapId - 910000000;
        if (room >= 1 && room <= 22) {
            return "FM " + room;
        }
        return "";
    }

    /** 价格展示格式：整十亿/百万/千取缩写（1.5b/50m/500k），其余原样输出。 */
    public static String formatPrice(long price) {
        if (price >= 1_000_000_000 && price % 1_000_000_000 == 0) {
            return (price / 1_000_000_000) + "b";
        } else if (price >= 1_000_000 && price % 1_000_000 == 0) {
            return (price / 1_000_000) + "m";
        } else if (price >= 1_000 && price % 1_000 == 0) {
            return (price / 1_000) + "k";
        }
        return String.valueOf(price);
    }
}
