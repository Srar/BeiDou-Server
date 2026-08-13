package org.gms.client;

import org.gms.net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 无头 BotClient：发包 no-op、恒已登录、断线/关会话安全、永不闲置。
 * 不依赖 Spring/数据库（Client 构造器只赋值字段，createMock 已有先例）。
 */
class BotClientTest {

    @Test
    void sendPacketIsNoOp() {
        BotClient botClient = new BotClient(0, 1);
        botClient.sendPacket(Mockito.mock(Packet.class)); // 无 socket：不得抛异常
        assertTrue(true);
    }

    @Test
    void isAlwaysLoggedIn() {
        BotClient botClient = new BotClient(0, 1);
        assertTrue(botClient.isLoggedIn(), "headless bot client must always read as logged in");
    }

    @Test
    void disconnectAndCloseAreSafe() {
        BotClient botClient = new BotClient(0, 1);
        botClient.disconnectSession();
        botClient.closeSession();
        assertTrue(true);
    }

    @Test
    void lastPacketIsFresh() {
        BotClient botClient = new BotClient(0, 1);
        long before = System.currentTimeMillis();
        long last = botClient.getLastPacket();
        assertTrue(last >= before - 5 && last <= System.currentTimeMillis(),
                "lastPacket must read as freshly active");
    }
}
