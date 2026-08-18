package org.gms.server.bot.freemarket.shopoffer;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Item;
import org.gms.net.packet.Packet;
import org.gms.server.bot.BotTiming;
import org.gms.server.maps.HiredMerchant;
import org.gms.server.maps.PlayerShop;
import org.gms.server.maps.PlayerShopItem;
import org.gms.test.BotTestSupport;
import org.gms.util.PacketCreator;
import org.gms.util.Randomizer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ShopOfferResponse 单测：PRESENT 议价（ACCEPT 成交改价广播 / COUNTER 还价 /
 * DECLINE 拒绝 / 3 次踢人 / 达到还价即成交）与 AFK（5-30 分钟延迟改价 + 私聊，
 * 含 PlayerShop 与 HiredMerchant 两种摊型）。
 * <p>
 * OfferEvaluator/BotTiming/PacketCreator/ShopOfferSystem 静态依赖 mock；
 * 台词走真实 BotDialogueHandler（classpath YAML，无 DB 依赖），随机行固定为第一行。
 */
class ShopOfferResponseTest {

    private static final int BOT_OWNER_ID = 9_700_001;
    private static final int PLAYER_ID = 5;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    // ─────────────────────────── PRESENT：ACCEPT ───────────────────────────

    @Test
    void presentAcceptSetsPriceBroadcastsAndLocks() {
        try (MockedStatic<OfferEvaluator> evaluator = mockStatic(OfferEvaluator.class);
             MockedStatic<PacketCreator> pc = mockStatic(PacketCreator.class);
             MockedStatic<ShopOfferSystem> sys = mockStatic(ShopOfferSystem.class);
             MockedStatic<Randomizer> rnd = mockStatic(Randomizer.class)) {
            rnd.when(Randomizer::nextInt).thenReturn(0); // 固定台词第一行
            ShopOfferSystem systemMock = mock(ShopOfferSystem.class);
            sys.when(ShopOfferSystem::getInstance).thenReturn(systemMock);
            evaluator.when(() -> OfferEvaluator.evaluate(anyLong(), anyInt()))
                    .thenReturn(OfferEvaluator.Decision.ACCEPT);

            Character owner = botOwner();
            PlayerShop shop = mock(PlayerShop.class);
            when(shop.getOwner()).thenReturn(owner);
            PlayerShopItem item = shopItem(100_000_000);
            Packet itemUpdate = mock(Packet.class);
            pc.when(() -> PacketCreator.getPlayerShopItemUpdate(shop)).thenReturn(itemUpdate);

            Character player = player();
            OfferParser.ParsedOffer offer = new OfferParser.ParsedOffer("Red Potion", 0, 50_000_000L, item);
            HaggleSession session = new HaggleSession(PLAYER_ID, BOT_OWNER_ID);

            ShopOfferResponse.handlePresentOwner(player, shop, offer, session);

            assertEquals(50_000_000, item.getPrice(), "成交应把价格改为报价");
            verify(shop).broadcast(itemUpdate);
            verify(shop).chat(eq(owner), argThat(argThatNoPlaceholderAndContains("50m", "Red Potion")));
            verify(systemMock).lockItem(BOT_OWNER_ID, 0);
            verify(systemMock).removeSession(PLAYER_ID);
        }
    }

    // ─────────────────────────── PRESENT：COUNTER ───────────────────────────

    @Test
    void presentCounterSetsSessionCounterAndSpeaksCounterLine() {
        try (MockedStatic<OfferEvaluator> evaluator = mockStatic(OfferEvaluator.class);
             MockedStatic<Randomizer> rnd = mockStatic(Randomizer.class)) {
            rnd.when(Randomizer::nextInt).thenReturn(0);
            evaluator.when(() -> OfferEvaluator.evaluate(anyLong(), anyInt()))
                    .thenReturn(OfferEvaluator.Decision.COUNTER);
            evaluator.when(() -> OfferEvaluator.calculateCounterPrice(anyLong(), anyInt()))
                    .thenReturn(75_000_000L);

            Character owner = botOwner();
            PlayerShop shop = mock(PlayerShop.class);
            when(shop.getOwner()).thenReturn(owner);
            PlayerShopItem item = shopItem(100_000_000);

            Character player = player();
            OfferParser.ParsedOffer offer = new OfferParser.ParsedOffer("Red Potion", 0, 50_000_000L, item);
            HaggleSession session = new HaggleSession(PLAYER_ID, BOT_OWNER_ID);

            ShopOfferResponse.handlePresentOwner(player, shop, offer, session);

            assertEquals(75_000_000L, session.getCounterPrice(), "还价 = (报价 + 标价) / 2");
            assertTrue(session.hasCounterPending());
            assertEquals(100_000_000, item.getPrice(), "还价阶段不应改价");
            verify(shop).chat(eq(owner), argThat(argThatNoPlaceholderAndContains("75m")));
        }
    }

