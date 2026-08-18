package org.gms.server.bot.freemarket.shopoffer;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.net.packet.Packet;
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
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ShopOfferSystem 单测：PRESENT/AFK 分支路由、会话复用、物品锁定、
 * HiredMerchant 转发、过期会话清理。静态依赖（BotHelpers/OfferParser/
 * ShopOfferResponse/BotTiming）全部 mock，单例内部状态经反射预设与复位。
 */
class ShopOfferSystemTest {

    private static final int BOT_OWNER_ID = BotHelpers.BOT_BASE_ID + 100_001;
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

    // ─────────────────────────── PRESENT 分支 ───────────────────────────

    @Test
    void presentModeSchedulesDelayedResponseAndCreatesSession() {
        try (MockedStatic<BotHelpers> helpers = mockStatic(BotHelpers.class);
             MockedStatic<OfferParser> parser = mockStatic(OfferParser.class);
             MockedStatic<ShopOfferResponse> response = mockStatic(ShopOfferResponse.class);
             MockedStatic<BotTiming> timing = mockStatic(BotTiming.class)) {
            helpers.when(() -> BotHelpers.isBot(any(Character.class))).thenReturn(true);

            Character owner = botOwner();
            PlayerShop shop = shopOf(owner);
            List<PlayerShopItem> items = itemsOf(shop);
            OfferParser.ParsedOffer offer = new OfferParser.ParsedOffer("Red Potion", 0, 50_000_000L, items.get(0));
            parser.when(() -> OfferParser.parse(anyString(), anyList())).thenReturn(offer);
            Character player = player();

            ShopOfferSystem system = ShopOfferSystem.getInstance();
            shopModes(system).put(BOT_OWNER_ID, ShopOfferSystem.ShopMode.PRESENT);

            system.onPlayerShopChat(player, shop, "50m Red Potion");

            ArgumentCaptor<Long> delayCap = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Runnable> runnableCap = ArgumentCaptor.forClass(Runnable.class);
            timing.verify(() -> BotTiming.after(delayCap.capture(), runnableCap.capture()));
            assertTrue(delayCap.getValue() >= 2000 && delayCap.getValue() < 6000, "应答延迟应在 [2s, 6s) 内");

            runnableCap.getValue().run();
            response.verify(() -> ShopOfferResponse.handlePresentOwner(eq(player), eq(shop), eq(offer), any(HaggleSession.class)));
            assertEquals(1, sessions(system).size(), "首次报价应建立议价会话");
        }
    }

    @Test
    void presentModeReusesExistingSession() {
        try (MockedStatic<BotHelpers> helpers = mockStatic(BotHelpers.class);
             MockedStatic<OfferParser> parser = mockStatic(OfferParser.class);
             MockedStatic<ShopOfferResponse> response = mockStatic(ShopOfferResponse.class);
             MockedStatic<BotTiming> timing = mockStatic(BotTiming.class)) {
            helpers.when(() -> BotHelpers.isBot(any(Character.class))).thenReturn(true);

            Character owner = botOwner();
            PlayerShop shop = shopOf(owner);
            List<PlayerShopItem> items = itemsOf(shop);
            OfferParser.ParsedOffer offer = new OfferParser.ParsedOffer("Red Potion", 0, 50_000_000L, items.get(0));
            parser.when(() -> OfferParser.parse(anyString(), anyList())).thenReturn(offer);
            Character player = player();

            ShopOfferSystem system = ShopOfferSystem.getInstance();
            shopModes(system).put(BOT_OWNER_ID, ShopOfferSystem.ShopMode.PRESENT);
            HaggleSession existing = new HaggleSession(PLAYER_ID, BOT_OWNER_ID);
            sessions(system).put(ShopOfferSystem.buildSessionKey(PLAYER_ID, BOT_OWNER_ID), existing);

            system.onPlayerShopChat(player, shop, "50m Red Potion");

            ArgumentCaptor<Runnable> runnableCap = ArgumentCaptor.forClass(Runnable.class);
            timing.verify(() -> BotTiming.after(any(Long.class), runnableCap.capture()));
            runnableCap.getValue().run();
            response.verify(() -> ShopOfferResponse.handlePresentOwner(eq(player), eq(shop), eq(offer), eq(existing)));
            assertEquals(1, sessions(system).size(), "复用会话，不新建");
        }
    }

    // ─────────────────────────── AFK 分支 ───────────────────────────

