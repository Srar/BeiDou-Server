package org.gms.net.packet.trace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 单连接的环形包记录缓冲（gms 增强，崩溃诊断用）。
 * <p>
 * 容量满时淘汰最旧条目；同一 channel 的读写都由其 Netty eventLoop 串行执行，
 * 但导出/归档可能来自其他线程（崩溃报告线程、GM 命令线程），故全部操作加锁。
 * 锁粒度按条，开销为纳秒级，不影响收发主链路。
 */
public final class PacketTraceBuffer {

    private final int capacity;
    private final ArrayDeque<PacketTraceEntry> entries;
    private volatile long lastActivityMillis;

    public PacketTraceBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.entries = new ArrayDeque<>(Math.min(this.capacity, 64));
    }

    /** 追加一条记录；容量满时淘汰最旧。 */
    public synchronized void add(PacketTraceEntry entry) {
        if (entries.size() >= capacity) {
            entries.pollFirst();
        }
        entries.addLast(entry);
        lastActivityMillis = entry.timeMillis();
    }

    /** 当前缓冲条数。 */
    public synchronized int size() {
        return entries.size();
    }

    /** 最后一条记录的时间戳（无记录返回 0）。 */
    public long lastActivityMillis() {
        return lastActivityMillis;
    }

    /** 时间正序快照（导出用）。 */
    public synchronized List<PacketTraceEntry> snapshot() {
        return new ArrayList<>(entries);
    }
}
