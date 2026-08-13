package org.gms.server.bot;

import org.gms.server.ThreadManager;
import org.gms.server.TimerManager;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bot 框架共用的执行器引导（TimerManager 共享调度池 + ThreadManager 虚拟线程池）。
 * 两个池都由 Server 启动流程正常拉起；这里做幂等兜底，保证 bot 代码在
 * 单元测试或任何先于 Server.init 的路径里也安全可用。
 */
public final class BotExecutors {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private BotExecutors() {
    }

    public static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        TimerManager.getInstance().start();
        ThreadManager.getInstance().start();
    }

    /** 延迟调度：定时在共享调度池。 */
    public static void schedule(Runnable task, long delayMs) {
        ensureStarted();
        // 上限钳制：极大延迟经 ScheduledThreadPoolExecutor 的 toNanos 换算会溢出成负
        // 时间戳，任务反而立即执行——「292 年后」语义反转。钳到 7 天上限。
        TimerManager.getInstance().schedule(task, Math.max(0, Math.min(delayMs, MAX_SCHEDULE_DELAY_MS)));
    }

    private static final long MAX_SCHEDULE_DELAY_MS = 7L * 24 * 60 * 60 * 1000;

    /** 立即异步执行：跑虚拟线程，慢任务不会堵调度池。 */
    public static void runAsync(Runnable task) {
        ensureStarted();
        ThreadManager.getInstance().newTask(task);
    }

    /** 服务器关停路径调用：允许下一次 ensureStarted 重新引导执行器。 */
    public static void resetForShutdown() {
        STARTED.set(false);
    }
}
