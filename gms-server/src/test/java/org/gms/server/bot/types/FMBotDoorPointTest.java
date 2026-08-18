package org.gms.server.bot.types;

import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.server.maps.MapManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.awt.Point;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;

/**
 * FMBot 门导航定位单测（mock 地图数据）：恢复接线后的 {@code getDoorPoint(room)}
 * 应委托 {@code FMMovementCommands.getDoorPoint}——即 FM 入口图（910000000）上
 * {@code in%02d} 房间门 portal 的坐标，返回非 null（SoloMapling 原语义）。
 * <p>
 * 依赖链（Server 静态单例 → channel → mapFactory → FM 入口图 → portal）全部 mock；
 * DefaultBotServerAccess.resolveBotWorld/Channel 在 BotTestSupport 空配置下回落 0/1，
 * 故对 getChannel 用 anyInt 匹配。
 */
class FMBotDoorPointTest {

    private static final int FM_ENTRANCE = 910000000;
    private static final int BOT_ID = 2_000_002_001;

    private MockedStatic<Server> serverStatic;
    private Character bot;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    private FMBot newFMBotWithDoorPortal(Point doorPosition, String portalName) {
        // mock 链路：Server.getInstance().getChannel(any, any).getMapFactory().getMap(910000000).getPortal(name)
        // doorPosition == null 表示「门 portal 缺失」场景（getPortal 返回 null）。
        MapleMap fmEntranceMap = Mockito.mock(MapleMap.class);
        if (doorPosition != null) {
            Portal doorPortal = Mockito.mock(Portal.class);
            Mockito.when(doorPortal.getPosition()).thenReturn(doorPosition);
            Mockito.when(fmEntranceMap.getPortal(portalName)).thenReturn(doorPortal);
        } else {
            Mockito.when(fmEntranceMap.getPortal(portalName)).thenReturn(null);
        }

        MapManager mapFactory = Mockito.mock(MapManager.class);
        Mockito.when(mapFactory.getMap(FM_ENTRANCE)).thenReturn(fmEntranceMap);

        Channel channel = Mockito.mock(Channel.class);
        Mockito.when(channel.getMapFactory()).thenReturn(mapFactory);

        serverStatic = Mockito.mockStatic(Server.class);
        Server server = Mockito.mock(Server.class);
        Mockito.when(server.getChannel(anyInt(), anyInt())).thenReturn(channel);
        serverStatic.when(Server::getInstance).thenReturn(server);

        bot = Mockito.mock(Character.class);
        Mockito.when(bot.getId()).thenReturn(BOT_ID);
        Mockito.when(bot.getName()).thenReturn("TestFMBot");
        return new FMBot(bot);
    }

    @AfterEach
    void tearDown() {
        if (serverStatic != null) {
            serverStatic.close();
        }
    }

    private Point invokeGetDoorPoint(FMBot fmBot, int room) throws Exception {
        Method method = FMBot.class.getDeclaredMethod("getDoorPoint", int.class);
        method.setAccessible(true);
        return (Point) method.invoke(fmBot, room);
    }

    @Test
    void doorPointResolvesFromFmEntrancePortal() throws Exception {
        Point expected = new Point(880, -520);
        FMBot fmBot = newFMBotWithDoorPortal(expected, "in01");

        Point doorPoint = invokeGetDoorPoint(fmBot, 1);

        assertNotNull(doorPoint, "房间门坐标应定位成功（非 null）");
        assertEquals(expected.x, doorPoint.x);
        assertEquals(expected.y, doorPoint.y);
    }

    @Test
    void doorPointUsesRoomSpecificPortal() throws Exception {
        Point expected = new Point(1400, 60);
        FMBot fmBot = newFMBotWithDoorPortal(expected, "in07");

        Point doorPoint = invokeGetDoorPoint(fmBot, 7);

        assertNotNull(doorPoint);
        assertEquals(expected.x, doorPoint.x);
        assertEquals(expected.y, doorPoint.y);
    }

    @Test
    void doorPointReturnsNullWhenPortalMissing() throws Exception {
        // 门 portal 缺失（FM 入口图未加载/门不存在）：getDoorPosition 判空返回 null 兜底
        //（FMBot.getDoorPoint 注释承诺的语义），不得抛 NPE。
        FMBot fmBot = newFMBotWithDoorPortal(null, "in01");

        Point doorPoint = invokeGetDoorPoint(fmBot, 1);

        org.junit.jupiter.api.Assertions.assertNull(doorPoint, "portal 缺失时门点必须返回 null（不抛 NPE）");
    }
}