    @Test
    void presentDeclineOnlyChats() {
        try (MockedStatic<OfferEvaluator> evaluator = mockStatic(OfferEvaluator.class);
             MockedStatic<Randomizer> rnd = mockStatic(Randomizer.class)) {
            rnd.when(Randomizer::nextInt).thenReturn(0);
            evaluator.when(() -> OfferEvaluator.evaluate(anyLong(), anyInt()))
                    .thenReturn(OfferEvaluator.Decision.DECLINE);

            Character owner = botOwner();
            PlayerShop shop = mock(PlayerShop.class);
            when(shop.getOwner()).thenReturn(owner);
            PlayerShopItem item = shopItem(100_000_000);

            ShopOfferResponse.handlePresentOwner(player(), shop,
                    new OfferParser.ParsedOffer("Red Potion", 0, 50_000_000L, item),
                    new HaggleSession(PLAYER_ID, BOT_OWNER_ID));

            assertEquals(100_000_000, item.getPrice(), "拒绝不应改价");
            verify(shop).chat(eq(owner), argThat(argThatNoPlaceholder()));
        }
    }

    // ─────────────────────────── PRESENT：3 次上限踢人 ───────────────────────────

    @Test
    void thirdAttemptKicksAndBansPlayer() {
        try (MockedStatic<OfferEvaluator> evaluator = mockStatic(OfferEvaluator.class);
             MockedStatic<ShopOfferSystem> sys = mockStatic(ShopOfferSystem.class);
             MockedStatic<BotTiming> timing = mockStatic(BotTiming.class);
             MockedStatic<Randomizer> rnd = mockStatic(Randomizer.class)) {
            rnd.when(Randomizer::nextInt).thenReturn(0);
            ShopOfferSystem systemMock = mock(ShopOfferSystem.class);
            sys.when(ShopOfferSystem::getInstance).thenReturn(systemMock);
            evaluator.when(() -> OfferEvaluator.evaluate(anyLong(), anyInt()))
                    .thenReturn(OfferEvaluator.Decision.DECLINE);

            Character owner = botOwner();
            PlayerShop shop = mock(PlayerShop.class);
            when(shop.getOwner()).thenReturn(owner);
            PlayerShopItem item = shopItem(100_000_000);
            Character player = player();
            OfferParser.ParsedOffer offer = new OfferParser.ParsedOffer("Red Potion", 0, 50_000_000L, item);
            HaggleSession session = new HaggleSession(PLAYER_ID, BOT_OWNER_ID);

            ShopOfferResponse.handlePresentOwner(player, shop, offer, session); // 第 1 次
            ShopOfferResponse.handlePresentOwner(player, shop, offer, session); // 第 2 次
            assertEquals(2, session.getAttempts());

            ShopOfferResponse.handlePresentOwner(player, shop, offer, session); // 第 3 次 → 踢人

            verify(shop, atLeast(3)).chat(eq(owner), argThat(argThatNoPlaceholder()));
            ArgumentCaptor<Runnable> banCap = ArgumentCaptor.forClass(Runnable.class);
            timing.verify(() -> BotTiming.after(eq(2000L), banCap.capture()));
            banCap.getValue().run();
            verify(shop).banPlayer("PlayerX");
            verify(systemMock).removeSession(PLAYER_ID);
        }
    }

    // ─────────────────────────── PRESENT：达到还价即成交 ───────────────────────────

