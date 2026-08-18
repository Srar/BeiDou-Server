package org.gms.server.bot.freemarket.shopoffer;

import org.gms.client.Character;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotTiming;
import org.gms.server.bot.dialogue.BotDialogueHandler;
import org.gms.server.maps.PlayerShop;
import org.gms.test.BotTestSupport;
import org.gms.util.Randomizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ShopOfferWelcome 单测：PRESENT 模式进店 60% 概率 15-20 秒打招呼、
 * AFK 模式不招呼、店内闲聊满 2 条触发报价提示（仅一次）、报价消息不计数。
 */
class ShopOfferWelcomeTest {

    private static final int BOT_OWNER_ID = 9_700_001;
    private static final int PLAYER_ID = 5;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @AfterEach
    void resetStaticState() {
        clearField("playerMessageCounts");
        clearField("hintedPlayers");
    }

    @Test
    void presentModeGreetsAfterFifteenToTwentySeconds() {
        try (MockedStatic<BotHelpers> helpers = mockStatic(BotHelpers.class);
             MockedStatic<ShopOfferSystem> sys = mockStatic(ShopOfferSystem.class);
             MockedStatic<Randomizer> rnd = mockStatic(Randomizer.class);
             MockedStatic<BotTiming> timing = mockStatic(BotTiming.class)) {
            rnd.when(Randomizer::nextDouble).thenReturn(0.5); // < 0.60 → 触发
            rnd.when(Randomizer::nextInt).thenReturn(0);      // 延迟抖动 0，台词固定第一行
            helpers.when(() -> BotHelpers.isBot(any(Character.class))).thenReturn(true);
            ShopOfferSystem system = mock(ShopOfferSystem.class);
            sys.when(ShopOfferSystem::getInstance).thenReturn(system);
            when(system.getOrAssignMode(BOT_OWNER_ID)).thenReturn(ShopOfferSystem.ShopMode.PRESENT);

            Character owner = botOwner();
            PlayerShop shop = mock(PlayerShop.class);
            when(shop.getOwner()).thenReturn(owner);
            Character visitor = visitorIn(shop);

            ShopOfferWelcome.onPlayerEnterShop(shop, visitor);

            ArgumentCaptor<Long> delayCap = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Runnable> runnableCap = ArgumentCaptor.forClass(Runnable.class);
            timing.verify(() -> BotTiming.after(delayCap.capture(), runnableCap.capture()));
            assertTrue(delayCap.getValue() >= 15_000 && delayCap.getValue() <= 20_000, "打招呼延迟应在 15-20 秒内");

            runnableCap.getValue().run();
            // Randomizer.nextInt → 0 → WelcomeResponse 第一行；M4：{player} 占位替换为访客名
            BotDialogueHandler.DialogueConstructor dialog =
                    BotDialogueHandler.getDialogueCon("ShopOfferDialogue.yaml", "ShopOffer", "WelcomeResponse");
            String expected = ShopOfferResponse.replacePlayerPlaceholder(dialog.getDialogue().get(0), visitor);
            assertEquals(expected, capturedChatLine(shop));
            verify(shop).chat(eq(owner), any());
        }
    }

    @Test
    void afkModeSkipsGreeting() {
        try (MockedStatic<BotHelpers> helpers = mockStatic(BotHelpers.class);
             MockedStatic<ShopOfferSystem> sys = mockStatic(ShopOfferSystem.class);
             MockedStatic<Randomizer> rnd = mockStatic(Randomizer.class);
             MockedStatic<BotTiming> timing = mockStatic(BotTiming.class)) {
            rnd.when(Randomizer::nextDouble).thenReturn(0.0); // 即使随机命中
            helpers.when(() -> BotHelpers.isBot(any(Character.class))).thenReturn(true);
            ShopOfferSystem system = mock(ShopOfferSystem.class);
            sys.when(ShopOfferSystem::getInstance).thenReturn(system);
            when(system.getOrAssignMode(BOT_OWNER_ID)).thenReturn(ShopOfferSystem.ShopMode.AFK);

            PlayerShop shop = mock(PlayerShop.class);
            Character owner = botOwner();
            when(shop.getOwner()).thenReturn(owner);

            ShopOfferWelcome.onPlayerEnterShop(shop, visitorIn(shop));

            timing.verify(() -> BotTiming.after(anyLong(), any()), never());
        }
    }

    @Test
    void welcomeChanceMissSkipsGreeting() {
        try (MockedStatic<BotHelpers> helpers = mockStatic(BotHelpers.class);
             MockedStatic<ShopOfferSystem> sys = mockStatic(ShopOfferSystem.class);
             MockedStatic<Randomizer> rnd = mockStatic(Randomizer.class);
             MockedStatic<BotTiming> timing = mockStatic(BotTiming.class)) {
            rnd.when(Randomizer::nextDouble).thenReturn(0.9); // ≥ 0.60 → 不触发
            helpers.when(() -> BotHelpers.isBot(any(Character.class))).thenReturn(true);
            ShopOfferSystem system = mock(ShopOfferSystem.class);
            sys.when(ShopOfferSystem::getInstance).thenReturn(system);
            when(system.getOrAssignMode(BOT_OWNER_ID)).thenReturn(ShopOfferSystem.ShopMode.PRESENT);

            PlayerShop shop = mock(PlayerShop.class);
            Character owner = botOwner();
            when(shop.getOwner()).thenReturn(owner);

            ShopOfferWelcome.onPlayerEnterShop(shop, visitorIn(shop));

            timing.verify(() -> BotTiming.after(anyLong(), any()), never());
        }
    }

