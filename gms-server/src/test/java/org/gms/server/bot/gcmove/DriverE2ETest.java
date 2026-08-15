package org.gms.server.bot.gcmove;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.TimerManager;
import org.gms.server.bot.town.TownStation;
import org.gms.server.bot.wander.BotWanderSystem;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端：enable + move 之后不做任何手动 tick 调用，交给 GCMovementDriver 的
 * 50ms 自调度池跑真实 tick()（含 maybeRefreshProfile/resolveTarget/stepMovementCore/
 * 导航图空处理/未观察降频 250ms 等生产全部分支），断言 bot 在数秒内自行位移。
 */
public class DriverE2ETest {

    private static final int MAP_ID = 910000002;
    private MockedStatic<Server> serverMock;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
        TimerManager.getInstance().start();
    }

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
    public void realDriverMovesBotWithoutManualTicks() throws Exception {
        MapleMap map = makeFlatMap();
        Character bot = Character.getDefault(new BotClient(0, 1));
        bot.setId(2_000_000_600);
        bot.setMap(map);
        bot.setPosition(new Point(0, 100));

        GCMovement.enable(bot);
        GCMovement.move(bot, 400, 100);

        int startX = bot.getPosition().x;
        // 未观察图（无真人）→ 有任务时 250ms 节奏；给足 4 秒让真实调度链自行推进
        Thread.sleep(4000);
        int endX = bot.getPosition().x;
        System.out.println("[DriverE2E] startX=" + startX + " endX=" + endX + " target=400");
        assertTrue(endX > startX + 20,
                "真实驱动链未自行推进 bot（start=" + startX + ", end=" + endX + "）");

        GCMovement.disable(bot);
    }

    @Test
    public void wanderSystemMovesBotAutonomously() throws Exception {
        MapleMap map = makeFlatMap();
        Character bot = Character.getDefault(new BotClient(0, 1));
        bot.setId(2_000_000_601);
        bot.setMap(map);
        bot.setPosition(new Point(0, 100));

        int startX = bot.getPosition().x;
        BotWanderSystem.start(bot);
        // 起步抖动<=1.5s + 选点 + 移动，8 秒内应发生实际位移（或已在移动中）
        Thread.sleep(8000);
        boolean moved = bot.getPosition().x != startX;
        System.out.println("[WanderE2E] startX=" + startX + " endX=" + bot.getPosition().x
                + " moving=" + GCMovement.isMoving(bot) + " wandering=" + BotWanderSystem.isWandering(bot));
        assertTrue(moved || GCMovement.isMoving(bot),
                "游荡系统未产生任何移动（start=" + startX + ", end=" + bot.getPosition().x + "）");
        BotWanderSystem.stop(bot);
        GCMovement.disable(bot);
    }

    @Test
    public void townStationRelocateChainMovesBot() throws Exception {
        MapleMap map = makeFlatMap();
        Character bot = Character.getDefault(new BotClient(0, 1));
        bot.setId(2_000_000_602);
        bot.setMap(map);
        bot.setPosition(new Point(0, 100));

        GCMovement.enable(bot);
        // SocialBot 的漂移链依赖导航图采样；同步烘焙合成图（真实生产为 enable 异步 warm，1.2s 级）
        BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());

        int startX = bot.getPosition().x;
        boolean relocated = TownStation.relocate(bot, new Point(0, 100));
        System.out.println("[RelocateE2E] relocate issued=" + relocated);
        Thread.sleep(5000);
        int endX = bot.getPosition().x;
        System.out.println("[RelocateE2E] startX=" + startX + " endX=" + endX);
        assertTrue(relocated, "TownStation.relocate 未发出移动（采样为空）");
        assertTrue(endX != startX || GCMovement.isMoving(bot),
                "relocate 链未产生移动（start=" + startX + ", end=" + endX + "）");
        TownStation.releaseSpot(bot);
        GCMovement.disable(bot);
    }
}