    @Test
    void afkModeHandlesOfferImmediately() {
        try (MockedStatic<BotHelpers> helpers = mockStatic(BotHelpers.class);
             MockedStatic<OfferParser> parser = mockStatic(OfferParser.class);
             MockedStatic<ShopOfferResponse> response = mockStatic(ShopOfferResponse.class);
             MockedStatic<BotTiming> timing = mockStatic(BotTiming.class)) {
            helpers.when(() -> BotHelpers.isBot(any(Character.class))).thenReturn(true);

            Character owner = botOwner();
            PlayerShop shop = shopOf(owner);
            List<PlayerShopItem> items = itemsOf(shop);
            OfferParser.ParsedOffer offer = new OfferParser.ParsedOffer("Red Potion", 0, 50_000_000L, items.get(0));
            parser.when(() -> OfferParser.parse(anyString(), anyList())).thenReturn(offer);
            Character player = player();

            ShopOfferSystem system = ShopOfferSystem.getInstance();
            shopModes(system).put(BOT_OWNER_ID, ShopOfferSystem.ShopMode.AFK);

            system.onPlayerShopChat(player, shop, "50m Red Potion");

            response.verify(() -> ShopOfferResponse.handleAFKOwner(eq(player), eq(shop), eq(offer), eq(system)));
            timing.verify(() -> BotTiming.after(any(Long.class), any(Runnable.class)), never());
            assertEquals(0, sessions(system).size(), "AFK 模式不建议价会话");
        }
    }

    // ─────────────────────────── 锁定与非 bot 门控 ───────────────────────────

    @Test
    void lockedItemIgnoresOffer() {
        try (MockedStatic<BotHelpers> helpers = mockStatic(BotHelpers.class);
             MockedStatic<OfferParser> parser = mockStatic(OfferParser.class);
             MockedStatic<ShopOfferResponse> response = mockStatic(ShopOfferResponse.class)) {
            helpers.when(() -> BotHelpers.isBot(any(Character.class))).thenReturn(true);

            Character owner = botOwner();
            PlayerShop shop = shopOf(owner);
            List<PlayerShopItem> items = itemsOf(shop);
            OfferParser.ParsedOffer offer = new OfferParser.ParsedOffer("Red Potion", 0, 50_000_000L, items.get(0));
            parser.when(() -> OfferParser.parse(anyString(), anyList())).thenReturn(offer);

            ShopOfferSystem system = ShopOfferSystem.getInstance();
            system.tryLockItem(BOT_OWNER_ID, items.get(0));

            system.onPlayerShopChat(player(), shop, "50m Red Potion");

            response.verifyNoInteractions();
            assertEquals(0, sessions(system).size());
        }
    }

    @Test
    void nonBotOwnerIsIgnored() {
        try (MockedStatic<BotHelpers> helpers = mockStatic(BotHelpers.class);
             MockedStatic<OfferParser> parser = mockStatic(OfferParser.class);
             MockedStatic<ShopOfferResponse> response = mockStatic(ShopOfferResponse.class)) {
            helpers.when(() -> BotHelpers.isBot(any(Character.class))).thenReturn(false);

            Character owner = botOwner();
            PlayerShop shop = shopOf(owner);

            ShopOfferSystem.getInstance().onPlayerShopChat(player(), shop, "50m Red Potion");

            parser.verifyNoInteractions();
            response.verifyNoInteractions();
        }
    }

    @Test
    void unparsableMessageIsIgnored() {
        try (MockedStatic<BotHelpers> helpers = mockStatic(BotHelpers.class);
             MockedStatic<OfferParser> parser = mockStatic(OfferParser.class);
             MockedStatic<ShopOfferResponse> response = mockStatic(ShopOfferResponse.class)) {
            helpers.when(() -> BotHelpers.isBot(any(Character.class))).thenReturn(true);
            parser.when(() -> OfferParser.parse(anyString(), anyList())).thenReturn(null);

            Character owner = botOwner();
            PlayerShop shop = shopOf(owner);
            ShopOfferSystem.getInstance().onPlayerShopChat(player(), shop, "随便聊聊");

            response.verifyNoInteractions();
        }
    }

    // ─────────────────────────── HiredMerchant 转发 ───────────────────────────

    @Test
    void hiredMerchantChatRoutesToAfkHandlerWithBroadcastRunnable() {
        try (MockedStatic<OfferParser> parser = mockStatic(OfferParser.class);
             MockedStatic<ShopOfferResponse> response = mockStatic(ShopOfferResponse.class);
             MockedStatic<PacketCreator> pc = mockStatic(PacketCreator.class)) {
            HiredMerchant merchant = mock(HiredMerchant.class);
            when(merchant.getOwner()).thenReturn("OwnerBot");
            when(merchant.getOwnerId()).thenReturn(BOT_OWNER_ID);
            when(merchant.getMapId()).thenReturn(910000001);
            PlayerShopItem item = new PlayerShopItem(new Item(2000000, (short) 0, (short) 1), (short) 1, 100_000_000);
            when(merchant.getItems()).thenReturn(List.of(item));
            pc.when(() -> PacketCreator.updateHiredMerchant(any(HiredMerchant.class), any(Character.class)))
                    .thenReturn(mock(Packet.class));

            OfferParser.ParsedOffer offer = new OfferParser.ParsedOffer("Red Potion", 0, 50_000_000L, item);
            parser.when(() -> OfferParser.parse(anyString(), anyList())).thenReturn(offer);

            Character player = player();
            ShopOfferSystem system = ShopOfferSystem.getInstance();
            system.onHiredMerchantChat(player, merchant, "50m Red Potion");

            ArgumentCaptor<Runnable> broadcastCap = ArgumentCaptor.forClass(Runnable.class);
            response.verify(() -> ShopOfferResponse.handleHiredMerchantAFK(
                    eq(player), eq("OwnerBot"), eq(BOT_OWNER_ID), eq(910000001),
                    eq(item), eq(offer), eq(system), broadcastCap.capture()));

            // 广播 runnable 应执行 updateHiredMerchant 并广播给店内访客
            broadcastCap.getValue().run();
            verify(merchant).broadcastToVisitorsThreadsafe(any());
        }
    }

