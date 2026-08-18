package org.gms.server.bot.gcmove;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.bot.replay.MovementCommands;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁协议加固单测（M3）：gcmove 会话与录制回放引擎的移动锁互斥协议。
 * <p>
 * 新语义：
 * <ul>
 *   <li>enable 拿锁失败（锁被录制回放引擎持有）→ 记 warn、不创建动态会话、不 Driver.start；</li>
 *   <li>disable 带 owner 断言：仅当锁由本 gcmove 会话持有时才释放，绝不误放回放引擎的锁。</li>
 * </ul>
 */
public class GCMovementLockProtocolTest {

    private static final int MAP_ID = 910000003;
    private MockedStatic<Server> serverMock;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
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
        // ObserverTracker 观察轮询（enable 会 ensureStarted）走 Server 静态单例；钉死空世界。
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
    void enableDoesNotStartSessionWhenReplayEngineHoldsLock() {
        MapleMap map = makeFlatMap();
        Character bot = Character.getDefault(new BotClient(0, 1));
        bot.setId(2_000_003_001);
        bot.setMap(map);
        bot.setPosition(new Point(0, 100));

        // 模拟录制回放引擎（如 pathFinderBeta）正在驱动该 bot：锁被 replay 持有
        assertTrue(MovementCommands.tryAcquireMovementLock(bot), "replay 引擎拿锁应成功");

        // 锁被占时 enable 不得创建动态会话（M3-a：拿锁失败不 Driver.start）
        GCMovement.enable(bot);
        assertFalse(GCMovement.isEnabled(bot), "锁被回放引擎占用时 enable 不得创建 gcmove 会话");

        // 锁仍归 replay 引擎所有
        assertTrue(MovementCommands.isBotMoving(bot), "enable 拿锁失败不得影响 replay 引擎持有的锁");

        // 以 gcmove owner 释放不得成功（owner 断言：锁不归 gcmove）
        MovementCommands.releaseMovementLock(bot, MovementCommands.LOCK_OWNER_GCMOVE);
        assertTrue(MovementCommands.isBotMoving(bot), "owner 不匹配时 gcmove 释放不得生效");

        // replay 引擎自己释放后，锁空闲，后续 enable 才能建会话
        MovementCommands.releaseMovementLock(bot);
        assertFalse(MovementCommands.isBotMoving(bot));
    }

    @Test
    void disableDoesNotReleaseLockHeldByReplayEngine() {
        MapleMap map = makeFlatMap();
        Character bot = Character.getDefault(new BotClient(0, 1));
        bot.setId(2_000_003_002);
        bot.setMap(map);
        bot.setPosition(new Point(0, 100));

        // 回放引擎持锁、gcmove 无会话：disable 不得误放锁（M3-b）
        assertTrue(MovementCommands.tryAcquireMovementLock(bot));
        GCMovement.disable(bot);
        assertTrue(MovementCommands.isBotMoving(bot), "无 gcmove 会话时 disable 不得释放回放引擎的锁");

        MovementCommands.releaseMovementLock(bot);
        assertFalse(MovementCommands.isBotMoving(bot));
    }

    @Test
    void enableAcquiresLockThenDisableReleasesIt() {
        MapleMap map = makeFlatMap();
        Character bot = Character.getDefault(new BotClient(0, 1));
        bot.setId(2_000_003_003);
        bot.setMap(map);
        bot.setPosition(new Point(0, 100));

        // 正常路径：enable 建会话并持有锁（owner=gcmove）
        GCMovement.enable(bot);
        assertTrue(GCMovement.isEnabled(bot), "锁空闲时 enable 应创建 gcmove 会话");
        assertTrue(MovementCommands.isBotMoving(bot), "enable 后 gcmove 会话应持有移动锁");

        // 会话期间 replay 引擎拿锁失败（互斥成立）
        assertFalse(MovementCommands.tryAcquireMovementLock(bot), "gcmove 会话期间 replay 引擎拿锁应失败");

        // disable 按 owner 校验释放：锁空闲、会话移除
        GCMovement.disable(bot);
        assertFalse(GCMovement.isEnabled(bot));
        assertFalse(MovementCommands.isBotMoving(bot), "disable 后移动锁应释放");

        // 释放后 replay 引擎可正常拿锁
        assertTrue(MovementCommands.tryAcquireMovementLock(bot));
        MovementCommands.releaseMovementLock(bot);
    }
}
