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
    /**
     * 议价会话表：key 为「玩家 × 店主」复合键（见 {@link #buildSessionKey}）。
     * 修复 C2：旧实现仅以 playerId 为 key，玩家在 A 店拿到还价后可去 B 店复用
     * A 店的 counter/attempts 低价成交；复合键使跨店会话天然隔离。
     */
    private final Map<String, HaggleSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<Integer, ShopMode> shopModes = new ConcurrentHashMap<>();
    /**
     * 待处理改价锁：key 为「店主 × 物品身份」（itemId + 商店物品对象身份，见
     * {@link #buildLockKey}），不再使用会随商店物品增删漂移的下标；且成交回调
     * 完成后会 remove（见 ShopOfferResponse 的 unlockItem），不再是永久锁（修复 M2）。
     */
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

        String lockKey = buildLockKey(shop.getOwner().getId(), offer.getShopItem());
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
            String sessionKey = buildSessionKey(player.getId(), shop.getOwner().getId());
            HaggleSession existingSession = activeSessions.get(sessionKey);
            if (existingSession != null) {
                existingSession.touch();
                BotTiming.after(
                        responseDelay,
                        () -> ShopOfferResponse.handlePresentOwner(player, shop, offer, existingSession)
                );
                return;
            }

            HaggleSession session = new HaggleSession(player.getId(), shop.getOwner().getId());
            activeSessions.put(sessionKey, session);
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
        // C1 修复：真人雇佣商店无门控，访客聊天可静默改价。HiredMerchant 只持有 ownerId
        // （店主可能离线，无 Character 对象），因此用区段判据 isBotId 而非 isBot(Character)。
        // 判据选择理由：bot 摊主 ownerId 由 artificialMerchantIdCounter 分配
        // （BotHelpers.BOT_BASE_ID + 10_000_000 起），恒落在 bot 区段；真人店主 id 来自
        // 数据库自增，不可能进入该区段。isBot(int) 的「注册表命中」判据（BotStorage.botLoggedIn
        // 仅覆盖在线 bot）会误伤店主离线的 bot 店，故此处只做区段判定。
        if (!BotHelpers.isBotId(merchant.getOwnerId())) {
            return;
        }

        List<PlayerShopItem> items = merchant.getItems();
        OfferParser.ParsedOffer offer = OfferParser.parse(message, items);
        if (offer == null) {
            return;
        }

        String ownerName = merchant.getOwner();
        String lockKey = buildLockKey(merchant.getOwnerId(), offer.getShopItem());
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

    /**
     * 尝试锁定物品（原子操作）：已存在相同物品身份的待处理改价时返回 false，
     * 供 AFK 成交排程前竞态兜底——两个玩家几乎同时报价同一物品时，
     * 只有先到者能排程，避免对同一物品排两次改价。
     */
    public boolean tryLockItem(int ownerId, PlayerShopItem item) {
        return lockedItems.add(buildLockKey(ownerId, item));
    }

    /** 成交回调完成后释放锁（M2：锁随成交结束移除，不再是永久锁）。 */
    public void unlockItem(int ownerId, PlayerShopItem item) {
        lockedItems.remove(buildLockKey(ownerId, item));
    }

    public void removeSession(int playerId, int ownerId) {
        activeSessions.remove(buildSessionKey(playerId, ownerId));
    }

    /**
     * 物品锁定 key：以物品身份（itemId + 商店物品对象身份）标识，而非商品下标。
     * 下标在物品被购买/下架后会漂移，旧 key（ownerId_itemIndex）会锁错商品或
     * 因下标不复存在而失去锁的语义。
     */
    private String buildLockKey(int ownerId, PlayerShopItem item) {
        return ownerId + "_" + item.getItem().getItemId() + "_" + System.identityHashCode(item);
    }

    /** 会话 key：玩家 × 店主 复合键（C2：跨店还价会话隔离）。 */
    static String buildSessionKey(int playerId, int shopOwnerId) {
        return playerId + "_" + shopOwnerId;
    }

    private void cleanExpiredSessions() {
        Iterator<Map.Entry<String, HaggleSession>> it = activeSessions.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired()) {
                it.remove();
            }
        }
    }
}
