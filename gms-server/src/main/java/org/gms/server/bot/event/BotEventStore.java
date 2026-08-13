package org.gms.server.bot.event;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局事件历史环形缓冲（容量 5000）：发布过的最近 N 个事件，供调试/审计回看。
 */
public final class BotEventStore {

    private static final int CAPACITY = 5000;

    private static final BotEventStore INSTANCE = new BotEventStore();

    private final GameEvent[] ring = new GameEvent[CAPACITY];
    private final AtomicInteger cursor = new AtomicInteger(0);

    private BotEventStore() {
    }

    public static BotEventStore getInstance() {
        return INSTANCE;
    }

    /** 写入一个事件到环形缓冲，返回其序号（全局递增）。 */
    public long add(GameEvent event) {
        long seq = cursor.getAndIncrement();
        // floorMod：AtomicInteger 溢出（seq 变负）后索引仍恒非负——否则负索引直接 AIOOBE
        ring[Math.floorMod(seq, CAPACITY)] = event;
        return seq;
    }

    /**
     * 最近的 N 个事件（时间倒序）；N 超过容量或历史时按实际返回。
     * 尚未写入的槽位为 null（例如启动后事件数不足 N）。
     */
    public GameEvent[] recent(int n) {
        int count = Math.min(n, CAPACITY);
        GameEvent[] result = new GameEvent[count];
        long tail = cursor.get();
        for (int i = 0; i < count; i++) {
            result[i] = ring[Math.floorMod(tail - 1 - i, CAPACITY)];
        }
        return result;
    }
}