    @Test
    void offerMeetingCounterPriceAcceptsWithoutReEvaluate() {
        try (MockedStatic<OfferEvaluator> evaluator = mockStatic(OfferEvaluator.class);
             MockedStatic<PacketCreator> pc = mockStatic(PacketCreator.class);
             MockedStatic<ShopOfferSystem> sys = mockStatic(ShopOfferSystem.class);
             MockedStatic<Randomizer> rnd = mockStatic(Randomizer.class)) {
            rnd.when(Randomizer::nextInt).thenReturn(0);
            ShopOfferSystem systemMock = mock(ShopOfferSystem.class);
            sys.when(ShopOfferSystem::getInstance).thenReturn(systemMock);
            pc.when(() -> PacketCreator.getPlayerShopItemUpdate(any())).thenReturn(mock(Packet.class));

            Character owner = botOwner();
            PlayerShop shop = mock(PlayerShop.class);
            when(shop.getOwner()).thenReturn(owner);
            PlayerShopItem item = shopItem(100_000_000);

            HaggleSession session = new HaggleSession(PLAYER_ID, BOT_OWNER_ID);
            session.setCounterPrice(70_000_000L);

            // 玩家报价 75m ≥ 还价 70m → 以还价成交，不走 evaluate
            ShopOfferResponse.handlePresentOwner(player(), shop,
                    new OfferParser.ParsedOffer("Red Potion", 0, 75_000_000L, item), session);

            assertEquals(70_000_000, item.getPrice(), "成交价应为店主还价而非玩家新报价");
            evaluator.verify(() -> OfferEvaluator.evaluate(anyLong(), anyInt()), never());
        }
    }

    // ─────────────────────────── AFK：PlayerShop ───────────────────────────

    @Test
    void afkAcceptSchedulesDelayedPriceUpdateAndWhisper() {
        try (MockedStatic<OfferEvaluator> evaluator = mockStatic(OfferEvaluator.class);
             MockedStatic<PacketCreator> pc = mockStatic(PacketCreator.class);
             MockedStatic<ShopOfferSystem> sys = mockStatic(ShopOfferSystem.class);
             MockedStatic<BotTiming> timing = mockStatic(BotTiming.class)) {
            ShopOfferSystem systemMock = mock(ShopOfferSystem.class);
            sys.when(ShopOfferSystem::getInstance).thenReturn(systemMock);
            evaluator.when(() -> OfferEvaluator.evaluate(anyLong(), anyInt()))
                    .thenReturn(OfferEvaluator.Decision.ACCEPT);

            Character owner = botOwner();
            PlayerShop shop = mock(PlayerShop.class);
            when(shop.getOwner()).thenReturn(owner);
            when(shop.getMapId()).thenReturn(910000001);
            PlayerShopItem item = shopItem(100_000_000);
            Packet itemUpdate = mock(Packet.class);
            pc.when(() -> PacketCreator.getPlayerShopItemUpdate(shop)).thenReturn(itemUpdate);

            Character player = playerWithClient();
            OfferParser.ParsedOffer offer = new OfferParser.ParsedOffer("Red Potion", 0, 50_000_000L, item);

            ShopOfferResponse.handleAFKOwner(player, shop, offer, systemMock);

            verify(systemMock).lockItem(BOT_OWNER_ID, 0);

            ArgumentCaptor<Long> delayCap = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Runnable> runnableCap = ArgumentCaptor.forClass(Runnable.class);
            timing.verify(() -> BotTiming.after(delayCap.capture(), runnableCap.capture()));
            assertTrue(delayCap.getValue() >= 5 * 60_000 && delayCap.getValue() <= 30 * 60_000,
                    "AFK 改价延迟应在 5-30 分钟内");

            pc.when(() -> PacketCreator.getWhisperReceive(anyString(), anyInt(), eq(false), anyString()))
                    .thenReturn(mock(Packet.class));
            runnableCap.getValue().run();

            assertEquals(50_000_000, item.getPrice(), "延迟到期应改价");
            verify(shop).broadcast(itemUpdate);
            ArgumentCaptor<String> whisperCap = ArgumentCaptor.forClass(String.class);
            pc.verify(() -> PacketCreator.getWhisperReceive(eq("OwnerBot"), eq(1), eq(false), whisperCap.capture()));
            assertTrue(whisperCap.getValue().contains("Red Potion"), "私聊应提及物品名");
            assertTrue(whisperCap.getValue().contains("50m"), "私聊应提及成交价");
            assertTrue(whisperCap.getValue().contains("FM 1"), "私聊应提及房间号");
            verify(player).sendPacket(any());
        }
    }

