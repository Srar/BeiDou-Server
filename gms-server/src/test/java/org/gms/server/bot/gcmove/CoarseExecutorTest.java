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

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 门禁（离线）：解析式 {@link CoarseExecutor} / {@link MovementPlan} 是耗时的纯函数，
 * 用假时钟确定性验证——不需要活客户端，也不需要人群。测试 1 在合成计划上钉住插值/完成数学；
 * 测试 2–3 证明同样的数学穿过真实烘焙的边代价/几何与活 A* 工厂。与生产同包，以便访问
 * 包私有的 plan/executor/graph。WZ 测试需要仓库 {@code wz/} 目录（工作目录为 gms-server，
 * 相对路径 wz/ 即可解析）。
 * 移植自 SoloMapling 的 CoarseExecutorTest，适配点：BeiDou 地图加载直接走
 * BotNavigationMapLoader（MapFactory 加载流），无 wz-path 系统属性；WZ 测试沿用
 * NavGraphBakeCheckTest 的 Server/数据库 mock 模式。
 */
class CoarseExecutorTest {

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

    private static BotNavigationGraph.Edge walk(int x1, int y1, int x2, int y2, int cost) {
        return new BotNavigationGraph.Edge(0, 1, BotNavigationGraph.EdgeType.WALK,
                new Point(x1, y1), new Point(x2, y2), 0, -1, 0, 0, 0, cost);
    }

    // ── 测试 1：纯插值数学，不碰 WZ ───────────────────────────────
    @Test
    void interpolatesAndCompletesDeterministically() {
        // 3 条边，总时长 2500ms：(0,0)->(100,0) 1000，->(100,50) 500，->(200,50) 1000。
        MovementPlan plan = MovementPlan.inMap(100000000, List.of(
                walk(0, 0, 100, 0, 1000),
                walk(100, 0, 100, 50, 500),
                walk(100, 50, 200, 50, 1000)));
        assertNotNull(plan);
        assertEquals(2500L, plan.totalTimeMs);

        assertEquals(new Point(0, 0), plan.positionAt(0));
        assertEquals(new Point(50, 0), plan.positionAt(500));    // 边 0 中段
        assertEquals(new Point(100, 0), plan.positionAt(1000));  // 边 0/1 交界
        assertEquals(new Point(100, 25), plan.positionAt(1250)); // 边 1 中段
        assertEquals(new Point(100, 50), plan.positionAt(1500)); // 边 1/2 交界
        assertEquals(new Point(150, 50), plan.positionAt(2000)); // 边 2 中段
        assertEquals(new Point(200, 50), plan.positionAt(2500)); // 终点
        assertEquals(new Point(200, 50), plan.positionAt(9999)); // 越过终点时钳制

        assertFalse(plan.isComplete(2499));
        assertTrue(plan.isComplete(2500));

        // CoarseExecutor.advance 是 (plan, start, now) 的纯函数：假时钟下答案相同。
        long start = 1_000_000L;
        CoarseExecutor.Step mid = CoarseExecutor.advance(plan, start, start + 1250);
        assertEquals(new Point(100, 25), mid.position());
        assertFalse(mid.complete());
        CoarseExecutor.Step done = CoarseExecutor.advance(plan, start, start + 2500);
        assertEquals(new Point(200, 50), done.position());
        assertTrue(done.complete());
    }

    // ── 测试 2：一条真实烘焙的射手村边原样流过 ─────────────
    @Test
    void interpolatesARealBakedEdge() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(100000000);
        BotNavigationGraph g = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        assertNotNull(g);

        BotNavigationGraph.Edge edge = firstEdgeWithCost(g);
        assertNotNull(edge, "期望至少一条烘焙边 cost > 0");

        MovementPlan plan = MovementPlan.inMap(map.getId(), List.of(edge));
        assertNotNull(plan);
        assertEquals(edge.cost, plan.totalTimeMs);
        assertEquals(new Point(edge.startPoint), plan.positionAt(0));
        assertEquals(new Point(edge.endPoint), plan.positionAt(edge.cost));
        assertTrue(CoarseExecutor.advance(plan, 0L, edge.cost).complete());
        assertFalse(CoarseExecutor.advance(plan, 0L, edge.cost - 1).complete());
    }

    // ── 测试 3：活 A* 工厂能规划出一条总时长合理的路线 ──────
    @Test
    void factoryPlansARealRoute() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(100000000);
        BotNavigationGraph g = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());

        // 用一条真实边的两端作为路线的起点/目标，向上抬几个 px，使 findGroundFoothold
        // （向下扫描地面——引擎约定，见 GCMovement.enable 的 y-1）能解析到它们。
        // 两个不同区域之间保证有路径。
        MovementPlan plan = null;
        for (List<BotNavigationGraph.Edge> outgoing : g.outgoingByRegionId.values()) {
            for (BotNavigationGraph.Edge e : outgoing) {
                if (e.cost <= 0) {
                    continue;
                }
                Point startP = new Point(e.startPoint.x, e.startPoint.y - 4);
                Point targetP = new Point(e.endPoint.x, e.endPoint.y - 4);
                if (g.findRegionId(map, startP) < 0 || g.findRegionId(map, targetP) < 0) {
                    continue;
                }
                plan = MovementPlan.inMap(g, map, startP, targetP);
                if (plan != null) {
                    break;
                }
            }
            if (plan != null) {
                break;
            }
        }
        assertNotNull(plan, "期望 A* 工厂至少规划出一条可行走路线");
        assertTrue(plan.totalTimeMs > 0, "规划路线应耗时 > 0 ms");
        // 计划的端点分别是首边的起点与末边的终点。
        assertEquals(plan.positionAt(0), plan.positionAt(-5)); // 低于 0 时钳制
        assertTrue(plan.isComplete(plan.totalTimeMs));
    }

    private static BotNavigationGraph.Edge firstEdgeWithCost(BotNavigationGraph g) {
        for (List<BotNavigationGraph.Edge> outgoing : g.outgoingByRegionId.values()) {
            for (BotNavigationGraph.Edge e : outgoing) {
                if (e.cost > 0) {
                    return e;
                }
            }
        }
        return null;
    }
}
