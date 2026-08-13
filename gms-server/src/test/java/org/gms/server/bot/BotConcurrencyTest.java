package org.gms.server.bot;

import org.gms.server.bot.event.BotEventBuffer;
import org.gms.server.bot.event.BotEventStore;
import org.gms.server.bot.event.GameEvent;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发语义（审查复盘补盲）：单测只覆盖单个操作与 tick 轮并发，本类锁定其余共享结构——
 * 每 bot 事件缓冲的并发 add、全局注册表的并发 add/remove、全局事件历史环形缓冲的
 * 并发写入、tick 轮的多线程并发 register/unregister。
 * <p>
 * 断言只判性质（无异常、最终态合法、能消费到数据），不判精确值；时间预算宽松，
 * 全部等待设上限防止死锁悬挂 CI。
 */
class BotConcurrencyTest {

    private static final int BASE_ID = 960_500_000;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(BASE_ID);
    private static final int MAP_ID = 100000000;

    /** 本用例注册过的 id：@AfterEach 统一从轮盘与注册表清理。 */
    private final List<Integer> usedIds = new ArrayList<>();

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @AfterEach
    void cleanup() {
        for (int id : usedIds) {
            BotTickService.unregister(id);
            BotStorage.removeActiveBot(id);
        }
        usedIds.clear();
    }

    @Test
    void concurrentBufferAddNeverThrows() throws Exception {
        BotEventBuffer buffer = new BotEventBuffer(100);
        int threads = 4;
        int addsPerThread = 500;

        List<Future<?>> futures = runConcurrently(threads, tid -> {
            for (int i = 0; i < addsPerThread; i++) {
                buffer.add(GameEvent.chat(0, 1, MAP_ID, tid * 1000 + i, "m" + i));
            }
        });
        awaitAll(futures);

        // 容量是「软上限」（check-then-act 非原子），最终规模不得越过上限（性质断言）
        assertTrue(buffer.size() <= 100, "final size must not exceed the soft capacity, got " + buffer.size());

        int polled = 0;
        while (buffer.poll() != null) {
            polled++;
        }
        assertTrue(polled >= 1, "concurrent adds must leave pollable events behind");
    }

    @Test
    void concurrentStorageAddRemoveIsConsistent() throws Exception {
        int threads = 4;
        int idsPerThread = 10;
        int rounds = 50;

        int[][] ids = new int[threads][idsPerThread];
        BotSM[][] mocks = new BotSM[threads][idsPerThread];
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < idsPerThread; i++) {
                ids[t][i] = NEXT_ID.incrementAndGet();
                mocks[t][i] = Mockito.mock(BotSM.class);
                usedIds.add(ids[t][i]);
            }
        }

        List<Future<?>> futures = runConcurrently(threads, tid -> {
            for (int round = 0; round < rounds; round++) {
                for (int i = 0; i < idsPerThread; i++) {
                    BotStorage.addActiveBot(ids[tid][i], mocks[tid][i]);
                    BotStorage.removeActiveBot(ids[tid][i]);
                }
            }
        });
        awaitAll(futures);

        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < idsPerThread; i++) {
                BotSM value = BotStorage.getBotById(ids[t][i]);
                boolean loggedIn = BotStorage.botLoggedIn(ids[t][i]);
                // 最终态只有两种合法值：不存在（null）或仍是本线程写入的实例
                assertTrue(value == null || value == mocks[t][i],
                        "id " + ids[t][i] + " holds an alien instance");
                // 注册表与 loggedIn 视图必须一致（同一 CHM 的原子读）
                assertEquals(value != null, loggedIn,
                        "registry and botLoggedIn must agree for id " + ids[t][i]);
            }
        }
    }

    @Test
    void concurrentEventStoreAddIsSafe() throws Exception {
        BotEventStore store = BotEventStore.getInstance();
        int threads = 4;
        int addsPerThread = 1000;

        List<Future<?>> futures = runConcurrently(threads, tid -> {
            for (int i = 0; i < addsPerThread; i++) {
                store.add(GameEvent.chat(0, 1, MAP_ID, tid * 1000 + i, "m"));
            }
        });
        awaitAll(futures);

        GameEvent[] recent = store.recent(100);
        assertEquals(100, recent.length, "recent(100) must always return the requested length");

        int nonNull = 0;
        for (GameEvent event : recent) {
            if (event != null) {
                nonNull++;
            }
        }
        assertTrue(nonNull >= 1, "concurrent adds must land in the ring");
    }

    @Test
    void concurrentRegisterUnregisterOnWheel() throws Exception {
        int threads = 8;
        int botsPerThread = 5;

        int[][] ids = new int[threads][botsPerThread];
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < botsPerThread; i++) {
                ids[t][i] = NEXT_ID.incrementAndGet();
                usedIds.add(ids[t][i]);
            }
        }

        CountDownLatch allRegistered = new CountDownLatch(threads);
        List<Future<?>> futures = runConcurrently(threads, tid -> {
            for (int i = 0; i < botsPerThread; i++) {
                BotTickService.register(ids[tid][i], () -> {
                }, 0, 100);
            }
            allRegistered.countDown();
        });
        assertTrue(allRegistered.await(10, TimeUnit.SECONDS), "registration threads did not finish in time");
        awaitAll(futures);

        // 全部注册完成后逐一 unregister：无异常且条目全部清空
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < botsPerThread; i++) {
                BotTickService.unregister(ids[t][i]);
            }
        }
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < botsPerThread; i++) {
                assertFalse(BotTickService.isRegistered(ids[t][i]),
                        "id " + ids[t][i] + " must be unregistered after the concurrent storm");
            }
        }
    }

    /** 派发 threads 个任务到固定线程池；任务抛出的任何异常由 awaitAll 重新抛出。 */
    private List<Future<?>> runConcurrently(int threads, IntConsumer task) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> task.accept(tid)));
        }
        pool.shutdown();
        return futures;
    }

    private static void awaitAll(List<Future<?>> futures) throws Exception {
        for (Future<?> future : futures) {
            future.get(120, TimeUnit.SECONDS); // 任务异常在此以 ExecutionException 浮出
        }
    }
}