    @Test
    void afkDeclineDoesNothing() {
        try (MockedStatic<OfferEvaluator> evaluator = mockStatic(OfferEvaluator.class);
             MockedStatic<BotTiming> timing = mockStatic(BotTiming.class)) {
            evaluator.when(() -> OfferEvaluator.evaluate(anyLong(), anyInt()))
                    .thenReturn(OfferEvaluator.Decision.DECLINE);

            Character owner = botOwner();
            PlayerShop shop = mock(PlayerShop.class);
            when(shop.getOwner()).thenReturn(owner);
            PlayerShopItem item = shopItem(100_000_000);

            ShopOfferResponse.handleAFKOwner(playerWithClient(), shop,
                    new OfferParser.ParsedOffer("Red Potion", 0, 30_000_000L, item), mock(ShopOfferSystem.class));

            timing.verify(() -> BotTiming.after(anyLong(), any()), never());
            assertEquals(100_000_000, item.getPrice());
        }
    }

    @Test
    void afkWhisperSkippedWhenPlayerOffline() {
        try (MockedStatic<OfferEvaluator> evaluator = mockStatic(OfferEvaluator.class);
             MockedStatic<PacketCreator> pc = mockStatic(PacketCreator.class);
             MockedStatic<ShopOfferSystem> sys = mockStatic(ShopOfferSystem.class);
             MockedStatic<BotTiming> timing = mockStatic(BotTiming.class)) {
            ShopOfferSystem systemMock = mock(ShopOfferSystem.class);
            sys.when(ShopOfferSystem::getInstance).thenReturn(systemMock);
            evaluator.when(() -> OfferEvaluator.evaluate(anyLong(), anyInt()))
                    .thenReturn(OfferEvaluator.Decision.ACCEPT);

            Character owner = botOwner();
            PlayerShop shop = mock(PlayerShop.class);
            when(shop.getOwner()).thenReturn(owner);
            when(shop.getMapId()).thenReturn(910000001);
            PlayerShopItem item = shopItem(100_000_000);
            pc.when(() -> PacketCreator.getPlayerShopItemUpdate(shop)).thenReturn(mock(Packet.class));

            Character player = mock(Character.class);
            when(player.getId()).thenReturn(PLAYER_ID);
            when(player.getName()).thenReturn("PlayerX");
            when(player.getClient()).thenReturn(null); // 已离线

            ShopOfferResponse.handleAFKOwner(player, shop,
                    new OfferParser.ParsedOffer("Red Potion", 0, 50_000_000L, item), systemMock);

            ArgumentCaptor<Runnable> runnableCap = ArgumentCaptor.forClass(Runnable.class);
            timing.verify(() -> BotTiming.after(anyLong(), runnableCap.capture()));
            runnableCap.getValue().run();

            assertEquals(50_000_000, item.getPrice(), "离线也应改价");
            verify(shop).broadcast(any());
            pc.verify(() -> PacketCreator.getWhisperReceive(anyString(), anyInt(), eq(false), anyString()), never());
        }
    }

    // ─────────────────────────── AFK：HiredMerchant ───────────────────────────

