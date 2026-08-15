package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.EventType;
import org.gms.server.bot.types.IdleBot;
import org.gms.server.bot.types.SocialBot;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BotTypeManager：类型工厂注册、启动/停止（running + 轮盘注册）、
 * 换型（TRADING 拒绝）、启动幂等。
 */
class BotTypeManagerTest {

    private static final int BASE_ID = 9_700_000;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(BASE_ID);

    private final int botId = NEXT_ID.incrementAndGet();

    private Character chr;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        chr = Mockito.mock(Character.class);
        Mockito.when(chr.getId()).thenReturn(botId);
        Mockito.when(chr.getName()).thenReturn("Bot" + botId);
        Mockito.when(chr.getWorld()).thenReturn(0);
    }

    @AfterEach
    void tearDown() {
        BotSM bot = BotStorage.getBotById(botId);
        if (bot != null) {
            bot.setRunning(false);
            bot.stopScheduledTask();
        }
        BotStorage.removeActiveBot(botId);
        BotTickService.unregister(botId);
        BotEventBus.getInstance().reset();
    }

    @Test
    void createAndSetBotRegistersInStorage() {
        BotTypeManager.BotType.SOCIAL_BOT.createAndSetBot(chr);

        BotSM bot = BotStorage.getBotById(botId);
        assertNotNull(bot, "bot must be registered in storage");
        assertInstanceOf(SocialBot.class, bot);
        assertEquals("SocialBot", bot.getBotType());
    }

    @Test
    void idleTypeCreatesIdleBot() {
        BotTypeManager.BotType.IDLE_BOT.createAndSetBot(chr);
        assertInstanceOf(IdleBot.class, BotStorage.getBotById(botId));
    }

    @Test
    void manuallyStartBotRegistersWheelAndRunning() {
        BotTypeManager.BotType.SOCIAL_BOT.createAndSetBot(chr);
        long t0 = System.currentTimeMillis();
        BotTypeManager.manuallyStartBot(chr);

        BotSM bot = BotStorage.getBotById(botId);
        assertTrue(bot.getRunning());
        assertTrue(BotTickService.isRegistered(botId), "started bot must be registered on the tick wheel");

        // 首 tick 延迟在 [SPAWN_CHOREOGRAPHY_MAX_MS(2000), 2000+3000) 区间；
        // 以 start 前时刻为基准计算期望区间，避免 start→断言之间的耗时侵蚀下界余量
        Long due = BotTickService.nextDueMs(botId);
        assertNotNull(due);
        assertTrue(due >= t0 + 1_900 && due <= t0 + 5_100,
                "first tick delay out of bounds: " + (due - t0) + "ms");
    }

    @Test
    void manuallyStartBotIsIdempotent() {
        BotTypeManager.BotType.SOCIAL_BOT.createAndSetBot(chr);
        BotTypeManager.manuallyStartBot(chr);
        Long firstDue = BotTickService.nextDueMs(botId);

        BotTypeManager.manuallyStartBot(chr); // running 已为 true：直接返回

        assertTrue(BotStorage.getBotById(botId).getRunning());
        assertEquals(firstDue, BotTickService.nextDueMs(botId), "second start must not reschedule");
    }

    @Test
    void manuallyStopBotUnregistersWheel() {
        BotTypeManager.BotType.SOCIAL_BOT.createAndSetBot(chr);
        BotTypeManager.manuallyStartBot(chr);
        assertTrue(BotTickService.isRegistered(botId));

        BotTypeManager.manuallyStopBot(chr);

        assertFalse(BotStorage.getBotById(botId).getRunning());
        assertFalse(BotTickService.isRegistered(botId), "stopped bot must leave the tick wheel");
    }

    @Test
    void convertBotTypeSwapsAndRestarts() {
        BotTypeManager.BotType.SOCIAL_BOT.createAndSetBot(chr);
        BotTypeManager.manuallyStartBot(chr);

        boolean converted = BotTypeManager.convertBotType(chr, BotTypeManager.BotType.IDLE_BOT);

        assertTrue(converted);
        BotSM bot = BotStorage.getBotById(botId);
        assertInstanceOf(IdleBot.class, bot, "convert must wrap the same character in the new type");
        assertTrue(bot.getRunning());
        assertTrue(BotTickService.isRegistered(botId));
    }

    @Test
    void convertBotTypeCleansOldSubscriptions() {
        BotTypeManager.BotType.SOCIAL_BOT.createAndSetBot(chr);
        BotTypeManager.manuallyStartBot(chr);
        assertEquals(1, BotEventBus.getInstance().subscriberCount(EventType.LEVEL_UP),
                "social bot must subscribe on construction");

        BotTypeManager.convertBotType(chr, BotTypeManager.BotType.IDLE_BOT);

        assertEquals(0, BotEventBus.getInstance().subscriberCount(EventType.LEVEL_UP),
                "converting away from SocialBot must unsubscribe the old LEVEL_UP subscription");
    }

    @Test
    void stopThenStartRestoresSocialBotSubscription() {
        BotTypeManager.BotType.SOCIAL_BOT.createAndSetBot(chr);
        BotTypeManager.manuallyStartBot(chr);
        BotTypeManager.manuallyStopBot(chr);
        assertEquals(0, BotEventBus.getInstance().subscriberCount(EventType.LEVEL_UP),
                "stopping must unsubscribe");

        BotTypeManager.manuallyStartBot(chr);
        assertEquals(1, BotEventBus.getInstance().subscriberCount(EventType.LEVEL_UP),
                "re-starting a SocialBot must re-subscribe its LEVEL_UP listener (onScheduledStart hook)");
    }

    @Test
    void startAllAndStopAllOperateOnAllRegisteredBots() {
        BotTypeManager.BotType.IDLE_BOT.createAndSetBot(chr);
        BotTypeManager.startAllBots();
        assertTrue(BotStorage.getBotById(botId).getRunning());
        assertTrue(BotTickService.isRegistered(botId));

        BotTypeManager.stopAllBots();
        assertFalse(BotStorage.getBotById(botId).getRunning());
        assertFalse(BotTickService.isRegistered(botId));
    }

    @Test
    void convertBotTypeRefusedWhileTrading() {
        BotTypeManager.BotType.SOCIAL_BOT.createAndSetBot(chr);
        BotStorage.getBotById(botId).setState(BotSM.BotState.TRADING);

        boolean converted = BotTypeManager.convertBotType(chr, BotTypeManager.BotType.IDLE_BOT);

        assertFalse(converted);
        assertInstanceOf(SocialBot.class, BotStorage.getBotById(botId),
                "mid-trade conversion must leave the original bot untouched");
    }

    @Test
    void startStopWithoutRegisteredBotIsSafe() {
        BotTypeManager.manuallyStartBot(chr); // 未注册类型：安全 no-op
        BotTypeManager.manuallyStopBot(chr);
        assertNull(BotStorage.getBotById(botId));
    }
}