    @Test
    void hintSentAfterTwoChatsOnlyOnce() {
        try (MockedStatic<BotHelpers> helpers = mockStatic(BotHelpers.class);
             MockedStatic<ShopOfferSystem> sys = mockStatic(ShopOfferSystem.class);
             MockedStatic<Randomizer> rnd = mockStatic(Randomizer.class);
             MockedStatic<BotTiming> timing = mockStatic(BotTiming.class);
             MockedStatic<OfferParser> parser = mockStatic(OfferParser.class)) {
            rnd.when(Randomizer::nextInt).thenReturn(0);
            helpers.when(() -> BotHelpers.isBot(any(Character.class))).thenReturn(true);
            parser.when(() -> OfferParser.parse(anyString(), anyList())).thenReturn(null); // 非报价闲聊
            ShopOfferSystem system = mock(ShopOfferSystem.class);
            sys.when(ShopOfferSystem::getInstance).thenReturn(system);
            when(system.getOrAssignMode(BOT_OWNER_ID)).thenReturn(ShopOfferSystem.ShopMode.PRESENT);

            Character owner = botOwner();
            PlayerShop shop = mock(PlayerShop.class);
            when(shop.getOwner()).thenReturn(owner);
            Character visitor = visitorIn(shop);

            ShopOfferWelcome.onPlayerChat(visitor, shop, "随便聊聊"); // 第 1 条
            timing.verify(() -> BotTiming.after(anyLong(), any()), never());

            ShopOfferWelcome.onPlayerChat(visitor, shop, "在吗"); // 第 2 条 → 提示
            ArgumentCaptor<Runnable> runnableCap = ArgumentCaptor.forClass(Runnable.class);
            timing.verify(() -> BotTiming.after(anyLong(), runnableCap.capture()));
            runnableCap.getValue().run();
            verify(shop).chat(eq(owner), eq("小提示：想砍价的话，输入价格（50m、1.5b）和物品名。同名多件用 1st/2nd/3rd 区分！"));

            ShopOfferWelcome.onPlayerChat(visitor, shop, "多少钱"); // 第 3 条 → 已提示不再触发
            timing.verify(() -> BotTiming.after(anyLong(), any()), times(1));
        }
    }

    @Test
    void parsedOfferChatSkipsHintCounting() {
        try (MockedStatic<BotHelpers> helpers = mockStatic(BotHelpers.class);
             MockedStatic<ShopOfferSystem> sys = mockStatic(ShopOfferSystem.class);
             MockedStatic<BotTiming> timing = mockStatic(BotTiming.class);
             MockedStatic<OfferParser> parser = mockStatic(OfferParser.class)) {
            helpers.when(() -> BotHelpers.isBot(any(Character.class))).thenReturn(true);
            parser.when(() -> OfferParser.parse(anyString(), anyList()))
                    .thenReturn(mock(OfferParser.ParsedOffer.class)); // 报价消息
            ShopOfferSystem system = mock(ShopOfferSystem.class);
            sys.when(ShopOfferSystem::getInstance).thenReturn(system);
            when(system.getOrAssignMode(BOT_OWNER_ID)).thenReturn(ShopOfferSystem.ShopMode.PRESENT);

            PlayerShop shop = mock(PlayerShop.class);
            Character owner = botOwner();
            when(shop.getOwner()).thenReturn(owner);
            Character visitor = visitorIn(shop);

            ShopOfferWelcome.onPlayerChat(visitor, shop, "50m Red Potion");
            ShopOfferWelcome.onPlayerChat(visitor, shop, "60m Red Potion");

            timing.verify(() -> BotTiming.after(anyLong(), any()), never());
        }
    }

    @Test
    void clearShopDataRemovesOnlyTargetOwner() {
        messageCounts().put(BOT_OWNER_ID + "_" + PLAYER_ID, 1);
        messageCounts().put((BOT_OWNER_ID + 1) + "_" + PLAYER_ID, 1);

        ShopOfferWelcome.clearShopData(BOT_OWNER_ID);

        assertFalse(messageCounts().containsKey(BOT_OWNER_ID + "_" + PLAYER_ID), "目标店主的数据应被清空");
        assertTrue(messageCounts().containsKey((BOT_OWNER_ID + 1) + "_" + PLAYER_ID), "其他店主的数据应保留");
    }

    // ─────────────────────────── helpers ───────────────────────────

    private static Character botOwner() {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(BOT_OWNER_ID);
        when(owner.getName()).thenReturn("OwnerBot");
        return owner;
    }

    private static Character visitorIn(PlayerShop shop) {
        Character visitor = mock(Character.class);
        when(visitor.getId()).thenReturn(PLAYER_ID);
        when(visitor.getName()).thenReturn("PlayerX");
        when(visitor.getPlayerShop()).thenReturn(shop); // 仍在店内
        return visitor;
    }

    private static String capturedChatLine(PlayerShop shop) {
        ArgumentCaptor<String> lineCap = ArgumentCaptor.forClass(String.class);
        verify(shop).chat(any(Character.class), lineCap.capture());
        return lineCap.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> messageCounts() {
        try {
            Field f = ShopOfferWelcome.class.getDeclaredField("playerMessageCounts");
            f.setAccessible(true);
            return (Map<String, Integer>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to read ShopOfferWelcome.playerMessageCounts", e);
        }
    }

    private static void clearField(String name) {
        try {
            Field f = ShopOfferWelcome.class.getDeclaredField(name);
            f.setAccessible(true);
            Object value = f.get(null);
            if (value instanceof Map) {
                ((Map<?, ?>) value).clear();
            } else if (value instanceof Set) {
                ((Set<?>) value).clear();
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to clear ShopOfferWelcome." + name, e);
        }
    }
}
