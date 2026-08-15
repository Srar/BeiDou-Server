package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.EventType;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.bot.types.IdleBot;
import org.gms.server.bot.types.SocialBot;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生命周期组合场景（审查复盘补盲）：单测只覆盖单个操作，本类锁定跨操作组合——
 * stop→start 后订阅仍存活（onScheduledStart 回归）、SOCIAL↔IDLE 往返换型订阅数守恒、
 * 注册表 remove 后同 id 重建、FINISHED 拆卸后重启、重复 stop 幂等、start 前 nudge 被忽略。
 * <p>
 * 时序规避：启动用 {@link BotSM#startScheduledTask(long)} 60s 延迟（测试窗口内不被真实
 * 轮盘驱动）或经 manuallyStartBot 的 2-5s 出生编排窗口（测试毫秒级跑完，即使被驱动
 * 也无广播副作用）；事件消费通过手动 {@link BotSM#updateState()} 触发。
 */
class BotLifecycleCombinationTest {

    private static final int BASE_ID = 960_000_000;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(BASE_ID);
    private static final int MAP_ID = 100000000;

    private final int botId = NEXT_ID.incrementAndGet();

    private Character chr;
    private Client client;
    private MapleMap map;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        chr = Mockito.mock(Character.class);
        Mockito.when(chr.getId()).thenReturn(botId);
        Mockito.when(chr.getName()).thenReturn("ComboBot" + botId);
        Mockito.when(chr.getWorld()).thenReturn(0);
        Mockito.when(chr.getWhiteChat()).thenReturn(false);

        client = Mockito.mock(Client.class);
        Mockito.when(client.getChannel()).thenReturn(1);
        Mockito.when(chr.getClient()).thenReturn(client);

        map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getId()).thenReturn(MAP_ID);
        Mockito.when(map.getCharacters()).thenReturn(Collections.emptyList());
        Mockito.when(chr.getMap()).thenReturn(map);
    }

    @AfterEach
    void tearDown() {
        BotSM bot = BotStorage.getBotById(botId);
        if (bot != null) {
            bot.stopScheduledTask();
        }
        BotStorage.removeActiveBot(botId);
        BotTickService.unregister(botId);
        BotEventBus.getInstance().reset();
    }

    @Test
    void startStopCycleKeepsBotFunctional() {
        BotTypeManager.BotType.SOCIAL_BOT.createAndSetBot(chr);

        for (int i = 1; i <= 2; i++) {
            BotTypeManager.manuallyStartBot(chr);
            assertTrue(BotStorage.getBotById(botId).getRunning(), "start #" + i + " must set running");
            assertTrue(BotTickService.isRegistered(botId), "start #" + i + " must register the wheel");

            BotTypeManager.manuallyStopBot(chr);
            assertFalse(BotStorage.getBotById(botId).getRunning(), "stop #" + i + " must clear running");
            assertFalse(BotTickService.isRegistered(botId), "stop #" + i + " must unregister the wheel");
        }

        // 第 3 次 start 后不再 stop：验证 stop→start 循环后订阅仍存活（onScheduledStart 回归）
        BotTypeManager.manuallyStartBot(chr);
        assertTrue(BotStorage.getBotById(botId).getRunning());
        assertTrue(BotTickService.isRegistered(botId));
        assertEquals(1, BotEventBus.getInstance().subscriberCount(EventType.LEVEL_UP),
                "subscriber must survive two stop→start cycles");

        // 订阅存活还不够——事件链路必须端到端可用（发布 → onEvent 入队 → tick 消费）。
        // 新 SocialBot 只消费 LEVEL_UP（祝贺），且反应是概率 + 错开延迟，故这里锁定确定性入队/出队路径。
        BotEventBus.getInstance().publish(GameEvent.levelUp(0, 1, MAP_ID, 5));
        BotSM bot = BotStorage.getBotById(botId);
        assertTrue(bot.hasQueuedEvents(), "matched event must be queued to the bot's buffer");
        bot.processQueuedEvents();
        assertFalse(bot.hasQueuedEvents(), "processQueuedEvents must drain the buffered event through handleEvent");
    }

    @Test
    void convertBackAndForthKeepsSingleSubscriber() {
        BotEventBus bus = BotEventBus.getInstance();

        BotTypeManager.BotType.SOCIAL_BOT.createAndSetBot(chr);
        assertEquals(1, bus.subscriberCount(EventType.LEVEL_UP), "SocialBot must subscribe on creation");
        assertInstanceOf(SocialBot.class, BotStorage.getBotById(botId));

        assertTrue(BotTypeManager.convertBotType(chr, BotTypeManager.BotType.IDLE_BOT));
        assertEquals(0, bus.subscriberCount(EventType.LEVEL_UP), "converting to IDLE must drop the LEVEL_UP subscriber");
        assertInstanceOf(IdleBot.class, BotStorage.getBotById(botId));

        assertTrue(BotTypeManager.convertBotType(chr, BotTypeManager.BotType.SOCIAL_BOT));
        assertEquals(1, bus.subscriberCount(EventType.LEVEL_UP), "converting back must restore exactly one subscriber");
        assertInstanceOf(SocialBot.class, BotStorage.getBotById(botId));

        assertTrue(BotTypeManager.convertBotType(chr, BotTypeManager.BotType.IDLE_BOT));
        assertEquals(0, bus.subscriberCount(EventType.LEVEL_UP), "second round trip must leave zero subscribers");
        assertInstanceOf(IdleBot.class, BotStorage.getBotById(botId));
    }

    @Test
    void removeThenRecreateSameIdWorks() {
        BotTypeManager.BotType.IDLE_BOT.createAndSetBot(chr);
        BotSM first = BotStorage.getBotById(botId);
        assertNotNull(first);

        BotStorage.removeActiveBot(botId);
        assertNull(BotStorage.getBotById(botId), "registry must forget the removed bot");

        // 同 id 重建：注册表 remove 后 put 必须得到全新实例
        BotTypeManager.BotType.IDLE_BOT.createAndSetBot(chr);
        BotSM second = BotStorage.getBotById(botId);
        assertNotNull(second, "recreate must land in the registry");
        assertNotSame(first, second, "recreate must produce a fresh instance, not resurrect the old one");

        BotTypeManager.manuallyStartBot(chr);
        assertTrue(second.getRunning(), "the new instance must be startable");
        assertTrue(BotTickService.isRegistered(botId), "the new instance must register the wheel");
    }

    @Test
    void finishedTeardownThenRestartWorks() {
        BotTypeManager.BotType.IDLE_BOT.createAndSetBot(chr);
        BotSM bot = BotStorage.getBotById(botId);

        bot.setRunning(true);
        bot.startScheduledTask(60_000); // 测试窗口内不被真实轮盘驱动
        assertTrue(BotTickService.isRegistered(botId));

        bot.updateState(); // IDLE → RUNNING（在线判据成立）
        assertEquals(BotSM.BotState.RUNNING, bot.getState());

        // 模拟掉线：RUNNING → FINISHED → IDLE（FINISHED 单拍拆卸）
        BotStorage.removeActiveBot(botId);
        bot.updateState();
        assertEquals(BotSM.BotState.FINISHED, bot.getState());
        bot.updateState();
        assertEquals(BotSM.BotState.IDLE, bot.getState(), "teardown must land back on IDLE");
        assertFalse(bot.getRunning(), "FINISHED teardown must clear running");
        assertFalse(BotTickService.isRegistered(botId), "FINISHED teardown must unregister the wheel");

        // 重新上线 + 重启调度
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        bot.startScheduledTask(60_000);
        assertTrue(BotTickService.isRegistered(botId), "restart must re-register the wheel");

        bot.updateState(); // IDLE → RUNNING
        assertEquals(BotSM.BotState.RUNNING, bot.getState(), "restarted bot must return to RUNNING");
        assertTrue(bot.getRunning());
    }

    @Test
    void stopScheduledTaskTwiceIsIdempotent() {
        BotTypeManager.BotType.IDLE_BOT.createAndSetBot(chr);
        BotSM bot = BotStorage.getBotById(botId);
        bot.setRunning(true);
        bot.startScheduledTask(60_000);
        assertTrue(BotTickService.isRegistered(botId));

        bot.stopScheduledTask();
        assertFalse(BotTickService.isRegistered(botId));

        bot.stopScheduledTask(); // 重复 stop：必须静默、无副作用
        assertFalse(BotTickService.isRegistered(botId));
        assertEquals(BotSM.BotState.IDLE, bot.getState(), "double stop must not disturb the state");
    }

    @Test
    void nudgeBeforeStartIsIgnored() {
        BotTypeManager.BotType.IDLE_BOT.createAndSetBot(chr);
        BotSM bot = BotStorage.getBotById(botId);
        bot.setRunning(true); // running 但从未 startScheduledTask：轮盘上无条目

        bot.nudgeSoon(100); // 未注册：静默忽略，不抛异常、不注册轮盘

        assertFalse(BotTickService.isRegistered(botId), "nudge before start must not register the wheel");
        assertNull(BotTickService.nextDueMs(botId), "nudge before start must not create a wheel entry");
    }
}
