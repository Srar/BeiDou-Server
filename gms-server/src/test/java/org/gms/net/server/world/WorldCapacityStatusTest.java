package org.gms.net.server.world;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.config.GameConfig;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.server.TimerManager;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 世界容量门槛的 bot 排除：loadenv 生成数千 bot 注册进 World.PlayerStorage，
 * 若计入容量则真实玩家登录全部被「频道人数已满」拒绝。本测试钉住 channel_capacity
 * 并验证 getWorldCapacityStatus/isWorldCapacityFull 只按真实玩家数判定，另覆盖
 * computeCapacityStatus 的 80% 与满员边界。
 */
class WorldCapacityStatusTest {

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize(); // GameConfig/Character 静态初始化依赖 mock ApplicationContext
    }

    @Test
    void capacityStatusExcludesBotsFromWorldGate() {
        try (MockedStatic<GameConfig> gc = Mockito.mockStatic(GameConfig.class);
             MockedStatic<Server> srv = Mockito.mockStatic(Server.class);
             MockedStatic<TimerManager> tm = Mockito.mockStatic(TimerManager.class)) {
            gc.when(() -> GameConfig.getServerInt("channel_capacity")).thenReturn(100);
            Server server = mock(Server.class);
            when(server.getCurrentTime()).thenReturn(0L);
            srv.when(Server::getInstance).thenReturn(server);
            // World 构造会注册一批定时任务，mock TimerManager 避免碰真实调度器
            TimerManager tman = mock(TimerManager.class);
            tm.when(TimerManager::getInstance).thenReturn(tman);

            World world = new World(0, 0, "", 1f, 1f, 1f, 1f, 1f, 1f, 1f);
            Channel channel = mock(Channel.class);
            when(channel.getId()).thenReturn(1); // addChannel 要求 id == channels.size()+1
            assertTrue(world.addChannel(channel), "测试频道应注册成功（worldCap = 100）");

            // 150 bot + 50 真实玩家：真实 50 < 100 → 状态 0，登录不受阻
            for (int i = 0; i < 150; i++) {
                world.addPlayer(botMock(2_100_000 + i, "Bot" + i));
            }
            for (int i = 0; i < 50; i++) {
                world.addPlayer(realMock(1000 + i, "Real" + i));
            }
            assertEquals(0, world.getWorldCapacityStatus(), "150 bot + 50 真实应判为正常（bot 不计容量）");
            assertFalse(world.isWorldCapacityFull());

            // 真实玩家补到 100 → 状态 2（满），bot 仍不参与
            for (int i = 50; i < 100; i++) {
                world.addPlayer(realMock(1000 + i, "Real" + i));
            }
            assertEquals(2, world.getWorldCapacityStatus(), "真实玩家达到容量应判为满");
            assertTrue(world.isWorldCapacityFull());
        }
    }

    @Test
    void computeCapacityStatusBoundaries() {
        assertEquals(0, World.computeCapacityStatus(100, 79), "79% 应为正常");
        assertEquals(1, World.computeCapacityStatus(100, 80), "80% 边界应为拥挤");
        assertEquals(1, World.computeCapacityStatus(100, 99), "99% 应为拥挤");
        assertEquals(2, World.computeCapacityStatus(100, 100), "满员应为 2");
        assertEquals(2, World.computeCapacityStatus(100, 150), "超员应为 2");
    }

    private static Character realMock(int id, String name) {
        return playerMock(id, name, mock(Client.class));
    }

    private static Character botMock(int id, String name) {
        Client botClient = mock(Client.class);
        when(botClient.isBot()).thenReturn(true);
        return playerMock(id, name, botClient);
    }

    private static Character playerMock(int id, String name, Client client) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getClient()).thenReturn(client);
        return chr;
    }
}
