package org.gms.server.bot.freemarket;

import org.gms.util.Randomizer;

import java.util.List;
import java.util.function.Supplier;

/**
 * 随机与等待工具（逐行移植自 SoloMapling server.SoloMaplingUtilities 中
 * 商业/引导类 Bot 用到的子集）。
 */
public final class BotRand {

    private BotRand() {
    }

    /**
     * A 1-in-N dice roll, NOT a percent chance: rollChanceInverse(20) is true one time in 20.
     */
    public static boolean rollChanceInverse(int outOf) {
        return Randomizer.nextInt(outOf) == 0;
    }

    public static <T> T getRandomElement(List<T> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(Randomizer.nextInt(list.size()));
    }

    public static int generateRandomNumber(int x, int y) {
        if (x > y) {
            throw new IllegalArgumentException("x must be less than or equal to y.");
        }
        return Randomizer.rand(x, y);
    }

    /** 等待条件成立（默认 15 次 × 2s）。刻意同步阻塞，等价源 SoloMaplingUtilities.waitForCondition。 */
    public static boolean waitForCondition(Supplier<Boolean> condition) {
        return waitForCondition(condition, 15, 2000);
    }

    public static boolean waitForCondition(Supplier<Boolean> condition, int maxAttempts, long delayMs) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (condition.get()) {
                return true;
            }
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
