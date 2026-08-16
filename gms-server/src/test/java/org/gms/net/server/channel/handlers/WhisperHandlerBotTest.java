package org.gms.net.server.channel.handlers;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.Packet;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.world.World;
import org.gms.test.BotTestSupport;
import org.gms.util.PacketCreator.WhisperFlag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WhisperHandler 的 bot 交互点：真实玩家对 bot 名使用 /find（定位）时，
 * target 命中注册在 PlayerStorage 里的 bot——bot 由 Character.getDefault 构造、
 * 未初始化 CashShop。修复前 handleFind 的 target.getCashShop().isOpened()
 * 直接 NPE（与 PacketCreator.charInfo 同根因），修复后判空并正常回落
 * 同频道/跨频道分支。
 * <p>
 * getFindResult 为纯字段封包（不触碰 WZ/DB），此处走真实 PacketCreator 全链路。
 */
class WhisperHandlerBotTest {

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @Test
    void findOnBotWithoutCashShopDoesNotThrow() {
        WhisperHandler handler = new WhisperHandler();

        InPacket inPacket = mock(InPacket.class);
        when(inPacket.readByte()).thenReturn((byte) (WhisperFlag.LOCATION | WhisperFlag.REQUEST));
        when(inPacket.readString()).thenReturn("ShopBot");

        // target = bot：headless client + 未初始化 CashShop（getCashShop() 恒 null）
        Character target = mock(Character.class);
        when(target.getName()).thenReturn("ShopBot");
        when(target.getPosition()).thenReturn(new Point(0, 0));
        when(target.getMapId()).thenReturn(910000000);
        Client botClient = mock(Client.class);
        when(botClient.getChannel()).thenReturn(1);
        when(target.getClient()).thenReturn(botClient);

        // user = 真实玩家（gmLevel 默认 0 ≥ target 的 0，进入查找分支）
        Character user = mock(Character.class);
        Client userClient = mock(Client.class);
        when(userClient.getChannel()).thenReturn(1);
        when(user.getClient()).thenReturn(userClient);

        PlayerStorage storage = mock(PlayerStorage.class);
        when(storage.getCharacterByName("ShopBot")).thenReturn(target);
        World world = mock(World.class);
        when(world.getPlayerStorage()).thenReturn(storage);
        Client c = mock(Client.class);
        when(c.getWorldServer()).thenReturn(world);
        when(c.getPlayer()).thenReturn(user);

        // 修复前此处 NPE：target.getCashShop().isOpened()（bot 无 CashShop）
        handler.handlePacket(inPacket, c);

        verify(user).sendPacket(any(Packet.class)); // 应正常产出同频道 find 结果封包
    }
}
