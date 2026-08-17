package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.maps.MapManager;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DefaultBotServerAccess（生产接缝）：channel 缺失即拒绝注册、channel/world 双注册、
 * 空 channel/world 移除容忍、取图/按 ID 取角色委托、跨世界名字查重、world/channel 配置回落。
 * 经 mockStatic Server 隔离重量级单例；resolveBotWorld/Channel 走空 GameConfig（回落 0/1）。
 */
class DefaultBotServerAccessTest {

    private static final int CHARACTER_ID = 970_000_001;

    private MockedStatic<Server> serverStatic;
    private Server server;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        serverStatic = Mockito.mockStatic(Server.class);
        server = mock(Server.class);
        serverStatic.when(Server::getInstance).thenReturn(server);
    }

    @AfterEach
    void tearDown() {
        serverStatic.close();
    }

    private static Character newBotCharacter() {
        Character bot = mock(Character.class);
        Client client = mock(Client.class);
        when(bot.getWorld()).thenReturn(0);
        when(bot.getClient()).thenReturn(client);
        when(client.getChannel()).thenReturn(1);
        return bot;
    }

    @Test
    void addBotToServerThrowsWhenChannelMissing() {
        Character bot = newBotCharacter();
        when(server.getChannel(0, 1)).thenReturn(null);

        // 频道缺失必须拒绝注册（消息来自 i18n key，这里只断言异常类型）
        assertThrows(IllegalStateException.class,
                () -> DefaultBotServerAccess.INSTANCE.addBotToServer(bot));
    }

    @Test
    void addBotToServerRegistersChannelAndWorld() {
        Character bot = newBotCharacter();
        Channel channel = mock(Channel.class);
        World world = mock(World.class);
        PlayerStorage storage = mock(PlayerStorage.class);
        when(server.getChannel(0, 1)).thenReturn(channel);
        when(server.getWorld(0)).thenReturn(world);
        when(world.getPlayerStorage()).thenReturn(storage);

        DefaultBotServerAccess.INSTANCE.addBotToServer(bot);

        verify(channel).addPlayer(bot);
        verify(storage).addPlayer(bot);
    }

    @Test
    void removeBotFromServerWithNullChannelIsSafe() {
        Character bot = newBotCharacter();
        when(bot.getId()).thenReturn(CHARACTER_ID);
        when(server.getChannel(0, 1)).thenReturn(null);
        when(server.getWorld(0)).thenReturn(null);

        // 生产路径要求：channel/world 为空（如已关停）时移除必须容忍
        DefaultBotServerAccess.INSTANCE.removeBotFromServer(bot);
    }

    @Test
    void removeBotFromServerReleasesGCMovementState() {
        Character bot = newBotCharacter();
        when(bot.getId()).thenReturn(CHARACTER_ID);
        when(server.getChannel(0, 1)).thenReturn(null);
        when(server.getWorld(0)).thenReturn(null);

        // GCMovement.enable 判活：已注册（或带地图）bot 才建状态。mock bot 无 map，
        // 先注册进 BotStorage（mock BotSM 的 getChr() 为 null，addActiveBot 容忍并跳过索引），
        // enable 走「map 为 null」分支照常创建 BotMovementState（跳过地图初始化）。
        // fromCharacter 的装备栏速度/跳跃统计需要空装备栏（默认 null 会 NPE）。
        Inventory equipped = mock(Inventory.class);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(equipped.iterator()).thenReturn(Collections.emptyIterator());

        BotSM registered = mock(BotSM.class);
        BotStorage.addActiveBot(CHARACTER_ID, registered);
        try {
            GCMovement.enable(bot);
            assertTrue(GCMovement.isEnabled(bot), "enable must create the movement state");

            DefaultBotServerAccess.INSTANCE.removeBotFromServer(bot);

            assertFalse(GCMovement.isEnabled(bot),
                    "removeBotFromServer must release the movement engine state");
        } finally {
            GCMovement.disable(bot); // 幂等兜底：断言失败也不残留移动状态
            BotStorage.removeActiveBot(CHARACTER_ID);
        }
    }

    @Test
    void getMapDelegatesToChannelMapFactory() {
        Channel channel = mock(Channel.class);
        MapManager mapFactory = mock(MapManager.class);
        MapleMap map = mock(MapleMap.class);
        when(server.getChannel(0, 1)).thenReturn(channel);
        when(channel.getMapFactory()).thenReturn(mapFactory);
        when(mapFactory.getMap(123)).thenReturn(map);

        MapleMap result = DefaultBotServerAccess.INSTANCE.getMap(0, 1, 123);

        assertSame(map, result, "getMap must delegate to the channel's map factory");
    }

    @Test
    void getMapWithMissingChannelReturnsNull() {
        when(server.getChannel(0, 1)).thenReturn(null);

        assertNull(DefaultBotServerAccess.INSTANCE.getMap(0, 1, 123),
                "missing channel must yield null map");    }

    @Test
    void getCharacterByIdDelegatesToPlayerStorage() {
        // resolveBotWorld/Channel 走空 GameConfig → 回落 0/1
        Channel channel = mock(Channel.class);
        PlayerStorage storage = mock(PlayerStorage.class);
        Character chr = mock(Character.class);
        when(server.getChannel(0, 1)).thenReturn(channel);
        when(channel.getPlayerStorage()).thenReturn(storage);
        when(storage.getCharacterById(CHARACTER_ID)).thenReturn(chr);

        Character result = DefaultBotServerAccess.INSTANCE.getCharacterById(CHARACTER_ID);

        assertSame(chr, result, "getCharacterById must delegate to the bot channel's player storage");
    }

    @Test
    void isNameTakenScansAllWorlds() {
        World world1 = mock(World.class);
        World world2 = mock(World.class);
        PlayerStorage storage1 = mock(PlayerStorage.class);
        PlayerStorage storage2 = mock(PlayerStorage.class);
        Character existing = mock(Character.class);
        when(server.getWorlds()).thenReturn(List.of(world1, world2));
        when(world1.getPlayerStorage()).thenReturn(storage1);
        when(world2.getPlayerStorage()).thenReturn(storage2);
        when(storage1.getCharacterByName("小明")).thenReturn(null);
        when(storage2.getCharacterByName("小明")).thenReturn(existing);

        assertTrue(DefaultBotServerAccess.INSTANCE.isNameTaken("小明"),
                "name present in any world must be taken");

        when(storage2.getCharacterByName("小明")).thenReturn(null);
        assertFalse(DefaultBotServerAccess.INSTANCE.isNameTaken("小明"),
                "name absent from every world must be free");
    }

    @Test
    void resolveBotWorldFallsBackToZero() {
        assertEquals(0, DefaultBotServerAccess.resolveBotWorld(),
                "empty GameConfig must fall back to world 0");
    }

    @Test
    void resolveBotChannelFallsBackToOne() {
        assertEquals(1, DefaultBotServerAccess.resolveBotChannel(),
                "empty GameConfig must fall back to channel 1");
    }
}
