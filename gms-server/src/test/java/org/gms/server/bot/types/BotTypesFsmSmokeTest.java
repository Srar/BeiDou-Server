package org.gms.server.bot.types;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.TimerManager;
import org.gms.server.bot.BotGeneration;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.BotTickService;
import org.gms.server.bot.BotTypeManager;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.gcmove.LodCounts;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
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
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bot 类型宏 FSM 冒烟：离线驱动 TrainingBot/SocialBot 的 updateState 多拍，
 * 验证 BotSM tick 层不抛异常且 FSM 能离开 INIT（排除"宏脑卡死"这一最后未验证因素）。
 */
public class BotTypesFsmSmokeTest {

    private static final int MAP_ID = 910000002;
    private MockedStatic<Server> serverMock;
    private MockedStatic<DatabaseConnection> dbMock;

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

        // 钉死线性扫描回退（未观察）
        lodMock = Mockito.mockStatic(LodCounts.class);
        lodMock.when(LodCounts::trackerRunning).thenReturn(false);
    }

    private MockedStatic<LodCounts> lodMock;

    @AfterEach
    void tearDown() {
        if (serverMock != null) serverMock.close();
        if (dbMock != null) dbMock.close();
        if (lodMock != null) lodMock.close();
    }

    @SuppressWarnings("unchecked")
    private static Object phaseOf(Object bot) {
        try {
            Field f = bot.getClass().getDeclaredField("phase");
            f.setAccessible(true);
            return f.get(bot);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void trainingBotFsmLeavesInitWithoutThrowing() throws Exception {
        MapleMap map = makeFlatMap();
        Character chr = Character.getDefault(new BotClient(0, 1));
        chr.setId(2_000_000_700);
        chr.setMap(map);
        chr.setPosition(new Point(0, 100));

        TrainingBot bot = new TrainingBot(chr);
        BotStorage.addActiveBot(chr.getId(), bot);
        BotTypeManager.manuallyStartBot(chr);

        Object phase = phaseOf(bot);
        System.out.println("[FsmSmoke] TrainingBot initial phase=" + phase);
        for (int i = 0; i < 5; i++) {
            bot.updateState(); // 不抛异常即通过一半
        }
        phase = phaseOf(bot);
        System.out.println("[FsmSmoke] TrainingBot phase after 5 ticks=" + phase);
        assertTrue(!"INIT".equals(String.valueOf(phase)),
                "TrainingBot 宏 FSM 5 拍后仍停在 INIT（可能 tick 异常或 doInit 卡死）");

        bot.setRunning(false);
        bot.stopScheduledTask();
        BotStorage.removeActiveBot(chr.getId());
        BotTickService.unregister(chr.getId());
    }

    @Test
    public void socialBotTicksWithoutThrowing() throws Exception {
        MapleMap map = makeFlatMap();
        Character chr = Character.getDefault(new BotClient(0, 1));
        chr.setId(2_000_000_701);
        chr.setMap(map);
        chr.setPosition(new Point(0, 100));

        SocialBot bot = new SocialBot(chr);
        BotStorage.addActiveBot(chr.getId(), bot);
        BotTypeManager.manuallyStartBot(chr);

        for (int i = 0; i < 3; i++) {
            bot.updateState();
        }
        System.out.println("[FsmSmoke] SocialBot 3 ticks ok; moving=" + GCMovement.isMoving(chr)
                + " enabled=" + GCMovement.isEnabled(chr));
        assertNotNull(bot.getState(), "SocialBot 状态为 null");

        bot.setRunning(false);
        bot.stopScheduledTask();
        BotStorage.removeActiveBot(chr.getId());
        BotTickService.unregister(chr.getId());
        GCMovement.disable(chr);
    }
}