    @Test
    void hiredMerchantAfkSchedulesPriceUpdateWhisperAndBroadcast() {
        try (MockedStatic<OfferEvaluator> evaluator = mockStatic(OfferEvaluator.class);
             MockedStatic<PacketCreator> pc = mockStatic(PacketCreator.class);
             MockedStatic<BotTiming> timing = mockStatic(BotTiming.class)) {
            evaluator.when(() -> OfferEvaluator.evaluate(anyLong(), anyInt()))
                    .thenReturn(OfferEvaluator.Decision.ACCEPT);
            pc.when(() -> PacketCreator.getWhisperReceive(anyString(), anyInt(), eq(false), anyString()))
                    .thenReturn(mock(Packet.class));

            PlayerShopItem item = shopItem(100_000_000);
            Character player = playerWithClient();
            ShopOfferSystem systemMock = mock(ShopOfferSystem.class);
            Runnable broadcastUpdate = mock(Runnable.class);

            ShopOfferResponse.handleHiredMerchantAFK(player, "OwnerBot", BOT_OWNER_ID, 910000001,
                    item, new OfferParser.ParsedOffer("Red Potion", 0, 50_000_000L, item),
                    systemMock, broadcastUpdate);

            verify(systemMock).lockItem(BOT_OWNER_ID, 0);

            ArgumentCaptor<Runnable> runnableCap = ArgumentCaptor.forClass(Runnable.class);
            timing.verify(() -> BotTiming.after(anyLong(), runnableCap.capture()));
            runnableCap.getValue().run();

            assertEquals(50_000_000, item.getPrice());
            verify(broadcastUpdate).run();
            verify(player).sendPacket(any());
        }
    }

    @Test
    void hiredMerchantAfkDeclineDoesNothing() {
        try (MockedStatic<OfferEvaluator> evaluator = mockStatic(OfferEvaluator.class);
             MockedStatic<BotTiming> timing = mockStatic(BotTiming.class)) {
            evaluator.when(() -> OfferEvaluator.evaluate(anyLong(), anyInt()))
                    .thenReturn(OfferEvaluator.Decision.DECLINE);

            PlayerShopItem item = shopItem(100_000_000);
            ShopOfferResponse.handleHiredMerchantAFK(playerWithClient(), "OwnerBot", BOT_OWNER_ID, 910000001,
                    item, new OfferParser.ParsedOffer("Red Potion", 0, 30_000_000L, item),
                    mock(ShopOfferSystem.class), mock(Runnable.class));

            timing.verify(() -> BotTiming.after(anyLong(), any()), never());
            assertEquals(100_000_000, item.getPrice());
        }
    }

    // ─────────────────────────── 价格格式化 ───────────────────────────

    @Test
    void formatPriceUsesGameAbbreviations() {
        assertEquals("1b", ShopOfferResponse.formatPrice(1_000_000_000L));
        assertEquals("50m", ShopOfferResponse.formatPrice(50_000_000L));
        assertEquals("500k", ShopOfferResponse.formatPrice(500_000L));
        assertEquals("1500m", ShopOfferResponse.formatPrice(1_500_000_000L), "非整十亿回退到 m 表达");
        assertEquals("1234", ShopOfferResponse.formatPrice(1234L));
    }

    // ─────────────────────────── helpers ───────────────────────────

    private static Character botOwner() {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(BOT_OWNER_ID);
        when(owner.getName()).thenReturn("OwnerBot");
        return owner;
    }

    private static Character player() {
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("PlayerX");
        return player;
    }

    private static Character playerWithClient() {
        Character player = player();
        Client client = mock(Client.class);
        when(client.getChannel()).thenReturn(2); // 1-based，whisper 发送时 -1
        when(player.getClient()).thenReturn(client);
        return player;
    }

    private static PlayerShopItem shopItem(int price) {
        return new PlayerShopItem(new Item(2000000, (short) 0, (short) 1), (short) 1, price);
    }

    /** 台词替换后不应残留占位符；可附加要求包含某些片段。 */
    private static ArgumentMatcher<String> argThatNoPlaceholderAndContains(String... fragments) {
        return msg -> {
            if (msg == null || msg.isEmpty()) {
                return false;
            }
            if (msg.contains("{item}") || msg.contains("{price}") || msg.contains("{player}")
                    || msg.contains("{listing_price}") || msg.contains("{counter_price}")) {
                return false;
            }
            for (String fragment : fragments) {
                if (!msg.contains(fragment)) {
                    return false;
                }
            }
            return true;
        };
    }

    private static ArgumentMatcher<String> argThatNoPlaceholder() {
        return argThatNoPlaceholderAndContains();
    }
}
