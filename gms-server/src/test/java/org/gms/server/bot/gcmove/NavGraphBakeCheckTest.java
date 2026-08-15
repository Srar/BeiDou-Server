package org.gms.server.bot.gcmove;

import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.gms.util.DatabaseConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 WZ 验证：加载射手村并同步构建导航图 + 构建世界连通图。
 * 依赖 BotTestSupport 的通用 bean mock（Spring 静态耦合全部指向 mock）。
 */
public class NavGraphBakeCheckTest {

    private MockedStatic<Server> serverMock;
    private MockedStatic<DatabaseConnection> dbMock;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
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

        // 地图加载会查 plife 表（生产走 DataSource），离线用空结果集代替
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
        if (serverMock != null) {
            serverMock.close();
        }
        if (dbMock != null) {
            dbMock.close();
        }
    }

    @Test
    public void bakeHenesysNavGraphFromRealWz() {
        long t0 = System.currentTimeMillis();
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(100000000);
        assertNotNull(map, "MapFactory 加载 Henesys 失败");
        System.out.println("[NavBake] map loaded in " + (System.currentTimeMillis() - t0) + "ms;"
                + " footholds=" + map.getFootholds().getAllFootholds().size()
                + " portals=" + map.getPortals().size()
                + " ropes=" + map.getRopes().size());
        assertTrue(map.getFootholds().getAllFootholds().size() > 10, "foothold 数量异常少");

        t0 = System.currentTimeMillis();
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        assertNotNull(graph, "导航图构建返回 null");
        System.out.println("[NavBake] graph built in " + (System.currentTimeMillis() - t0) + "ms");
        System.out.println("[NavBake] report=" + BotNavigationGraphProvider.getLastBuildReport(100000000));
    }

    @Test
    public void buildWorldGraphFromRealWz() {
        long t0 = System.currentTimeMillis();
        int maps = GCMovement.ensureWorldGraph();
        System.out.println("[WorldGraph] built " + maps + " maps in " + (System.currentTimeMillis() - t0) + "ms");
        assertTrue(maps > 100, "世界图地图数异常少: " + maps);
    }
}
