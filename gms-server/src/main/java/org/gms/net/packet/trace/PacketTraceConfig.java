package org.gms.net.packet.trace;

import org.gms.config.GameConfig;

/**
 * 包记录开关的快照缓存（gms 增强，崩溃诊断用）。
 * <p>
 * 收发路径每包都要判断是否记录，直接逐包走 GameConfig（JSON 遍历）太贵；
 * 这里做 1 秒粒度的快照缓存，GameConfig 热更新最多延迟 1 秒生效，
 * 对「崩溃现场捕捉」无影响。
 */
public final class PacketTraceConfig {

    private static volatile boolean enabled;
    private static volatile int capacity = 2048;
    private static volatile boolean includeMove;
    private static volatile long lastRefreshMillis;

    private PacketTraceConfig() {
    }

    /** 是否开启包记录。 */
    public static boolean enabled() {
        refreshIfStale();
        return enabled;
    }

    /** 每连接环形缓冲容量。 */
    public static int capacity() {
        refreshIfStale();
        return capacity;
    }

    /** 是否记录高频移动包（默认 false：过滤，防缓冲被移动广播刷掉）。 */
    public static boolean includeMove() {
        refreshIfStale();
        return includeMove;
    }

    /** 立即重读 GameConfig（GM 命令/测试用）。 */
    public static void refreshNow() {
        lastRefreshMillis = 0;
        refreshIfStale();
    }

    private static void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMillis < 1000) {
            return;
        }
        synchronized (PacketTraceConfig.class) {
            if (now - lastRefreshMillis < 1000) {
                return;
            }
            lastRefreshMillis = now;
            try {
                enabled = GameConfig.getServerBoolean("packet_trace_enabled");
                capacity = GameConfig.getServerInt("packet_trace_capacity");
                if (capacity <= 0) {
                    capacity = 2048;
                }
                includeMove = GameConfig.getServerBoolean("packet_trace_include_move");
            } catch (RuntimeException ignored) {
                // GameConfig 未初始化（单测环境）：保持上次快照
            }
        }
    }

    /** 测试注入：直接覆盖快照（绕过 GameConfig）。 */
    static void overrideForTest(boolean enabledValue, int capacityValue, boolean includeMoveValue) {
        enabled = enabledValue;
        capacity = Math.max(1, capacityValue);
        includeMove = includeMoveValue;
        lastRefreshMillis = System.currentTimeMillis();
    }
}
