package org.gms.client;

import org.gms.net.packet.Packet;
import org.gms.server.life.Monster;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Client#announceBossHpBar(Monster, int, Packet)} 对 headless 客户端的防御：
 * player == null（bot 共享 client、无角色挂载）时直接返回，不触碰 player 字段与发包逻辑。
 * <p>
 * Client.createMock 只做字段赋值（player 为 null），BotClient 构造同路径，均不触发 Spring；
 * BotTestSupport.initialize 仅作兜底（mock Monster 会加载其类，静态初始化可能触碰容器）。
 */
class ClientBotSafetyTest {

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @Test
    void createMockClientAnnounceBossHpBarIsSafeWithoutPlayer() {
        Client client = Client.createMock(); // player 恒为 null

        client.announceBossHpBar(Mockito.mock(Monster.class), 1, Mockito.mock(Packet.class));

        assertTrue(true, "announceBossHpBar on player-less createMock client must not throw");
    }

    @Test
    void botClientAnnounceBossHpBarIsSafeWithoutPlayer() {
        Client client = new BotClient(0, 1); // 共享无头客户端：player 恒为 null

        client.announceBossHpBar(Mockito.mock(Monster.class), 1, Mockito.mock(Packet.class));

        assertTrue(true, "announceBossHpBar on headless BotClient must not throw");
    }
}