    @Test
    void hiredMerchantLockedItemIsIgnored() {
        try (MockedStatic<OfferParser> parser = mockStatic(OfferParser.class);
             MockedStatic<ShopOfferResponse> response = mockStatic(ShopOfferResponse.class)) {
            HiredMerchant merchant = mock(HiredMerchant.class);
            when(merchant.getOwner()).thenReturn("OwnerBot");
            when(merchant.getOwnerId()).thenReturn(BOT_OWNER_ID);
            PlayerShopItem item = new PlayerShopItem(new Item(2000000, (short) 0, (short) 1), (short) 1, 100_000_000);
            when(merchant.getItems()).thenReturn(List.of(item));

            OfferParser.ParsedOffer offer = new OfferParser.ParsedOffer("Red Potion", 0, 50_000_000L, item);
            parser.when(() -> OfferParser.parse(anyString(), anyList())).thenReturn(offer);

            ShopOfferSystem system = ShopOfferSystem.getInstance();
            system.tryLockItem(BOT_OWNER_ID, item);
            system.onHiredMerchantChat(player(), merchant, "50m Red Potion");

            response.verifyNoInteractions();
        }
    }

    // ─────────────────────────── 过期会话清理 ───────────────────────────

    @Test
    void expiredSessionIsCleanedOnNextOffer() {
        try (MockedStatic<BotHelpers> helpers = mockStatic(BotHelpers.class);
             MockedStatic<OfferParser> parser = mockStatic(OfferParser.class);
             MockedStatic<ShopOfferResponse> response = mockStatic(ShopOfferResponse.class);
             MockedStatic<BotTiming> timing = mockStatic(BotTiming.class)) {
            helpers.when(() -> BotHelpers.isBot(any(Character.class))).thenReturn(true);

            Character owner = botOwner();
            PlayerShop shop = shopOf(owner);
            List<PlayerShopItem> items = itemsOf(shop);
            OfferParser.ParsedOffer offer = new OfferParser.ParsedOffer("Red Potion", 0, 50_000_000L, items.get(0));
            parser.when(() -> OfferParser.parse(anyString(), anyList())).thenReturn(offer);
            Character player = player();

            ShopOfferSystem system = ShopOfferSystem.getInstance();
            shopModes(system).put(BOT_OWNER_ID, ShopOfferSystem.ShopMode.PRESENT);
            // 注入过期会话（时钟推进超过 60 秒）
            AtomicLong clock = new AtomicLong(0);
            HaggleSession expired = new HaggleSession(PLAYER_ID, BOT_OWNER_ID, clock::get);
            clock.set(HaggleSession.EXPIRY_MS + 1);
            sessions(system).put(ShopOfferSystem.buildSessionKey(PLAYER_ID, BOT_OWNER_ID), expired);

            system.onPlayerShopChat(player, shop, "50m Red Potion");

            ArgumentCaptor<Runnable> runnableCap = ArgumentCaptor.forClass(Runnable.class);
            timing.verify(() -> BotTiming.after(any(Long.class), runnableCap.capture()));
            runnableCap.getValue().run();
            ArgumentCaptor<HaggleSession> sessionCap = ArgumentCaptor.forClass(HaggleSession.class);
            response.verify(() -> ShopOfferResponse.handlePresentOwner(eq(player), eq(shop), eq(offer), sessionCap.capture()));
            assertNotSame(expired, sessionCap.getValue(), "过期会话应被清理并新建");
        }
    }

    // ─────────────────────────── helpers ───────────────────────────

    private static Character botOwner() {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(BOT_OWNER_ID);
        when(owner.getName()).thenReturn("OwnerBot");
        return owner;
    }

    private static PlayerShop shopOf(Character owner) {
        PlayerShop shop = mock(PlayerShop.class);
        when(shop.getOwner()).thenReturn(owner);
        return shop;
    }

    private static List<PlayerShopItem> itemsOf(PlayerShop shop) {
        PlayerShopItem item = new PlayerShopItem(new Item(2000000, (short) 0, (short) 1), (short) 1, 100_000_000);
        when(shop.getItems()).thenReturn(List.of(item));
        return shop.getItems();
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
