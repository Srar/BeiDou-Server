package org.gms.server.bot.freemarket.shopoffer;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotTiming;
import org.gms.server.maps.HiredMerchant;
import org.gms.server.maps.PlayerShop;
import org.gms.server.maps.PlayerShopItem;
import org.gms.test.BotTestSupport;
import org.gms.util.PacketCreator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ShopOffer 安全审计回归测试（C1/C2/M1）：
 * <ul>
 *   <li>C1：真人（非 bot 区段 ownerId）HiredMerchant 聊天不触发任何报价流程；</li>
 *   <li>C2：A 店议价会话（counter/attempts）不得被 B 店报价复用；</li>
 *   <li>M1：多线程连发报价 attempts 计数不丢失，3 次踢人上限判定仍有效。</li>
 * </ul>
 */
class ShopOfferSecurityTest {

    private static final int OWNER_A = BotHelpers.BOT_BASE_ID + 100_001;
    private static final int OWNER_B = BotHelpers.BOT_BASE_ID + 100_002;
    private static final int PLAYER_ID = 5;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void resetSingletonState() {
        ShopOfferSystem system = ShopOfferSystem.getInstance();
        clearField(system, "activeSessions");
        clearField(system, "shopModes");
        clearField(system, "lockedItems");
    }

    // ─────────────────────────── C1：真人雇佣商店负向 ───────────────────────────

    @Test
    void realPlayerHiredMerchantChatTriggersNoOfferFlow() {
        try (MockedStatic<OfferParser> parser = mockStatic(OfferParser.class);
             MockedStatic<ShopOfferResponse> response = mockStatic(ShopOfferResponse.class);
             MockedStatic<PacketCreator> pc = mockStatic(PacketCreator.class)) {
            HiredMerchant merchant = mock(HiredMerchant.class);
            // 真人店主：数据库自增 id，不在 bot 区段（BOT_BASE_ID 之上）
            when(merchant.getOwnerId()).thenReturn(12345);
            when(merchant.getOwner()).thenReturn("RealOwner");

            ShopOfferSystem.getInstance().onHiredMerchantChat(player(), merchant, "50m Red Potion");

            // 门控先行返回：不解析报价、不评估、不排程、不构造广播封包
            parser.verifyNoInteractions();
            response.verifyNoInteractions();
            pc.verifyNoInteractions();
            verify(merchant, never()).getItems();
        }
    }

    // ─────────────────────────── C2：跨店会话隔离 ───────────────────────────

    @Test
    void haggleSessionCannotBeReusedAcrossShops() {
        try (MockedStatic<BotHelpers> helpers = mockStatic(BotHelpers.class);
             MockedStatic<OfferParser> parser = mockStatic(OfferParser.class);
             MockedStatic<ShopOfferResponse> response = mockStatic(ShopOfferResponse.class);
             MockedStatic<BotTiming> timing = mockStatic(BotTiming.class)) {
            helpers.when(() -> BotHelpers.isBot(any(Character.class))).thenReturn(true);

            Character ownerA = botOwner(OWNER_A);
            Character ownerB = botOwner(OWNER_B);
            PlayerShop shopA = shopOf(ownerA);
            PlayerShop shopB = shopOf(ownerB);
            PlayerShopItem item = shopItem();
            itemsOf(shopA, item);
            itemsOf(shopB, item);
            OfferParser.ParsedOffer offer = new OfferParser.ParsedOffer("Red Potion", 0, 50_000_000L, item);
            parser.when(() -> OfferParser.parse(anyString(), anyList())).thenReturn(offer);
            Character player = player();

            ShopOfferSystem system = ShopOfferSystem.getInstance();
            shopModes(system).put(OWNER_A, ShopOfferSystem.ShopMode.PRESENT);
            shopModes(system).put(OWNER_B, ShopOfferSystem.ShopMode.PRESENT);

            // A 店议价：建立会话，模拟还价 + 已消耗 2 次报价
            system.onPlayerShopChat(player, shopA, "50m Red Potion");
            HaggleSession sessionA = sessions(system).get(ShopOfferSystem.buildSessionKey(PLAYER_ID, OWNER_A));
            assertNotNull(sessionA, "A 店报价应建立会话");
            sessionA.setCounterPrice(70_000_000L);
            sessionA.incrementAttempt();
            sessionA.incrementAttempt();

            // B 店报价：必须新建会话，不得复用 A 店的 counter/attempts
            system.onPlayerShopChat(player, shopB, "50m Red Potion");

            HaggleSession sessionB = sessions(system).get(ShopOfferSystem.buildSessionKey(PLAYER_ID, OWNER_B));
            assertNotNull(sessionB, "B 店报价应建立自己的会话");
            assertNotSame(sessionA, sessionB, "B 店不得复用 A 店会话对象");
            assertEquals(OWNER_B, sessionB.getShopOwnerId());
            assertEquals(0, sessionB.getAttempts(), "B 店不得复用 A 店 attempts");
            assertFalse(sessionB.hasCounterPending(), "B 店不得复用 A 店还价（counter）");
            assertEquals(2, sessions(system).size(), "跨店会话各自独立保留");
        }
    }

