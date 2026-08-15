package org.gms.server.bot.gcmove;

import org.gms.client.BotClient;
import org.gms.client.Character;
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
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 观察态压测：真实野外图（含怪）+ markObservedNow（模拟玩家在场）+
 * 反射直调真实 GCMovementDriver.tick 覆盖全部生产分支——任何 NPE 同步带堆栈暴露。
 */
public class ObservedTickStressTest {

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

    @Test
    public void observedTickOnRealFieldMapDoesNotThrow() throws Exception {
        // 蚂蚁洞Ⅰ：26 只真实 WZ 怪（覆盖接触伤害/索敌路径）
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(105050000);
        BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());

        Character bot = Character.getDefault(new BotClient(0, 1));
        bot.setId(2_000_000_900);
        bot.setMap(map);
        Point spawn = map.getPortal(0) != null ? map.getPortal(0).getPosition() : new Point(0, 0);
        bot.setPosition(spawn);

        GCMovement.enable(bot);
        Point target = map.getPointBelow(new Point(spawn.x + 300, spawn.y + 5));
        if (target != null) {
            GCMovement.move(bot, target.x, target.y);
        }

        Method tick = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
        tick.setAccessible(true);
        BotMovementState st = null;
        for (BotMovementState s : GCMovement.enabledStates()) {
            if (s.bot.getId() == bot.getId()) st = s;
        }
        assertTrue(st != null, "移动状态未创建");

        int startX = bot.getPosition().x;
        for (int i = 0; i < 100; i++) {
            ObserverTracker.markObservedNow(map.getId()); // 维持观察态（TTL 2s，循环内刷新）
            tick.invoke(null, st); // 异常将直接抛出并带完整堆栈
        }
        int endX = bot.getPosition().x;
        System.out.println("[ObservedStress] startX=" + startX + " endX=" + endX
                + " moving=" + GCMovement.isMoving(bot));
        assertTrue(endX != startX || GCMovement.isMoving(bot),
                "观察态 tick 100 拍后 bot 未移动（start=" + startX + ", end=" + endX + "）");
        GCMovement.disable(bot);
    }
}
