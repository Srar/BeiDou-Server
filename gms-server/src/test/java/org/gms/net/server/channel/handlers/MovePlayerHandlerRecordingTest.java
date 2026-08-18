package org.gms.net.server.channel.handlers;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.packet.InPacket;
import org.gms.server.bot.replay.InPacketReader;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * MovePlayerHandler 移动录制钩子：录制开关关闭时零调用，开启时对当前 InPacket 调用
 * recordMovementInPacketToBinaryAndCSV。InPacketReader 用静态 mock 拦截（避免真实文件
 * 写入），并在每个用例后复位静态录制状态。
 */
class MovePlayerHandlerRecordingTest {

    private MockedStatic<InPacketReader> inPacketReaderMock;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @AfterEach
    void tearDown() {
        if (inPacketReaderMock != null) {
            inPacketReaderMock.close();
            inPacketReaderMock = null;
        }
        // 静态录制状态复位：测试不得泄漏「录制开启」到其他用例/生产路径
        InPacketReader.boolRecordMovementData = false;
        InPacketReader.movementDataRecordingName = "default_movement_recording";
        InPacketReader.movementRecordingMap = 0;
    }

    @Test
    void recordingDisabledSkipsRecorderEntirely() {
        inPacketReaderMock = Mockito.mockStatic(InPacketReader.class);
        inPacketReaderMock.when(InPacketReader::getMoveDataRecording).thenReturn(false);

        InPacket p = Mockito.mock(InPacket.class);
        Client c = Mockito.mock(Client.class);
        Character player = Mockito.mock(Character.class); // 不在 thenReturn 内联创建（避免未完成 stubbing）
        Mockito.when(c.getPlayer()).thenReturn(player);

        new MovePlayerHandler().handlePacket(p, c);

        inPacketReaderMock.verify(() -> InPacketReader.recordMovementInPacketToBinaryAndCSV(any(InPacket.class)), never());
        verify(p).skip(9); // 正常处理路径不受影响
    }

    @Test
    void recordingEnabledCallsRecorderWithCurrentPacket() {
        inPacketReaderMock = Mockito.mockStatic(InPacketReader.class);
        inPacketReaderMock.when(InPacketReader::getMoveDataRecording).thenReturn(true);

        InPacket p = Mockito.mock(InPacket.class);
        Client c = Mockito.mock(Client.class);
        Character player = Mockito.mock(Character.class); // 不在 thenReturn 内联创建（避免未完成 stubbing）
        Mockito.when(c.getPlayer()).thenReturn(player);

        new MovePlayerHandler().handlePacket(p, c);

        inPacketReaderMock.verify(() -> InPacketReader.recordMovementInPacketToBinaryAndCSV(p), times(1));
    }
}
