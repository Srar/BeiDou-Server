package org.gms.server.bot;

import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BotTiming 三件工具：after 延迟单次执行、chain 有序编排（pause 时序）、
 * stopUnless 门控中止、步骤异常终止整链、afterRandom 延迟区间。
 */
class BotTimingTest {

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @Test
    void afterFiresOnceAfterDelay() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean firedTwice = new AtomicBoolean(false);
        AtomicLong firedAt = new AtomicLong();

        long start = System.currentTimeMillis();
        BotTiming.after(200, () -> {
            if (firedAt.get() != 0) {
                firedTwice.set(true);
            }
            firedAt.set(System.currentTimeMillis());
            latch.countDown();
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS), "after() action never ran");
        assertTrue(firedAt.get() - start >= 150, "action ran too early: " + (firedAt.get() - start) + "ms");
        assertFalse(firedTwice.get(), "after() action ran more than once");
    }

    @Test
    void chainRunsStepsInOrderWithPauses() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        BotTiming.chain()
                .run(() -> order.add("a"))
                .pause(100)
                .run(() -> order.add("b"))
                .pauseRandom(50, 80)
                .run(() -> {
                    order.add("c");
                    done.countDown();
                })
                .start();

        assertTrue(done.await(3, TimeUnit.SECONDS), "chain never finished");
        assertEquals(List.of("a", "b", "c"), order, "chain steps out of order");
    }

    @Test
    void chainPauseActuallyDelays() throws Exception {
        List<Long> marks = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        BotTiming.chain()
                .run(() -> marks.add(System.currentTimeMillis()))
                .pause(250)
                .run(() -> {
                    marks.add(System.currentTimeMillis());
                    done.countDown();
                })
                .start();

        assertTrue(done.await(3, TimeUnit.SECONDS), "chain never finished");
        assertTrue(marks.size() == 2, "expected 2 marks, got " + marks.size());
        assertTrue(marks.get(1) - marks.get(0) >= 200,
                "pause didn't actually delay: gap=" + (marks.get(1) - marks.get(0)) + "ms");
    }

    @Test
    void stopUnlessHaltsChainBeforeLaterSteps() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicBoolean keepGoing = new AtomicBoolean(true);

        BotTiming.chain()
                .run(() -> order.add("first"))
                .stopUnless(keepGoing::get)
                .pause(300)
                .run(() -> order.add("second"))
                .run(() -> order.add("third"))
                .start();

        Thread.sleep(100);
        keepGoing.set(false); // pause 期间翻转：后续步骤执行前检查，静默终止

        Thread.sleep(600); // 链在 t≈300 的门控检查后终止
        assertEquals(List.of("first"), order, "chain kept running after gate went false");
    }

    @Test
    void thrownStepEndsChain() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch thrown = new CountDownLatch(1);

        BotTiming.chain()
                .run(() -> order.add("first"))
                .run(() -> {
                    thrown.countDown(); // 握手：确认 throwing 步骤确实执行了
                    throw new IllegalStateException("boom");
                })
                .run(() -> order.add("never"))
                .start();

        assertTrue(thrown.await(2, TimeUnit.SECONDS), "throwing step never ran");
        Thread.sleep(150); // 给后续步骤（若错误地继续执行）留出暴露窗口
        assertEquals(List.of("first"), order, "steps after a throwing step still ran");
    }

    @Test
    void afterActionExceptionDoesNotKillScheduler() throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);

        BotTiming.after(50, () -> {
            firstDone.countDown();
            throw new IllegalStateException("boom");
        });
        BotTiming.after(50, secondDone::countDown);

        // 前一个动作抛异常被 safely 吞掉，不影响同池的其他定时任务
        assertTrue(firstDone.await(2, TimeUnit.SECONDS));
        assertTrue(secondDone.await(2, TimeUnit.SECONDS), "scheduler must survive a failing action");
    }

    @Test
    void afterRandomDelayWithinBounds() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        long start = System.currentTimeMillis();

        BotTiming.afterRandom(150, 250, latch::countDown);

        assertTrue(latch.await(3, TimeUnit.SECONDS), "afterRandom action never ran");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 120 && elapsed < 1500,
                "afterRandom delay out of plausible bounds: " + elapsed + "ms");
    }
}
