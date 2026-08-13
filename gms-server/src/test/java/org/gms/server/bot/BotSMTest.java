package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.bot.types.IdleBot;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BotSM 状态骨架：IDLE→RUNNING→FINISHED→IDLE 流转、waitFor 门控、
 * 观察分级调速（low/normal 档位与翻转）、updateScheduleDelay 短路、
 * nudgeSoon 的拉前/去抖/交易拒绝语义。
 * <p>
 * 时序相关断言避免等待真实轮盘：注册时用 60s 初始延迟（测试窗口内不会被驱动），
 * 经 {@link BotTickService#nextDueMs} 观察轮盘状态。
 */
class BotSMTest {

    private static final int BASE_ID = 9_800_000;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(BASE_ID);

    private final int botId = NEXT_ID.incrementAndGet();

    private Character chr;
    private Client client;
    private MapleMap map;
    private IdleBot bot;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        chr = Mockito.mock(Character.class);
        Mockito.when(chr.getId()).thenReturn(botId);
        Mockito.when(chr.getName()).thenReturn("BotX" + botId);
        Mockito.when(chr.getWorld()).thenReturn(0);

        client = Mockito.mock(Client.class);
        Mockito.when(client.getChannel()).thenReturn(1);
        Mockito.when(chr.getClient()).thenReturn(client);

        map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getId()).thenReturn(100000000);
        Mockito.when(map.getCharacters()).thenReturn(Collections.emptyList());
        Mockito.when(chr.getMap()).thenReturn(map);

        bot = new IdleBot(chr);
    }

    @AfterEach
    void tearDown() {
        bot.setRunning(false);
        bot.stopScheduledTask();
        BotStorage.removeActiveBot(botId);
        BotTickService.unregister(botId);
    }

    @Test
    void initialStateIsIdleAndUnregistered() {
        assertEquals(BotSM.BotState.IDLE, bot.getState());
        assertFalse(bot.getRunning());
        assertFalse(BotTickService.isRegistered(botId));
    }

    @Test
    void idleMovesToRunningWhenStarted() {
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        bot.updateState();

        assertEquals(BotSM.BotState.RUNNING, bot.getState());
    }

    @Test
    void idleStaysIdleWhenNotStarted() {
        // 未启动（running=false）即使注册表存在也不动
        BotStorage.addActiveBot(botId, bot);
        bot.updateState();

        assertEquals(BotSM.BotState.IDLE, bot.getState());
    }

    @Test
    void offlineBotFinishesThenReturnsToIdle() {
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        bot.updateState();
        assertEquals(BotSM.BotState.RUNNING, bot.getState());

        // 被移出注册表 = 掉线
        BotStorage.removeActiveBot(botId);
        bot.updateState();
        assertEquals(BotSM.BotState.FINISHED, bot.getState());

        bot.updateState();
        assertEquals(BotSM.BotState.IDLE, bot.getState());
        assertFalse(bot.getRunning(), "FINISHED teardown must clear running");
    }

    @Test
    void finishedTeardownUnregistersWheel() {
        bot.startScheduledTask(60_000); // 测试窗口内不会被驱动
        assertTrue(BotTickService.isRegistered(botId));

        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        bot.updateState(); // → RUNNING
        BotStorage.removeActiveBot(botId);
        bot.updateState(); // → FINISHED
        bot.updateState(); // → IDLE（拆卸：stopScheduledTask）

        assertFalse(BotTickService.isRegistered(botId), "FINISHED must unregister the wheel entry");
    }

    @Test
    void waitForGatesUntilDeadline() throws Exception {
        long start = System.currentTimeMillis();
        bot.waitFor(250);
        assertTrue(bot.isWaiting(), "isWaiting must be true inside the wait window");
        long waitUntil = bot.getWaitUntilMs();
        assertTrue(waitUntil >= start + 250 - 5 && waitUntil <= start + 250 + 30,
                "waitUntilMs out of bounds: " + (waitUntil - start));

        Thread.sleep(350);
        assertFalse(bot.isWaiting(), "isWaiting must be false after the wait passes");
    }

    @Test
    void waitForRandomStaysInRange() {
        long start = System.currentTimeMillis();
        bot.waitForRandom(500, 500);
        long delta = bot.getWaitUntilMs() - start;
        assertTrue(delta >= 500 - 5 && delta <= 500 + 30, "waitForRandom out of range: " + delta);
    }

    @Test
    void unobservedMapDropsToLowCadence() {
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);

        // 空图 → 未观察 → low 档 9-12s
        bot.checkPrioritySpeed();
        assertFalse(bot.isCadenceObserved());
        long delay = bot.getCurrentDelay();
        assertTrue(delay >= 9000 && delay <= 11999, "low cadence out of [9000,11999]: " + delay);
    }

    @Test
    void observedMapRestoresNormalCadence() {
        Character player = Mockito.mock(Character.class);
        Mockito.when(player.getId()).thenReturn(5); // 非 bot
        Mockito.when(map.getCharacters()).thenReturn(List.of(player));

        bot.checkPrioritySpeed();
        assertTrue(bot.isCadenceObserved());
        long delay = bot.getCurrentDelay();
        assertTrue(delay >= 2000 && delay <= 5999, "normal cadence out of [2000,5999]: " + delay);
    }

    @Test
    void cadenceFlipReschedulesOnlyOnFlip() {
        // 先落到未观察
        bot.checkPrioritySpeed();
        assertFalse(bot.isCadenceObserved());
        long low = bot.getCurrentDelay();

        // 仍未观察：不翻转，档位不变（重复断言 low 档）
        bot.checkPrioritySpeed();
        assertFalse(bot.isCadenceObserved());

        // 有玩家进图：翻回观察档
        Character player = Mockito.mock(Character.class);
        Mockito.when(player.getId()).thenReturn(5);
        Mockito.when(map.getCharacters()).thenReturn(List.of(player));
        bot.checkPrioritySpeed();
        assertTrue(bot.isCadenceObserved());
        long normal = bot.getCurrentDelay();
        assertTrue(normal >= 2000 && normal <= 5999);
        assertFalse(low >= 2000 && low <= 5999, "low cadence must differ from normal range");
    }

    @Test
    void updateScheduleDelayShortCircuits() {
        long before = bot.getCurrentDelay();
        bot.updateScheduleDelay(before);
        assertEquals(before, bot.getCurrentDelay(), "same delay must not change");

        bot.updateScheduleDelay(12_345);
        assertEquals(12_345, bot.getCurrentDelay());
    }

    @Test
    void nudgeSoonPullsNextTickForward() {
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        bot.startScheduledTask(60_000);
        Long before = BotTickService.nextDueMs(botId);
        assertNotNull(before);
        long now = System.currentTimeMillis();
        assertTrue(before >= now + 55_000, "expected far-future due, got " + (before - now) + "ms away");

        bot.nudgeSoon(500);
        Long after = BotTickService.nextDueMs(botId);
        assertNotNull(after);
        assertTrue(after <= now + 1_500, "nudge must pull next due forward to ~now+500, got "
                + (after - now) + "ms away");
        assertTrue(after < before, "nudge must move the next due earlier");
    }

    @Test
    void nudgeSoonIsDebounced() {
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        bot.startScheduledTask(60_000);

        bot.nudgeSoon(400);
        Long first = BotTickService.nextDueMs(botId);
        assertNotNull(first);

        bot.nudgeSoon(400); // 去抖窗口内：第二次被拒
        Long second = BotTickService.nextDueMs(botId);
        assertEquals(first, second, "debounced nudge must not move the next due again");
    }

    @Test
    void nudgeSoonRefusedWhileTrading() {
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        bot.startScheduledTask(60_000);
        Long before = BotTickService.nextDueMs(botId);

        bot.setState(BotSM.BotState.TRADING);
        bot.nudgeSoon(100);

        assertEquals(before, BotTickService.nextDueMs(botId), "trading bot must ignore nudges");
    }

    @Test
    void nudgeSoonIgnoredWhenNotRegistered() {
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        bot.nudgeSoon(100); // 未注册：静默忽略，不抛异常
        assertFalse(BotTickService.isRegistered(botId));
    }

    @Test
    void stopScheduledTaskUnsubscribesAndUnregisters() {
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        bot.startScheduledTask(60_000);
        assertTrue(BotTickService.isRegistered(botId));

        bot.stopScheduledTask();
        assertFalse(BotTickService.isRegistered(botId));
    }

    @Test
    void pausedBotResumesWhenPlayerEnters() {
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        bot.setState(BotSM.BotState.PAUSE);

        bot.updateState(); // 空图：保持 PAUSE
        assertEquals(BotSM.BotState.PAUSE, bot.getState());

        Character player = Mockito.mock(Character.class);
        Mockito.when(player.getId()).thenReturn(5);
        Mockito.when(map.getCharacters()).thenReturn(List.of(player));

        bot.updateState(); // 有真人进图：恢复 RUNNING
        assertEquals(BotSM.BotState.RUNNING, bot.getState());
    }

    @Test
    void pausedBotOfflineMovesToFinished() {
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        bot.setState(BotSM.BotState.PAUSE);

        BotStorage.removeActiveBot(botId); // 掉线
        bot.updateState();
        assertEquals(BotSM.BotState.FINISHED, bot.getState(),
                "offline PAUSE bot must be torn down, not stuck in PAUSE forever");
    }

    @Test
    void tradingBotWithoutPartnerReturnsToRunning() {
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        bot.setState(BotSM.BotState.TRADING);

        bot.updateState(); // v1 无交易子系统：无伙伴 → cleanup + waitFor(2000) + RUNNING

        assertEquals(BotSM.BotState.RUNNING, bot.getState());
        assertTrue(bot.isWaiting(), "trade close must settle with a waitFor(2000) beat");
    }

    @Test
    void tradingBotOfflineMovesToFinished() {
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        bot.setState(BotSM.BotState.TRADING);

        BotStorage.removeActiveBot(botId); // 掉线
        bot.updateState();
        assertEquals(BotSM.BotState.FINISHED, bot.getState(),
                "offline TRADING bot must be torn down");
    }

    @Test
    void matchesFilterWithNullClientIsSafe() {
        Mockito.when(chr.getClient()).thenReturn(null);

        // 发布线程上执行：client 为 null 时必须安全拒绝而非 NPE（总线无异常保护）
        boolean matched = bot.matchesFilter(GameEvent.chat(0, 1, 100000000, 5, "hi"));
        assertFalse(matched);
    }

    @Test
    void setStateRejectsNull() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> bot.setState(null));
    }

    @Test
    void nudgeSoonResetsCadenceObserved() {
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        bot.startScheduledTask(60_000);

        bot.checkPrioritySpeed(); // 空图 → 未观察
        assertFalse(bot.isCadenceObserved());

        bot.nudgeSoon(400);
        assertTrue(bot.isCadenceObserved(), "nudge re-establishes the normal cadence for the next tick");
    }
}
