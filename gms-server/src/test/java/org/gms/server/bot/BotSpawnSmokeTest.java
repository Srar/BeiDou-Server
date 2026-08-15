package org.gms.server.bot;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.constants.id.MapId;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.TimerManager;
import org.gms.server.bot.decorate.BotDecorate;
import org.gms.server.bot.decorate.BotDecorateEquips;
import org.gms.server.bot.decorate.BotDecorateNX;
import org.gms.server.bot.decorate.QuickEquip;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.gcmove.GCMovement;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * Bot 生成冒烟测试：把此前漏掉的三条运行时不变量固化为防回归断言。
 * <p>
 * 与 {@code MapleMapBotPublishTest} 同风格：mock 静态单例（Server/TimerManager），但使用真实
 * {@link MapleMap} + 真实 {@code Character.getDefault(Client)} 跑 {@link BotGeneration#createBot} 全路径。
 * <ol>
 *   <li><b>装饰不变量</b>：{@link BotGeneration#createBot} 必须调用装饰器（此前 createBot 漏调装饰
 *       → bot 无装备）。真实 {@link BotDecorate#setBotVariables(Character)} 单独验证其生成非初心者
 *       职业、等级落在 [10,80]。装备落库（WEAPON 槽）依赖 {@code ItemInformationProvider} 单例，其
 *       静态初始化直连 DB/WZ，离线单测无法实例化（与 {@code MapleMapBotPublishTest} 规避该单例同理），
 *       故此处以装饰器调用记录 + 真实等级/职业生成为断言面。</li>
 *   <li><b>移动引擎启动不变量</b>：SOCIAL_BOT 注册 + manuallyStartBot + {@link GCMovement#enable(Character)}
 *       之后，移动引擎已启用、观察轮已启动、tick 轮已注册（此前 spawn 不 enable → bot 不动）。</li>
 *   <li><b>出生点分散不变量</b>：{@link BotHelpers#pickGroundSpots} 在真实地图上返回 5 个 X 互不相同的点
 *       （此前批量 bot 全部挤在同一个 portal 出生点）。</li>
 * </ol>
 */
class BotSpawnSmokeTest {

    private static final int WORLD = 0;
    private static final int CHANNEL = 1;
    private static final int MAP_ID = 100000000; // Henesys：普通地图，避开船/竞技场/CPQ 等特殊分支
    private static final long FIXED_SERVER_TIME = 1_000_000L;
    private static final Point SPAWN = new Point(500, 50);

    private MapleMap map;
    private Character createdBot;
    private MockedStatic<Server> serverStatic;
    private MockedStatic<TimerManager> timerStatic;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        // MonsterAggroCoordinator 实例字段初始化就调 Server.getInstance().getCurrentTime()，
        // 因此 mockStatic(Server) 必须先于 new MapleMap（与 MapleMapBotPublishTest 一致）。
        serverStatic = Mockito.mockStatic(Server.class);
        Server serverMock = Mockito.mock(Server.class);
        World worldMock = Mockito.mock(World.class);
        Channel channelMock = Mockito.mock(Channel.class);
        PlayerStorage storageMock = Mockito.mock(PlayerStorage.class);
        Mockito.lenient().when(serverMock.getCurrentTime()).thenReturn(FIXED_SERVER_TIME);
        Mockito.lenient().when(serverMock.getWorld(anyInt())).thenReturn(worldMock);
        Mockito.lenient().when(serverMock.getChannel(anyInt(), anyInt())).thenReturn(channelMock);
        Mockito.lenient().when(worldMock.getPlayerStorage()).thenReturn(storageMock);
        serverStatic.when(Server::getInstance).thenReturn(serverMock);

        // 首个玩家进图会启动 itemMonitor/aggroMonitor（真实线程池 + GameConfig 周期），
        // mock 掉避免后台任务在测试 JVM 存活（与 MapleMapBotPublishTest 一致）。
        // 注意：mockStatic 只对当前线程生效；createBot 的出生编排跑在 BotExecutors 的虚拟线程上，
        // 那里的 TimerManager.getInstance() 会落回真实单例。先启动真实调度器，避免虚拟线程里
        // TimerManager.schedule 对未启动的 ses 做 NPE。
        TimerManager.getInstance().start();
        timerStatic = Mockito.mockStatic(TimerManager.class);
        TimerManager timerManagerMock = Mockito.mock(TimerManager.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ScheduledFuture future = Mockito.mock(ScheduledFuture.class);
        Mockito.lenient().when(timerManagerMock.register(any(), anyLong(), anyLong())).thenReturn(future);
        timerStatic.when(TimerManager::getInstance).thenReturn(timerManagerMock);

        map = new MapleMap(MAP_ID, WORLD, CHANNEL, MapId.NONE, 1.0f);
        map.setOnFirstUserEnter(""); // 空串 → 跳过 MapScriptManager 分支
        map.setOnUserEnter("");
        map.setFootholds(flatGround());

        // createBot 走生产 DefaultBotServerAccess（经 mock 的 Server 单例）。
        BotGeneration.setServerAccess(DefaultBotServerAccess.INSTANCE);
    }

    @AfterEach
    void tearDown() {
        // 先停移动引擎 tick 循环，再清理 FSM / tick 轮 / 事件总线。
        if (createdBot != null) {
            GCMovement.disable(createdBot);
        }
        for (int id : new ArrayList<>(BotStorage.getAllBots().keySet())) {
            BotSM sm = BotStorage.getBotById(id);
            if (sm != null) {
                sm.stopScheduledTask();
            }
            BotTickService.unregister(id);
            BotStorage.removeActiveBot(id);
        }
        BotEventBus.getInstance().reset();

        // 恢复生产默认：装饰、基底角色工厂、serverAccess、装饰开关。
        BotGeneration.setDecorator(null);
        BotGeneration.setBaseCharacterSupplier(null);
        BotGeneration.setServerAccess(DefaultBotServerAccess.INSTANCE);
        QuickEquip.ENABLED = true;
        BotDecorateNX.ENABLED = true;

        timerStatic.close();
        serverStatic.close();
    }

    @Test
    void createBotInvokesDecoratorWithBotAndParams() {
        // 回归防线（核心）：此前 createBot 完全漏掉装饰调用。这里注入记录型装饰器，
        // 断言 createBot 确实把生成的 bot 与默认参数交给装饰器（等价 6 参默认 0,0,0,0）。
        BotClient botClient = new BotClient(WORLD, CHANNEL);
        createdBot = Character.getDefault(botClient);
        disablePartySearchInvite(createdBot);
        BotGeneration.setBaseCharacterSupplier(() -> createdBot);

        AtomicReference<Character> decorated = new AtomicReference<>();
        AtomicReference<int[]> params = new AtomicReference<>();
        BotGeneration.setDecorator((bot, baseClass, minLevel, maxLevel, forcedJobId) -> {
            decorated.set(bot);
            params.set(new int[]{baseClass, minLevel, maxLevel, forcedJobId});
        });

        int botId = BotGeneration.createBot(SPAWN, map);

        assertEquals(botId, createdBot.getId());
        assertNotNull(decorated.get(), "createBot must invoke the decorator");
        assertEquals(createdBot, decorated.get(), "decorator must receive the created bot");
        assertArrayEquals(new int[]{0, 0, 0, 0}, params.get(),
                "createBot(pos,map) must delegate to the decorator with default params");
    }

    @Test
    void realBotDecorateSetsNonBeginnerJobAndLevelRange() {
        // 回归防线：真实 BotDecorate 的等级/职业生成不变量（非初心者、level ∈ [10,80]）。
        // 装备/NX 落库最终都经 ItemInformationProvider 单例（静态初始化直连 DB/WZ），离线单测
        // 无法实例化——关闭 QuickEquip/NX 并 mock 掉 BotDecorateEquips，仅验证真实变量生成路径。
        QuickEquip.ENABLED = false;
        BotDecorateNX.ENABLED = false;

        BotClient botClient = new BotClient(WORLD, CHANNEL);
        createdBot = Character.getDefault(botClient);

        try (MockedStatic<BotDecorateEquips> equipsStatic = Mockito.mockStatic(BotDecorateEquips.class)) {
            BotDecorate.setBotVariables(createdBot);

            assertNotEquals(0, createdBot.getJob().getId(), "bot must be decorated out of beginner job");
            assertTrue(createdBot.getLevel() >= 10 && createdBot.getLevel() <= 80,
                    "bot level must be within [10,80], got " + createdBot.getLevel());
        }
    }

    @Test
    void spawnRegistersAndEnablesMovementEngine() {
        BotClient botClient = new BotClient(WORLD, CHANNEL);
        createdBot = Character.getDefault(botClient);
        disablePartySearchInvite(createdBot);
        BotGeneration.setBaseCharacterSupplier(() -> createdBot);
        // 本不变量只关心「注册 + 启动 + enable」；装饰用注入接缝屏蔽，避免与本用例无关的静态副作用。
        BotGeneration.setDecorator((bot, baseClass, minLevel, maxLevel, forcedJobId) -> { });

        int botId = BotGeneration.createBot(SPAWN, map);

        BotTypeManager.BotType.SOCIAL_BOT.createAndSetBot(createdBot);
        BotTypeManager.manuallyStartBot(createdBot);
        GCMovement.enable(createdBot);

        // enabledStates() 是包私有（gcmove 包），此处用公开等价 isEnabled() 断言「STATES 含该 bot」。
        assertTrue(GCMovement.isEnabled(createdBot), "spawned bot must be under GCMovement control after enable");
        assertTrue(observerTrackerStarted(), "GCMovement.enable must start the observer poll");
        assertTrue(BotTickService.isRegistered(botId), "started bot must be registered on the tick wheel");
    }

    @Test
    void pickGroundSpotsSpreadsBotsAcrossDistinctX() {
        List<Point> spots = BotHelpers.pickGroundSpots(map, SPAWN, 5);

        assertEquals(5, spots.size(), "must produce exactly the requested number of spawn spots");
        Set<Integer> xs = new HashSet<>();
        for (Point spot : spots) {
            assertNotNull(spot);
            xs.add(spot.x);
        }
        assertEquals(5, xs.size(), "spawn spots must have distinct X coordinates: " + spots);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static FootholdTree flatGround() {
        FootholdTree tree = new FootholdTree(new Point(0, 0), new Point(2000, 200));
        tree.insert(new Foothold(new Point(0, 100), new Point(2000, 100), 1));
        return tree;
    }

    /**
     * setEnteredChannelWorld 在 canRecvPartySearchInvite=true 时会触达
     * World.getPartySearchCoordinator()，而 PartySearchCoordinator 的静态初始化加载 WZ 邻接图
     * （离线单测不可用）。把该私有字段置 false，让 createBot 跳过该分支——与装饰/移动无关。
     */
    private static void disablePartySearchInvite(Character chr) {
        try {
            Field f = Character.class.getDeclaredField("canRecvPartySearchInvite");
            f.setAccessible(true);
            f.setBoolean(chr, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to disable canRecvPartySearchInvite", e);
        }
    }

    /** 反射读取包私有 {@code ObserverTracker.started()}（gcmove 包）。 */
    private static boolean observerTrackerStarted() {
        try {
            Class<?> clazz = Class.forName("org.gms.server.bot.gcmove.ObserverTracker");
            Method started = clazz.getDeclaredMethod("started");
            started.setAccessible(true);
            return (boolean) started.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to read ObserverTracker.started()", e);
        }
    }
}
