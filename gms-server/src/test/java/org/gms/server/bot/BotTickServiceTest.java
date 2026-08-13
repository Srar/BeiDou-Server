package org.gms.server.bot;

import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中央 tick 轮的调度语义测试（移植自 SoloMapling 的 BotTickServiceTest）：
 * 每个 bot 都依赖的不变量——tick 永不重叠、稳态周期从 completion 起算、
 * 在途 nudge 交接不丢唤醒、nudge 拉前空闲 bot、register keep-if-present、
 * unregister 停跳、tick 异常不杀调度。
 * <p>
 * 测试跑在真实 100ms driver 上，时序断言只用宽松边界（顺序与下界，不判精确时刻）。
 */
class BotTickServiceTest {

    private static final int BASE_ID = 9_900_000;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(BASE_ID);

    private final int botId = NEXT_ID.incrementAndGet();

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @AfterEach
    void cleanup() {
        BotTickService.unregister(botId);
    }

    @Test
    void ticksNeverOverlapForOneBot() throws Exception {
        AtomicInteger inTick = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger ticks = new AtomicInteger();

        // 周期远小于 tick 自身耗时：没有 CAS 闸门时 driver 会趁第一次还在 sleep 再派发一次
        BotTickService.register(botId, () -> {
            int now = inTick.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            ticks.incrementAndGet();
            sleep(300);
            inTick.decrementAndGet();
        }, 0, 50);
        BotTickService.setNoThrottle(botId, true); // 免疫 governor 拉伸，防 CI 卡顿静态污染

        sleep(1500);

        assertTrue(ticks.get() >= 2, "expected repeated ticks, got " + ticks.get());
        assertEquals(1, maxConcurrent.get(), "two ticks ran concurrently for one bot");
    }

    @Test
    void periodIsMeasuredFromTickCompletion() throws Exception {
        List<Long> startTimes = new CopyOnWriteArrayList<>();

        // 300ms 工作 + 300ms 周期：completion 起算的语义下相邻 START 至少约 600ms；
        // 错误实现（fixed-rate）会每 300ms 触发一次
        BotTickService.register(botId, () -> {
            startTimes.add(System.currentTimeMillis());
            sleep(300);
        }, 0, 300);
        BotTickService.setNoThrottle(botId, true); // 免疫 governor 拉伸，防 CI 卡顿静态污染

        sleep(2500);
        BotTickService.unregister(botId);

        assertTrue(startTimes.size() >= 3, "expected at least 3 ticks, got " + startTimes.size());
        for (int i = 1; i < startTimes.size(); i++) {
            long gap = startTimes.get(i) - startTimes.get(i - 1);
            assertTrue(gap >= 550, "tick " + i + " started " + gap
                    + "ms after the previous start; completion-measured delay requires >= ~600ms");
        }
    }

    @Test
    void nudgeDuringInFlightTickIsConsumedAtCompletion() throws Exception {
        CountDownLatch firstTickRunning = new CountDownLatch(1);
        CountDownLatch secondTick = new CountDownLatch(2);

        // 稳态周期一分钟——只有 pending 交接能产生测试窗口内的第二次 tick
        BotTickService.register(botId, () -> {
            firstTickRunning.countDown();
            secondTick.countDown();
            sleep(400);
        }, 0, 60_000);

        assertTrue(firstTickRunning.await(3, TimeUnit.SECONDS), "first tick never ran");
        BotTickService.nudge(botId, 0, 60_000); // 落在 tick 在途期间

        assertTrue(secondTick.await(3, TimeUnit.SECONDS),
                "nudge sent during an in-flight tick was lost; bot stayed parked on its 60s period");
    }

    @Test
    void nudgePullsForwardAnIdleBot() throws Exception {
        CountDownLatch ticked = new CountDownLatch(1);

        BotTickService.register(botId, ticked::countDown, 60_000, 60_000);
        // 不被扰动时首 tick 在一分钟外
        assertFalse(ticked.await(300, TimeUnit.MILLISECONDS), "tick fired before the nudge");

        BotTickService.nudge(botId, 100, 60_000);
        assertTrue(ticked.await(3, TimeUnit.SECONDS), "nudge did not pull the idle bot forward");
    }

