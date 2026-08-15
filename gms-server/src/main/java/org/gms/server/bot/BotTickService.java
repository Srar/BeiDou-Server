package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.server.TimerManager;
import org.gms.util.I18nUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bot 中央宏脑 tick 轮（参考 SoloMapling 的 BotTickService 移植）。
 * <p>
 * 全服唯一的多 bot 调度器：一个共享 driver 任务每 100ms 扫描一次到期条目，
 * 经每 bot 的 CAS 闸门防重叠后把 tick 派发到虚拟线程执行。保留的调度语义：
 * <ul>
 *   <li>同一 bot 的两个 tick 永不并发（per-entry {@code ticking} CAS 闸门；
 *       completion 的 volatile 写保证 tick N 与 tick N+1 的 happens-before）</li>
 *   <li>稳态周期从 tick <b>完成</b>起算——慢 tick 不会在身后堆积</li>
 *   <li>reschedule/nudge 只挪该 bot 的下一次触发时刻，不取消也不重建任何 future；
 *       在途 tick 期间到达的请求经 {@code pendingDueMs} 交接，唤醒永不丢失</li>
 *   <li>register 是 keep-if-present，unregister 允许在途 tick 跑完</li>
 * </ul>
 * governor 在派发滞后时整体拉伸稳态周期（nudge 与显式 reschedule 永不被缩放）。
 * <p>
 * 生命周期：driver 挂在 {@link TimerManager} 上，而它会被 {@code Server.shutdownInternal}
 * shutdownNow 掉——因此 {@link #shutdown()} 必须在服务器关停路径调用以复位全部静态状态，
 * 否则 in-place 重启后轮盘永久停摆（driver 不复位、旧条目滞留）。
 */
@Slf4j
public final class BotTickService {

    private BotTickService() {
    }

    /**
     * driver 扫描周期。每个 pass 只是对 entries 做一次 O(n) 的廉价 volatile 读；
     * 100ms 保证 nudge 延迟远小于 150-700ms 的进图抖动窗口。
     */
    private static final long DRIVER_PERIOD_MS = 100;

    /** 调度参数的防御性上限（约 7 天）：防溢出/误配造成热循环或「292 年后」反转立即执行。 */
    private static final long MAX_DELAY_MS = 7L * 24 * 60 * 60 * 1000;

    private static final class Entry {
        final Runnable tick;
        volatile long periodMs;
        volatile long nextDueMs;
        /** 在途 tick 期间到达的 fire-at 请求（nudge/reschedule），0 = 无；由 completion 消费。 */
        volatile long pendingDueMs;
        /** 高优先 bot 免于 governor 拉伸——它们的节奏本身就是功能。 */
        volatile boolean noThrottle;
        final AtomicBoolean ticking = new AtomicBoolean(false);

        Entry(Runnable tick, long periodMs, long nextDueMs) {
            this.tick = tick;
            this.periodMs = periodMs;
            this.nextDueMs = nextDueMs;
        }

        /**
         * 请求在 at 时刻触发。先写 pendingDueMs，保证在途 tick 的 completion 一定能
         * 看到该请求。与 {@link #dispatch}/{@link #complete} 同一把锁，消除
         * 「读 nextDueMs 后、写 nextDueMs 前 driver 恰好泊车」导致的陈旧覆盖
         * （一次 nudge 重复触发两次 tick 的竞态）。
         */
        synchronized void requestFireAt(long at) {
            pendingDueMs = at;
            if (nextDueMs != Long.MAX_VALUE) { // 非 mid-tick：直接挪轮盘条目
                nextDueMs = at;
            }
        }

        /**
         * driver 的派发复合操作：CAS 进闸门 + 泊车 + 消费已满足的 pending 请求，
         * 一步完成且与 requestFireAt/complete 互斥。
         *
         * @return 本次派发的滞后（now - 原 due），负值 = 未派发（在途或已被他处结算）
         */
        synchronized long dispatch(long now) {
            if (!ticking.compareAndSet(false, true)) {
                return -1; // 仍在执行；它的 completion 会算下一次 due
            }
            long due = nextDueMs;
            nextDueMs = Long.MAX_VALUE; // tick 期间泊车
            if (pendingDueMs > 0 && now >= pendingDueMs) {
                pendingDueMs = 0; // 本次派发已满足 pending 请求
            }
            return now - due;
        }

        /**
         * tick 完成结算：周期从 completion 起算；消费在途期间到达的 pending 请求；
         * 先写 nextDueMs 再释放闸门（写-写顺序保证 completion 的 volatile 写对
         * 下一个 tick 有 happens-before）。
         */
        synchronized void complete(long now, double throttleFactor) {
            long due = now + (noThrottle ? periodMs : (long) (periodMs * throttleFactor));
            long pending = pendingDueMs;
            if (pending > 0) {
                pendingDueMs = 0;
                due = Math.min(due, Math.max(pending, now));
            }
            nextDueMs = due;
            ticking.set(false);
        }
    }

    private static final Map<Integer, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final AtomicBoolean DRIVER_STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean rejectionLogged = new AtomicBoolean(false);
    private static volatile ScheduledFuture<?> driverFuture;

    // ── Governor：自调节降载 ────────────────────────────────────────────────
    // 当轮盘持续落后（5s 窗口内平均派发滞后超过 LAG_HI）时，按递增系数拉伸所有
    // 稳态周期，让负载自己降下来；滞后恢复到 LAG_LO 以下后对称回落。nudge 与
    // 显式 reschedule 永不被缩放——提升响应性不可牺牲，只有后台节奏可降级。
    private static final long GOVERNOR_WINDOW_MS = 5000;
    private static final long LAG_HI_MS = 1000;
    private static final long LAG_LO_MS = 300;
    private static final double FACTOR_MAX = 4.0;
    private static final double FACTOR_STEP = 1.5;

    private static volatile double throttleFactor = 1.0;
    private static long governorWindowStartMs = 0;
    private static long windowLagSumMs = 0;
    private static long windowDispatches = 0;

    /**
     * 把 bot 的 tick 挂上轮盘。keep-if-present：已存活的注册条目保持不动。
     * 延迟参数做非负与上限钳制（防溢出热循环）。
     */
    public static void register(int botId, Runnable tick, long initialDelayMs, long periodMs) {
        ensureDriver();
        ENTRIES.putIfAbsent(botId, new Entry(tick, clampDelay(periodMs),
                System.currentTimeMillis() + clampDelay(initialDelayMs)));
    }

    public static void unregister(int botId) {
        ENTRIES.remove(botId);
    }

    public static boolean isRegistered(int botId) {
        return ENTRIES.containsKey(botId);
    }

    /**
     * 让某 bot 的稳态节奏免于 governor 拉伸。当前无生产调用者，为后续
     * 需要稳定节奏的类型（如跟随型 bot）预留。
     */
    public static void setNoThrottle(int botId, boolean on) {
        Entry e = ENTRIES.get(botId);
        if (e != null) {
            e.noThrottle = on;
        }
    }

    public static int size() {
        return ENTRIES.size();
    }

    /**
     * 服务器关停路径调用：取消 driver、清空全部条目并复位 driver 与 governor 状态，
     * 使 in-place 重启（TimerManager 已被 shutdownNow）后轮盘可以重新挂载。
     * 在途 tick 不会被中断（与 unregister 语义一致——条目移除，允许跑完）。
     */
    public static void shutdown() {
        ScheduledFuture<?> future = driverFuture;
        if (future != null) {
            future.cancel(false);
            driverFuture = null;
        }
        ENTRIES.clear();
        DRIVER_STARTED.set(false);
        rejectionLogged.set(false);
        throttleFactor = 1.0;
        governorWindowStartMs = 0;
        windowLagSumMs = 0;
        windowDispatches = 0;
        BotExecutors.resetForShutdown();
    }

    /** 测试访问器：某 bot 的下一次应触发时刻（未注册返回 null）。 */
    static Long nextDueMs(int botId) {
        Entry e = ENTRIES.get(botId);
        return e == null ? null : e.nextDueMs;
    }

    /**
     * 改变 bot 的节奏：下一次在 now + periodMs 触发，之后每次 completion 后间隔 periodMs。
     */
    public static void reschedule(int botId, long periodMs) {
        Entry e = ENTRIES.get(botId);
        if (e == null) {
            return;
        }
        e.periodMs = clampDelay(periodMs);
        e.requestFireAt(System.currentTimeMillis() + e.periodMs);
    }

    /**
     * 把下一次触发拉到 now + initialDelayMs，并设置稳态周期。
     */
    public static void nudge(int botId, long initialDelayMs, long periodMs) {
        Entry e = ENTRIES.get(botId);
        if (e == null) {
            return;
        }
        e.periodMs = clampDelay(periodMs);
        e.requestFireAt(System.currentTimeMillis() + clampDelay(initialDelayMs));
    }

    private static long clampDelay(long delayMs) {
        if (delayMs < 0) {
            return 0;
        }
        return Math.min(delayMs, MAX_DELAY_MS);
    }

    private static void ensureDriver() {
        if (!DRIVER_STARTED.compareAndSet(false, true)) {
            return;
        }
        BotExecutors.ensureStarted();
        driverFuture = TimerManager.getInstance().registerWithFixedDelay(
                BotTickService::safeDrive, DRIVER_PERIOD_MS, DRIVER_PERIOD_MS);
    }

    private static void safeDrive() {
        try {
            drive();
        } catch (Throwable t) {
            // driver 绝不能死；跳过本 pass，下一个 pass 补上
            log.error(I18nUtil.getLogMessage("BotTickService.driver.error"), t);
        }
    }

    private static void drive() {
        long now = System.currentTimeMillis();
        long lagSum = 0;
        int dispatched = 0;
        for (Entry e : ENTRIES.values()) {
            if (now < e.nextDueMs) {
                continue;
            }
            long lag = e.dispatch(now);
            if (lag < 0) {
                continue;
            }
            lagSum += Math.max(0, lag);
            dispatched++;
            try {
                BotExecutors.runAsync(() -> runTick(e));
            } catch (RejectedExecutionException ex) {
                // 执行器已关停：回滚派发状态，避免条目永远卡在 ticking=true/nextDueMs=MAX。
                // gms 增强：执行器关停后 driver 每 100ms 都会全部拒绝，形成刷屏风暴并拖慢停机。
                // 首次拒绝即停轮（shutdown() 幂等），并只告警一次。
                e.complete(now, throttleFactor);
                if (rejectionLogged.compareAndSet(false, true)) {
                    log.warn(I18nUtil.getLogMessage("BotTickService.dispatch.rejected"), ex);
                }
                shutdown(); // 停 driver + 清空轮盘（虚拟线程执行器已关，tick 不可能再执行）
                return;
            }
        }
        governorTick(now, lagSum, dispatched);
    }

    private static void governorTick(long now, long lagSumMs, int dispatches) {
        windowLagSumMs += lagSumMs;
        windowDispatches += dispatches;
        if (governorWindowStartMs == 0) {
            governorWindowStartMs = now;
            return;
        }
        if (now - governorWindowStartMs < GOVERNOR_WINDOW_MS) {
            return;
        }
        long avg = windowDispatches > 0 ? windowLagSumMs / windowDispatches : 0;
        windowLagSumMs = 0;
        windowDispatches = 0;
        governorWindowStartMs = now;
        if (avg > LAG_HI_MS && throttleFactor < FACTOR_MAX) {
            throttleFactor = Math.min(FACTOR_MAX, throttleFactor * FACTOR_STEP);
            log.warn(I18nUtil.getLogMessage("BotTickService.governor.stretch", String.valueOf(avg),
                    String.format("%.2f", throttleFactor)));
        } else if (avg < LAG_LO_MS && throttleFactor > 1.0) {
            throttleFactor = Math.max(1.0, throttleFactor / FACTOR_STEP);
            log.info(I18nUtil.getLogMessage("BotTickService.governor.recover", String.valueOf(avg),
                    String.format("%.2f", throttleFactor)));
        }
    }

    private static void runTick(Entry e) {
        try {
            e.tick.run();
        } catch (Throwable t) {
            // 单个 bot 的 tick 异常绝不能杀死它的调度（tickRunnable 自身也有兜底）
            log.warn(I18nUtil.getLogMessage("BotTickService.tick.error"), t);
        } finally {
            e.complete(System.currentTimeMillis(), throttleFactor);
        }
    }
}
