package org.gms.server.bot.gcmove;

import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.life.Monster;
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

/** 离线验证：批量加载用户日志中的野外图，统计 null-stats 怪（NPE 假设证实/排除）。 */
public class MobDataIntegrityTest {

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
    public void scanFieldMapsForNullStatsMonsters() {
        int[] maps = {105050000, 105040000, 100040000, 106010000, 105050100, 220050300, 211040300, 105070001};
        for (int mapId : maps) {
            try {
                MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
                int nullStats = 0, total = 0;
                for (var mo : map.getAllMonsters()) {
                    total++;
                    if (((Monster) mo).getStats() == null) nullStats++;
                }
                System.out.println("[MobIntegrity] map " + mapId + ": monsters=" + total
                        + " nullStats=" + nullStats);
            } catch (Throwable t) {
                System.out.println("[MobIntegrity] map " + mapId + " LOAD FAILED: " + t);
            }
        }
    }
}
