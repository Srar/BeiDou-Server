package org.gms.net.packet.trace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环形缓冲测试：容量淘汰最旧、快照时间正序、活跃时间戳。
 */
class PacketTraceBufferTest {

    private static PacketTraceEntry entry(int seq) {
        return new PacketTraceEntry(1000L + seq, PacketTraceEntry.DIR_SEND,
                (short) seq, "PKT_" + seq, 2, String.format("%02X %02X", seq, seq));
    }

    @Test
    void evictsOldestBeyondCapacity() {
        PacketTraceBuffer buffer = new PacketTraceBuffer(3);
        buffer.add(entry(1));
        buffer.add(entry(2));
        buffer.add(entry(3));
        buffer.add(entry(4));

        List<PacketTraceEntry> snapshot = buffer.snapshot();
        assertEquals(3, snapshot.size());
        assertEquals(2, snapshot.get(0).opcode(), "最旧的 1 应被淘汰");
        assertEquals(4, snapshot.get(2).opcode());
    }

    @Test
    void snapshotKeepsInsertionOrder() {
        PacketTraceBuffer buffer = new PacketTraceBuffer(5);
        buffer.add(entry(1));
        buffer.add(entry(3));
        buffer.add(entry(2));
        List<PacketTraceEntry> snapshot = buffer.snapshot();
        assertEquals(1, snapshot.get(0).opcode());
        assertEquals(3, snapshot.get(1).opcode());
        assertEquals(2, snapshot.get(2).opcode());
    }

    @Test
    void tracksLastActivityMillis() {
        PacketTraceBuffer buffer = new PacketTraceBuffer(5);
        assertEquals(0, buffer.lastActivityMillis());
        buffer.add(entry(7));
        assertEquals(1007L, buffer.lastActivityMillis());
    }

    @Test
    void clampsCapacityToAtLeastOne() {
        PacketTraceBuffer buffer = new PacketTraceBuffer(0);
        buffer.add(entry(1));
        buffer.add(entry(2));
        assertEquals(1, buffer.size());
        assertTrue(buffer.snapshot().get(0).opcode() == 2, "容量下限为 1，只保留最新");
    }

    @Test
    void concurrentAddsDoNotLoseOrCorrupt() throws Exception {
        PacketTraceBuffer buffer = new PacketTraceBuffer(64);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            final int base = t * 100;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    buffer.add(entry(base + i));
                }
            });
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(64, buffer.size());
        List<PacketTraceEntry> snapshot = buffer.snapshot();
        for (PacketTraceEntry entry : snapshot) {
            assertTrue(entry.opcode() > 0 && entry.opcodeName() != null, "并发追加无损坏");
        }
    }
}
