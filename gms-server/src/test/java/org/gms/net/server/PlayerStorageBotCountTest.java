package org.gms.net.server;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PlayerStorage 的 bot 会话计数维护：容量统计（World/Channel）依赖 getBotCount()
 * 排除 loadenv 生成的数千 bot。覆盖 add/remove/disconnectAll 三条路径与 null client 边界。
 */
class PlayerStorageBotCountTest {

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize(); // Character 静态初始化依赖 mock ApplicationContext
    }

    @Test
    void botCountTracksAddRemoveAndClear() {
        PlayerStorage storage = new PlayerStorage();

        // client 为 null（防御）与普通 client 均视为非 bot
        storage.addPlayer(playerMock(1, "RealOne", null));
        storage.addPlayer(playerMock(2, "RealTwo", mock(Client.class)));
        storage.addPlayer(botMock(3, "BotOne"));
        storage.addPlayer(botMock(4, "BotTwo"));

        assertEquals(4, storage.getSize(), "总人口应含 bot");
        assertEquals(2, storage.getBotCount(), "bot 计数应只统计 isBot 会话");

        storage.removePlayer(3);
        assertEquals(3, storage.getSize());
        assertEquals(1, storage.getBotCount(), "移除 bot 应同步减计数");

        storage.removePlayer(1);
        assertEquals(2, storage.getSize());
        assertEquals(1, storage.getBotCount(), "移除非 bot 不应减 bot 计数");

        storage.removePlayer(999); // 不存在：不应影响计数
        assertEquals(1, storage.getBotCount());

        storage.disconnectAll();
        assertEquals(0, storage.getSize());
        assertEquals(0, storage.getBotCount(), "清空后 bot 计数应归零");
    }

    @Test
    void repeatedAddWithSameIdDoesNotDriftBotCount() {
        PlayerStorage storage = new PlayerStorage();

        // 同 id 重复注册：storage 覆盖且 botCount 按被覆盖旧角色类型回退（生产 bot id 唯一，此为防御不变量）
        storage.addPlayer(botMock(3, "BotOne"));
        storage.addPlayer(botMock(3, "BotOneAgain"));
        assertEquals(1, storage.getBotCount(), "bot 覆盖 bot 计数不变");

        storage.addPlayer(playerMock(3, "RealTakeover", mock(Client.class)));
        assertEquals(1, storage.getSize());
        assertEquals(0, storage.getBotCount(), "真实角色覆盖 bot 后 bot 计数应回退为 0");

        storage.removePlayer(3);
        assertEquals(0, storage.getSize());
        assertEquals(0, storage.getBotCount());
    }

    private static Character playerMock(int id, String name, Client client) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getClient()).thenReturn(client);
        return chr;
    }

    /** bot 会话：Client.isBot() 为真（等价于生产中的 BotClient 覆写行为）。 */
    private static Character botMock(int id, String name) {
        Client botClient = mock(Client.class);
        when(botClient.isBot()).thenReturn(true);
        return playerMock(id, name, botClient);
    }
}