    // ─────────────────────────── M1：并发 attempts 上限 ───────────────────────────

    @Test
    void concurrentOffersKeepAttemptCapEffective() throws Exception {
        HaggleSession session = new HaggleSession(PLAYER_ID, OWNER_A);
        int offerCount = 12;
        ExecutorService pool = Executors.newFixedThreadPool(offerCount);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger kickDecisions = new AtomicInteger();
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < offerCount; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    // 与 handlePresentOwner 相同的原子判定：登记序号 >= 上限即触发踢人
                    int count = session.incrementAttempt();
                    if (count >= HaggleSession.MAX_ATTEMPTS) {
                        kickDecisions.incrementAndGet();
                    }
                    return count;
                }));
            }
            start.countDown();

            Set<Integer> seen = new HashSet<>();
            for (Future<Integer> result : results) {
                seen.add(result.get());
            }

            assertEquals(offerCount, session.getAttempts(), "并发登记不得丢失计数（旧实现多线程同时 attempts++ 会丢）");
            assertEquals(offerCount, seen.size(), "每次登记都应拿到唯一递增序号");
            assertTrue(session.hasExceededAttempts(), "上限判定应生效");
            // 登记序号严格递增 1..N：从第 3 次起每次登记都命中上限 → 踢人路径必然触发（3 次踢人语义）
            assertEquals(offerCount - HaggleSession.MAX_ATTEMPTS + 1, kickDecisions.get(),
                    "第 3 次及之后的每次登记都应触发踢人判定");
        } finally {
            pool.shutdownNow();
        }
    }

    // ─────────────────────────── helpers ───────────────────────────

    private static Character botOwner(int id) {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(id);
        when(owner.getName()).thenReturn("OwnerBot" + id);
        return owner;
    }

    private static PlayerShop shopOf(Character owner) {
        PlayerShop shop = mock(PlayerShop.class);
        when(shop.getOwner()).thenReturn(owner);
        return shop;
    }

    private static PlayerShopItem shopItem() {
        return new PlayerShopItem(new Item(2000000, (short) 0, (short) 1), (short) 1, 100_000_000);
    }

    private static void itemsOf(PlayerShop shop, PlayerShopItem item) {
        when(shop.getItems()).thenReturn(List.of(item));
    }

    private static Character player() {
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("PlayerX");
        return player;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, HaggleSession> sessions(ShopOfferSystem system) {
        return (Map<String, HaggleSession>) readField(system, "activeSessions");
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, ShopOfferSystem.ShopMode> shopModes(ShopOfferSystem system) {
        return (Map<Integer, ShopOfferSystem.ShopMode>) readField(system, "shopModes");
    }

    private static Object readField(ShopOfferSystem system, String name) {
        try {
            Field f = ShopOfferSystem.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(system);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to read ShopOfferSystem." + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearField(ShopOfferSystem system, String name) {
        try {
            Field f = ShopOfferSystem.class.getDeclaredField(name);
            f.setAccessible(true);
            Object value = f.get(system);
            if (value instanceof Map) {
                ((Map<Object, Object>) value).clear();
            } else if (value instanceof Set) {
                ((Set<Object>) value).clear();
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to clear ShopOfferSystem." + name, e);
        }
    }
}
