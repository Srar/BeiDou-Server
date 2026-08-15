package org.gms.server.bot.gcmove;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.server.TimerManager;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.awt.Point;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线验证移动执行链核心：合成平地地图上，真实物理引擎 + BotMovementManager 的
 * tickGrounded 是否真的能推进 bot 位置（不依赖 WZ/导航图/真实服务器）。
 */
public class MovementExecutionTest {

    private static final int MAP_ID = 910000001;
    private MockedStatic<Server> serverMock;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
        TimerManager.getInstance().start();
    }

    // 注意：不要在 @AfterAll 停 TimerManager——它是进程级共享单例，
    // 停掉会破坏同一测试 fork 中后续依赖真实调度的测试（如 BotTickServiceTest）。
    private MapleMap makeFlatMap() {
        MapleMap map = new MapleMap(MAP_ID, 0, 1, 100000000, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        tree.insert(new Foothold(new Point(-1500, 100), new Point(1500, 100), 1));
        map.setFootholds(tree);
        return map;
    }

    @BeforeEach
    void setUp() {
        serverMock = Mockito.mockStatic(Server.class);
        Server server = Mockito.mock(Server.class);
        serverMock.when(Server::getInstance).thenReturn(server);
        Channel channel = Mockito.mock(Channel.class);
        Mockito.when(server.getChannel(0, 1)).thenReturn(channel);
        World world = Mockito.mock(World.class);
        Mockito.when(server.getWorld(0)).thenReturn(world);
        Mockito.when(server.getWorlds()).thenReturn(List.<World>of());
    }

    @AfterEach
    void tearDown() {
        if (serverMock != null) {
            serverMock.close();
        }
    }

    @Test
    public void groundedTickAdvancesBotTowardTarget() {
        MapleMap map = makeFlatMap();
        Character bot = Character.getDefault(new BotClient(0, 1));
        bot.setId(2_000_000_500);
        bot.setMap(map);
        bot.setPosition(new Point(0, 100));
        // 不调 map.addPlayer：startItemMonitor 需要真实 DB 配置（离线不可用），
        // 且本测试只验证物理移动执行链，未观察地图本就不广播、无需入图注册。

        GCMovement.enable(bot);
        GCMovement.move(bot, 400, 100);

        BotMovementState st = null;
        for (BotMovementState s : GCMovement.enabledStates()) {
            if (s.bot.getId() == bot.getId()) {
                st = s;
            }
        }
        assertNotNull(st, "GCMovement.enable 未创建移动状态");

        int startX = bot.getPosition().x;
        for (int i = 0; i < 240 && Math.abs(bot.getPosition().x - 400) > 4; i++) {
            BotMovementManager.tickGrounded(st, new Point(400, 100));
        }
        int endX = bot.getPosition().x;
        System.out.println("[MoveExec] startX=" + startX + " endX=" + endX + " target=400");
        assertTrue(endX > startX + 20, "bot 位置未向右推进（start=" + startX + ", end=" + endX + "）");
        assertTrue(Math.abs(endX - 400) <= 30, "bot 未到达目标附近（STOP_DIST 内即停，end=" + endX + "）");

        GCMovement.disable(bot);
    }
}
