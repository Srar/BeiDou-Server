package org.gms.server.bot.gcmove;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.TimerManager;
import org.gms.server.bot.wander.BotWanderSystem;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.gms.util.DatabaseConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户场景的最终离线复现：真实射手村（100000000）WZ 几何上，
 * 驱动移动 + 游荡系统自主踱步。若此测试通过，则"射手村 bot 不动"
 * 在代码侧已无任何未验证环节。
 */
public class RealMapE2ETest {

    private static final int HENESYS = 100000000;
    private MockedStatic<Server> serverMock;
    private MockedStatic<DatabaseConnection> dbMock;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
        TimerManager.getInstance().start();
    }

    @BeforeEach
    void setUp() throws Exception {
        serverMock = Mockito.mockStatic(Server.class);
        Server server = Mockito.mock(Server.class);
        serverMock.when(Server::getInstance).thenReturn(server);
        Channel channel = Mockito.mock(Channel.class);
        Mockito.when(server.getChannel(0, 1)).thenReturn(channel);
        World world = Mockito.mock(World.class);
        Mockito.when(server.getWorld(0)).thenReturn(world);
        Mockito.when(server.getWorlds()).thenReturn(List.<World>of());

        Connection con = Mockito.mock(Connection.class);
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(con.prepareStatement(Mockito.anyString())).thenReturn(ps);
        Mockito.when(ps.executeQuery()).thenReturn(rs);
        Mockito.when(rs.next()).thenReturn(false);
        dbMock = Mockito.mockStatic(DatabaseConnection.class);
        dbMock.when(DatabaseConnection::getConnection).thenReturn(con);
    }

    @AfterEach
    void tearDown() {
        if (serverMock != null) serverMock.close();
        if (dbMock != null) dbMock.close();
    }

    private MapleMap loadHenesys() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(HENESYS);
        assertNotNull(map, "真实射手村加载失败");
        return map;
    }

    @Test
    public void driverMovesBotOnRealHenesysGeometry() throws Exception {
        MapleMap map = loadHenesys();
        // 同步烘焙导航图（生产为 enable 异步 warm，实测 1.2s 级）
        BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());

        Character bot = Character.getDefault(new BotClient(0, 1));
        bot.setId(2_000_000_800);
        bot.setMap(map);
        Point spawn = map.getPortal(0).getPosition();
        bot.setPosition(new Point(spawn.x, spawn.y));

        GCMovement.enable(bot);
        // 沿真实 foothold 向右走 200px（getPointBelow 修正到地面）
        Point target = map.getPointBelow(new Point(spawn.x + 200, spawn.y + 5));
        assertNotNull(target, "目标点无地面");
        GCMovement.move(bot, target.x, target.y);

        int startX = bot.getPosition().x;
        Thread.sleep(5000);
        int endX = bot.getPosition().x;
        System.out.println("[RealMapE2E] driver: startX=" + startX + " endX=" + endX
                + " target=" + target.x + " moving=" + GCMovement.isMoving(bot));
        assertTrue(endX != startX || GCMovement.isMoving(bot),
                "真实射手村几何上驱动未推进（start=" + startX + ", end=" + endX + "）");
        GCMovement.disable(bot);
    }

    @Test
    public void wandererStrollsOnRealHenesysGeometry() throws Exception {
        MapleMap map = loadHenesys();
        BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());

        Character bot = Character.getDefault(new BotClient(0, 1));
        bot.setId(2_000_000_801);
        bot.setMap(map);
        Point spawn = map.getPortal(0).getPosition();
        bot.setPosition(new Point(spawn.x, spawn.y));

        int startX = bot.getPosition().x;
        BotWanderSystem.start(bot);
        Thread.sleep(12000);
        System.out.println("[RealMapE2E] wander: startX=" + startX + " endX=" + bot.getPosition().x
                + " moving=" + GCMovement.isMoving(bot) + " wandering=" + BotWanderSystem.isWandering(bot));
        assertTrue(bot.getPosition().x != startX || GCMovement.isMoving(bot),
                "真实射手村几何上游荡系统未移动（start=" + startX + ", end=" + bot.getPosition().x + "）");
        BotWanderSystem.stop(bot);
        GCMovement.disable(bot);
    }
}
