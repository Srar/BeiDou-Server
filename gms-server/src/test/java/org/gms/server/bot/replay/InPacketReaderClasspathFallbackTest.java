package org.gms.server.bot.replay;

import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 录制品读取的 classpath 回退语义测试：
 * 服务从仓库根等非 gms-server 目录启动时，文件系统相对路径读不到录制品，
 * 应回退到 jar/classpath 内打包的 movementDataPackets 资源（见 InPacketReader.openBinaryStream）。
 */
class InPacketReaderClasspathFallbackTest {

    private static final String BIN = "movementDataPackets/map0/portalenterdrop.bin";
    private static final String CSV = "movementDataPackets/map0/leftright70.csv";

    private File binBackup;
    private File csvBackup;

    @BeforeAll
    static void init() {
        BotTestSupport.initialize();
    }

    @AfterEach
    void restore() {
        restoreFile(BIN, binBackup);
        restoreFile(CSV, csvBackup);
    }

    @Test
    void binaryRecordingFallsBackToClasspathWhenFileSystemFileMissing() {
        File bin = new File(BIN);
        assumeTrue(bin.isFile(), "前置：文件系统上存在录制品（工作目录为 gms-server）");
        binBackup = new File(BIN + ".bak");
        assertTrue(bin.renameTo(binBackup), "前置：应能临时移开文件系统录制品");

        // 文件系统已无该文件 → 走 classpath 回退 → 仍能读回完整录制包
        List<MovementPacket> packets = InPacketReader.readPacketsFromFile(BIN);
        assertFalse(packets.isEmpty(), "classpath 回退应能读到打包录制品");
    }

    @Test
    void csvRecordingFallsBackToClasspathWhenFileSystemFileMissing() {
        File csv = new File(CSV);
        assumeTrue(csv.isFile(), "前置：文件系统上存在录制品（工作目录为 gms-server）");
        csvBackup = new File(CSV + ".bak");
        assertTrue(csv.renameTo(csvBackup), "前置：应能临时移开文件系统录制品");

        MovementRecordingRaw raw = InPacketReader.getMovementRecordingRaw(0, "leftright70");
        assertFalse(raw.getMovementPacketList().isEmpty(), "classpath 回退应能读到打包录制品");
    }

    @Test
    void missingEverywhereReportsClearError() {
        assertThrows(RuntimeException.class,
                () -> InPacketReader.readPacketsFromFile("movementDataPackets/map0/__missing__.bin"),
                "文件系统与 classpath 均缺失时应抛出明确异常（由调用方兜底降级）");
    }

    private static void restoreFile(String originalPath, File backup) {
        if (backup == null) {
            return;
        }
        File original = new File(originalPath);
        if (backup.isFile() && !original.exists()) {
            backup.renameTo(original);
        }
    }
}
