package org.gms.net.packet.trace;

import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册表测试：在线/归档索引、断连归档优先级（崩溃现场）、归档容量淘汰、
 * dump 闭环（崩溃报告延迟到达场景）。
 */
class PacketTraceRegistryTest {

    @BeforeAll
    static void initSupport() {
        // I18nUtil/GameConfig 静态初始化依赖 Spring 上下文；必须在任何类初始化前就绪
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setup() {
        PacketTraceRegistry.disableFileOutputForTest(true);
    }

    @AfterEach
    void cleanup() {
        PacketTraceRegistry.disableFileOutputForTest(false);
        PacketTraceRegistry.clearForTest();
    }

    private static PacketTraceBuffer bufferWith(int entryCount) {
        PacketTraceBuffer buffer = new PacketTraceBuffer(16);
        for (int i = 0; i < entryCount; i++) {
            buffer.add(new PacketTraceEntry(1000L + i, PacketTraceEntry.DIR_SEND,
                    (short) i, "PKT_" + i, 2, "00 00"));
        }
        return buffer;
    }

    @Test
    void findPrefersArchiveOverOnline() {
        String ip = "192.168.0.50";
        PacketTraceBuffer online = bufferWith(3);
        PacketTraceBuffer archived = bufferWith(5);
        PacketTraceRegistry.registerOnline(ip, online);
        PacketTraceRegistry.archive(ip, archived);

        // 归档优先：崩溃报告到达时应拿到断连现场而不是新会话缓冲
        assertEquals(5, PacketTraceRegistry.find(ip).size());

        // 归档被移除后回退在线（测试钩子重置再验一次路径）
        PacketTraceRegistry.archive(ip, online);
        assertEquals(3, PacketTraceRegistry.find(ip).size());
    }

    @Test
    void findFallsBackToOnlineWhenNoArchive() {
        String ip = "192.168.0.51";
        PacketTraceBuffer online = bufferWith(4);
        PacketTraceRegistry.registerOnline(ip, online);
        assertEquals(4, PacketTraceRegistry.find(ip).size());
    }

    @Test
    void findReturnsNullForUnknownIp() {
        assertNull(PacketTraceRegistry.find("10.0.0.99"));
    }

    @Test
    void archiveEvictsOldestBeyondCapacity() {
        for (int i = 0; i < 12; i++) {
            String ip = "192.168.0." + (10 + i);
            PacketTraceBuffer buffer = bufferWith(i + 1);
            PacketTraceRegistry.registerOnline(ip, buffer);
            PacketTraceRegistry.archive(ip, buffer);
        }
        // 容量 8：最旧的 4 个 ip 被淘汰
        assertNull(PacketTraceRegistry.find("192.168.0.10"));
        assertNull(PacketTraceRegistry.find("192.168.0.13"));
        assertNotNull(PacketTraceRegistry.find("192.168.0.14"));
        assertNotNull(PacketTraceRegistry.find("192.168.0.21"));
    }

    @Test
    void archiveRemovesFromOnlineIndex() {
        String ip = "192.168.0.52";
        PacketTraceBuffer buffer = bufferWith(2);
        PacketTraceRegistry.registerOnline(ip, buffer);
        PacketTraceRegistry.archive(ip, buffer);
        // 在线索引已移除：find 只能命中归档
        assertEquals(2, PacketTraceRegistry.find(ip).size());
        List<String> summary = PacketTraceRegistry.listSummary();
        boolean onlineGone = summary.stream().noneMatch(line -> line.startsWith("[ONLINE] ip=" + ip));
        assertTrue(onlineGone, "断连后不应残留在线条目");
    }

    @Test
    void archiveIsIdempotentForSameBuffer() {
        String ip = "192.168.0.53";
        PacketTraceBuffer buffer = bufferWith(2);
        PacketTraceRegistry.registerOnline(ip, buffer);
        PacketTraceRegistry.archive(ip, buffer);
        PacketTraceRegistry.archive(ip, buffer);
        assertEquals(2, PacketTraceRegistry.find(ip).size());
    }

    @Test
    void listSummaryContainsBothKinds() {
        String onlineIp = "192.168.0.60";
        String archivedIp = "192.168.0.61";
        PacketTraceRegistry.registerOnline(onlineIp, bufferWith(1));
        PacketTraceBuffer archived = bufferWith(2);
        PacketTraceRegistry.registerOnline(archivedIp, archived);
        PacketTraceRegistry.archive(archivedIp, archived);

        List<String> summary = PacketTraceRegistry.listSummary();
        assertTrue(summary.stream().anyMatch(line -> line.startsWith("[ONLINE] ip=" + onlineIp)));
        assertTrue(summary.stream().anyMatch(line -> line.startsWith("[ARCHIVE] ip=" + archivedIp)));
    }
}
