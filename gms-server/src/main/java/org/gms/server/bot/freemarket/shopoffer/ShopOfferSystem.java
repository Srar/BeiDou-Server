package org.gms.server.bot.freemarket.shopoffer;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotTiming;
import org.gms.server.maps.HiredMerchant;
import org.gms.server.maps.PlayerShop;
import org.gms.server.maps.PlayerShopItem;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.gms.util.Randomizer;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商店报价系统（移植自 SoloMapling FreeMarket.ShopOfferSystem.ShopOfferSystem）。
 * <p>
 * 玩家在 bot 店铺聊天里报价（价格 + 物品名，见 {@link OfferParser}）时触发：
 * <ul>
 *   <li>PRESENT 模式（默认 60% 概率分配）：店主在场，走 {@link HaggleSession}
 *       延迟应答议价（3 次上限、60 秒过期）；</li>
 *   <li>AFK 模式：店主不在场，成交价延迟 5-30 分钟改价并通过私聊通知玩家。</li>
 * </ul>
 * 与 BotEconomy 的静态定价共存：本系统只响应真人玩家的报价聊天，
 * 不改动 bot 进货/挂价逻辑，属于叠加的议价能力。
 */
@Slf4j
public class ShopOfferSystem {

    private static ShopOfferSystem instance;
    private final Map<Integer, HaggleSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<Integer, ShopMode> shopModes = new ConcurrentHashMap<>();
    private final Set<String> lockedItems = ConcurrentHashMap.newKeySet();

    private static final double PRESENT_CHANCE = 0.60;

    public enum ShopMode {
        PRESENT,
        AFK
    }

    private ShopOfferSystem() {
    }

    public static synchronized ShopOfferSystem getInstance() {
        if (instance == null) {
            instance = new ShopOfferSystem();
        }
        return instance;
    }

    public ShopMode getOrAssignMode(int ownerId) {
        return shopModes.computeIfAbsent(
                ownerId,
                id -> Randomizer.nextDouble() < PRESENT_CHANCE ? ShopMode.PRESENT : ShopMode.AFK
        );
    }

    public void onPlayerShopChat(Character player, PlayerShop shop, String message) {
        if (player == null || shop == null || message == null) {
            return;
        }
        if (!BotHelpers.isBot(shop.getOwner())) {
            return;
        }

        List<PlayerShopItem> items = shop.getItems();
        OfferParser.ParsedOffer offer = OfferParser.parse(message, items);
        if (offer == null) {
            return;
        }

        String lockKey = buildLockKey(shop.getOwner().getId(), offer.getItemIndex());
        if (lockedItems.contains(lockKey)) {
            log.info(I18nUtil.getLogMessage("ShopOfferSystem.log.itemLocked", player.getName()));
            return;
        }

        log.info(I18nUtil.getLogMessage("ShopOfferSystem.log.offerDetected", player.getName(),
                offer.getItemName(), offer.getOfferPrice(), offer.getShopItem().getPrice()));

        cleanExpiredSessions();

        ShopMode mode = getOrAssignMode(shop.getOwner().getId());

        int responseDelay = 2000 + Randomizer.nextInt(4000);

        if (mode == ShopMode.PRESENT) {
            HaggleSession existingSession = activeSessions.get(player.getId());
            if (existingSession != null) {
                existingSession.touch();
                BotTiming.after(
                        responseDelay,
                        () -> ShopOfferResponse.handlePresentOwner(player, shop, offer, existingSession)
                );
                return;
            }

            HaggleSession session = new HaggleSession(player.getId(), shop.getOwner().getId());
            activeSessions.put(player.getId(), session);
            BotTiming.after(
                    responseDelay,
                    () -> ShopOfferResponse.handlePresentOwner(player, shop, offer, session)
            );
        } else {
            ShopOfferResponse.handleAFKOwner(player, shop, offer, this);
        }
    }

    public void onHiredMerchantChat(Character player, HiredMerchant merchant, String message) {
        if (player == null || merchant == null || message == null) {
            return;
        }

        List<PlayerShopItem> items = merchant.getItems();
        OfferParser.ParsedOffer offer = OfferParser.parse(message, items);
        if (offer == null) {
            return;
        }

        String ownerName = merchant.getOwner();
        String lockKey = buildLockKey(merchant.getOwnerId(), offer.getItemIndex());
        if (lockedItems.contains(lockKey)) {
            log.info(I18nUtil.getLogMessage("ShopOfferSystem.log.hiredItemLocked", player.getName()));
            return;
        }

        log.info(I18nUtil.getLogMessage("ShopOfferSystem.log.hiredOffer", player.getName(),
                offer.getItemName(), offer.getOfferPrice(), offer.getShopItem().getPrice(), ownerName));

        ShopOfferResponse.handleHiredMerchantAFK(
                player, ownerName, merchant.getOwnerId(), merchant.getMapId(),
                offer.getShopItem(), offer, this,
                () -> merchant.broadcastToVisitorsThreadsafe(PacketCreator.updateHiredMerchant(merchant, player))
        );
    }

    public void lockItem(int ownerId, int itemIndex) {
        lockedItems.add(buildLockKey(ownerId, itemIndex));
    }

    public void removeSession(int playerId) {
        activeSessions.remove(playerId);
    }

    private String buildLockKey(int ownerId, int itemIndex) {
        return ownerId + "_" + itemIndex;
    }

    private void cleanExpiredSessions() {
        Iterator<Map.Entry<Integer, HaggleSession>> it = activeSessions.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired()) {
                it.remove();
            }
        }
    }
}