    @Test
    void registerIsKeepIfPresent() throws Exception {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();

        BotTickService.register(botId, first::incrementAndGet, 0, 150);
        BotTickService.setNoThrottle(botId, true); // 免疫 governor 拉伸，防 CI 卡顿静态污染
        sleep(400);
        assertTrue(first.get() >= 1, "original tick never ran");

        // 旧 startScheduledTask 契约：活着的注册条目不动它
        BotTickService.register(botId, second::incrementAndGet, 0, 150);
        sleep(600);

        assertEquals(0, second.get(), "re-register replaced a live entry");
        assertTrue(first.get() >= 2, "original tick stopped after re-register");
    }

    @Test
    void unregisterStopsTicking() throws Exception {
        AtomicInteger ticks = new AtomicInteger();

        BotTickService.register(botId, ticks::incrementAndGet, 0, 100);
        sleep(500);
        assertTrue(ticks.get() >= 1, "bot never ticked");

        BotTickService.unregister(botId);
        assertFalse(BotTickService.isRegistered(botId));
        sleep(250); // 等在途 tick 排干
        int settled = ticks.get();
        sleep(600);
        assertEquals(settled, ticks.get(), "bot kept ticking after unregister");
    }

    @Test
    void tickExceptionDoesNotKillSchedule() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        // tick 每次都抛异常：轮盘必须吞掉并继续调度（completion 仍会结算下一次 due）
        BotTickService.register(botId, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        }, 0, 100);
        BotTickService.setNoThrottle(botId, true); // 免疫 governor 拉伸，防 CI 卡顿静态污染

        sleep(800);
        assertTrue(attempts.get() >= 3, "tick stopped being dispatched after exceptions, got " + attempts.get());
    }

    @Test
    void reschedulePullsForwardAndChangesCadence() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        AtomicInteger ticks = new AtomicInteger();

        // 稳态一分钟：只有 reschedule 能产生窗口内的第二次 tick。
        // 第一个 tick 只放行 first；第二个及以后的 tick 才放行 second
        BotTickService.register(botId, () -> {
            int n = ticks.incrementAndGet();
            if (n == 1) {
                first.countDown();
            } else {
                second.countDown();
            }
        }, 0, 60_000);
        BotTickService.setNoThrottle(botId, true); // 免疫 governor 拉伸，防 CI 卡顿静态污染

        assertTrue(first.await(3, TimeUnit.SECONDS), "first tick never ran");

        BotTickService.reschedule(botId, 150);
        assertTrue(second.await(3, TimeUnit.SECONDS), "reschedule did not pull the bot forward");
        assertTrue(ticks.get() >= 2, "expected at least 2 ticks, got " + ticks.get());
    }

    @Test
    void negativeDelaysAreClampedToImmediate() throws Exception {
        CountDownLatch ticked = new CountDownLatch(1);

        // 负周期/负初始延迟：钳制为 0（立即触发），不得溢出成热循环或死等
        BotTickService.register(botId, ticked::countDown, -50, -10);

        assertTrue(ticked.await(3, TimeUnit.SECONDS), "negative delays must be clamped to immediate fire");
    }

    @Test
    void shutdownResetsWheelForRestart() throws Exception {
        CountDownLatch ticked = new CountDownLatch(1);
        int restartId = botId;

        BotTickService.register(restartId, ticked::countDown, 0, 100);
        BotTickService.setNoThrottle(restartId, true); // 免疫 governor 拉伸，防 CI 卡顿静态污染
        assertTrue(ticked.await(3, TimeUnit.SECONDS), "first registration must tick");

        // 模拟服务器 in-place 重启：关停复位后，同一 id 可以重新注册并再次 tick
        BotTickService.shutdown();
        assertFalse(BotTickService.isRegistered(restartId));

        CountDownLatch second = new CountDownLatch(1);
        BotTickService.register(restartId, second::countDown, 0, 100);
        BotTickService.setNoThrottle(restartId, true); // shutdown 重建条目：新条目同样免疫拉伸
        assertTrue(second.await(3, TimeUnit.SECONDS), "wheel must work again after shutdown reset");
        BotTickService.unregister(restartId);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
