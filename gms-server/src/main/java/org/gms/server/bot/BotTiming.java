package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.util.I18nUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * 「稍等一拍再做事」的唯一工具箱（参考 SoloMapling 的 BotTiming 移植）——
 * 在绝不持有 OS 线程、绝不把 tick 冻在 sleep 里的前提下编排 bot 的延迟行为。
 * <p>
 * 三件工具，按场景选择：
 * <ol>
 *   <li>暂停整个 FSM 到下一拍 → {@link BotSM#waitFor(long)}（不在这里）。延迟本身就是
 *       bot 的下一个动作间隔时使用：waitFor(...) 后置状态、从 tick return。</li>
 *   <li>稍后做一件延迟副作用、不暂停任何东西 → {@link #after(long, Runnable)}。
 *       定时挂在共享调度池，动作体跑虚拟线程，慢动作堵不住定时器池。</li>
 *   <li>一串编排节拍 → {@link #chain()}。整链按序跑在单虚拟线程上（pause 只 park
 *       该虚拟线程）。链是 fire-and-forget：境况已变就应终止的链必须显式
 *       {@code stopUnless(...)} 门控。</li>
 * </ol>
 * 经验法则：节奏 bot 自己的下一步 = waitFor；其余所有原本用 Thread.sleep 的地方 = after 或 chain。
 */
@Slf4j
public final class BotTiming {

    private BotTiming() {
    }

    /** 一次延迟动作。定时在共享调度池，动作体在虚拟线程。 */
    public static void after(long delayMs, Runnable action) {
        BotExecutors.schedule(() -> BotExecutors.runAsync(() -> safely(action)), delayMs);
    }

    /** 同上，延迟为 [loMs, hiMs] 闭区间均匀随机——抖动读起来更像人。 */
    public static void afterRandom(long loMs, long hiMs, Runnable action) {
        after(randomBetween(loMs, hiMs), action);
    }

    public static Chain chain() {
        return new Chain();
    }

    /** 有序步骤跑在单虚拟线程上；pause 只 park 该线程。 */
    public static final class Chain {
        private final List<Runnable> steps = new ArrayList<>();
        private BooleanSupplier keepGoing = null;

        private Chain() {
        }

        public Chain run(Runnable action) {
            steps.add(action);
            return this;
        }

        public Chain pause(long ms) {
            long delay = Math.max(0, ms);
            steps.add(() -> parkFor(delay));
            return this;
        }

        public Chain pauseRandom(long loMs, long hiMs) {
            steps.add(() -> parkFor(randomBetween(loMs, hiMs)));
            return this;
        }

        /**
         * 在此调用之后的每个步骤执行前检查；返回 false 则整链静默终止。
         * 想在链上装全程门控的话把它放在第一位。
         */
        public Chain stopUnless(BooleanSupplier condition) {
            this.keepGoing = condition;
            return this;
        }

        /** 在虚拟线程上启动链并立即返回。 */
        public void start() {
            List<Runnable> script = new ArrayList<>(steps);
            BooleanSupplier gate = keepGoing;
            BotExecutors.runAsync(() -> {
                for (Runnable step : script) {
                    if (gate != null && !gate.getAsBoolean()) {
                        return;
                    }
                    if (!safely(step)) {
                        return; // 某步抛异常即终止整链；后续步骤常依赖前一步
                    }
                }
            });
        }
    }

    private static void parkFor(long ms) {
        try {
            Thread.sleep(ms); // 只 park 链所在的虚拟线程——不占 OS 线程
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e); // 经 safely() 终止整链
        }
    }

    private static long randomBetween(long loMs, long hiMs) {
        long lo = Math.max(0, Math.min(loMs, hiMs));
        long hi = Math.max(loMs, hiMs);
        if (hi == Long.MAX_VALUE) {
            hi = Long.MAX_VALUE - 1; // 防 hi - lo + 1 溢出为负导致 nextLong 抛 IAE
        }
        return lo + (hi > lo ? ThreadLocalRandom.current().nextLong(hi - lo + 1) : 0);
    }

    private static boolean safely(Runnable r) {
        try {
            r.run();
            return true;
        } catch (Throwable t) {
            // 异常处理器里不再拼接异常文案（MessageFormat 风险字符会在此处二次抛异常）；
            // throwable 作为日志参数传递，保留完整堆栈
            log.warn(I18nUtil.getLogMessage("BotTiming.step.failed"), t);
            return false;
        }
    }
}
