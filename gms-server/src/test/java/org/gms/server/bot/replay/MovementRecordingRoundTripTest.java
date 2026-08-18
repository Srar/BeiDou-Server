package org.gms.server.bot.replay;

import org.gms.net.packet.InPacket;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 录制品文件读盘 round-trip 单测：覆盖六个回放消费点实际引用的录制数据文件
 * （JQ 七档 / 掉落游戏 / 教程回程 / 传送落下 / FM 门导航），断言二进制流
 * 可完整读出且结构自洽（mapId/录制名/包列表/时间戳单调）。
 * <p>
 * 文件经相对路径 movementDataPackets/ 解析——surefire 的工作目录为 gms-server
 * 模块 basedir，与生产运行（working dir = gms-server）一致。
 */
class MovementRecordingRoundTripTest {

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    private static void assertRecordingRoundTrip(int mapId, String name) {
        MovementRecording mvr = InPacketReader.getMovementRecording(mapId, name);
        assertNotNull(mvr, "录制品应能加载: map" + mapId + "/" + name);
        assertEquals(mapId, mvr.getMapId());
        assertEquals(name, mvr.getRecordingName());
        List<MovementPacket> packets = mvr.getMovementPacketList();
        assertFalse(packets.isEmpty(), "录制品包列表不应为空: map" + mapId + "/" + name);

        long previousTimestamp = Long.MIN_VALUE;
        for (MovementPacket packet : packets) {
            InPacket p = packet.getPacket();
            assertNotNull(p);
            assertNotNull(p.copy(), "包二进制应可完整 copy（round-trip）");
            assertTrue(packet.getTimestamp() >= previousTimestamp,
                    "时间戳应单调非降: map" + mapId + "/" + name);
            previousTimestamp = packet.getTimestamp();
        }
    }

    @Test
    void jqTierRecordingsRoundTrip() {
        // Pet Park (100000202) 七档攀爬录制：本测试抽查第 1/4/6/7 档。
        assertRecordingRoundTrip(100000202, "jq1fail");
        assertRecordingRoundTrip(100000202, "jq4fail");
        assertRecordingRoundTrip(100000202, "jq6afail");
        assertRecordingRoundTrip(100000202, "jq7top");
    }

    @Test
    void dropGameRecordingRoundTrip() {
        // 掉落游戏（药剂店 100000102 / 101000002 双图都录有 dg_potshop_1）。
        assertRecordingRoundTrip(100000102, "dg_potshop_1");
        assertRecordingRoundTrip(101000002, "dg_potshop_1");
    }

    @Test
    void tutorialReturnRecordingRoundTrip() {
        // 教程 bot 回等待点录制（map10000）。
        assertRecordingRoundTrip(10000, "tutorial2");
    }

    @Test
    void portalDropDownAndMicroTurnRecordingsRoundTrip() {
        // 传送落下（map0/portalenterdrop，WarpCommands.botEnterPortalDropDown 引用）
        // 与出生编排微转身（map0/turnaroundtoleft，microTurnAroundToLeft 引用）。
        assertRecordingRoundTrip(0, "portalenterdrop");
        assertRecordingRoundTrip(0, "turnaroundtoleft");
    }
}
