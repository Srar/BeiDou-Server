package org.gms.server.maps;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.inventory.Pet;
import org.gms.constants.id.MapId;
import org.gms.net.server.Server;
import org.gms.net.server.world.World;
import org.gms.server.TimerManager;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.EventSubscriber;
import org.gms.server.bot.event.EventType;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.maps.MapObjectType;
import org.gms.test.BotTestSupport;
import org.gms.util.PacketCreator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * MAP_ENTERED 发布判定（shouldPublishMapEntered）+ 真实 {@link MapleMap#addPlayer(Character)} 冒烟。
 * <p>
 * 此前 bot 相关测试全部 mock 掉 Character/Client/MapleMap，真实对象对 headless botClient 的
 * 隐藏依赖（player=null NPE）成为盲区。本类用真实 MapleMap 实例跑 addPlayer 全路径，只 mock
 * 沿途的静态单例（Server/TimerManager）与 Character（无法轻量构造）：
 * <ul>
 *   <li>bot 区段 id 落图：不抛异常、不发布 MAP_ENTERED（核心防御点）；</li>
 *   <li>真实玩家 id 落图：不抛异常、恰好发布一条携带正确路由信息的 MAP_ENTERED。</li>
 * </ul>
 * GameConfig 静态初始化依赖 Spring 容器，因此 @BeforeAll 必须先注入 mock ApplicationContext。
 */
class MapleMapBotPublishTest {

    private static final int WORLD = 0;
    private static final int CHANNEL = 1;
    private static final int MAP_ID = 100000000; // Henesys：普通地图，避开船/竞技场/CPQ/迷你副本等特殊分支
    private static final long FIXED_SERVER_TIME = 1_000_000L;

    private static final class MapEnteredCollector implements EventSubscriber {
        final List<GameEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(GameEvent event) {
            events.add(event);
        }

        @Override
        public boolean matchesFilter(GameEvent event) {
            return event.getType() == EventType.MAP_ENTERED;
        }
    }

    private MapleMap map;
    private Character chr;
    private BotClient botClient;
    private MapEnteredCollector collector;
    private MockedStatic<Server> serverStatic;
    private MockedStatic<TimerManager> timerStatic;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        // MonsterAggroCoordinator 实例字段初始化就会调 Server.getInstance().getCurrentTime()，
        // 因此 mockStatic(Server) 必须先于 new MapleMap。
        serverStatic = Mockito.mockStatic(Server.class);
        Server serverMock = Mockito.mock(Server.class);
        when(serverMock.getCurrentTime()).thenReturn(FIXED_SERVER_TIME);
        when(serverMock.getWorld(anyInt())).thenReturn(Mockito.mock(World.class));
        serverStatic.when(Server::getInstance).thenReturn(serverMock);

        // addPlayer 首个玩家进图会启动 itemMonitor/aggroMonitor（真实线程池与 GameConfig 周期），
        // 与 MAP_ENTERED 发布无关——mock 掉，避免后台任务在测试 JVM 里存活。
        timerStatic = Mockito.mockStatic(TimerManager.class);
        TimerManager timerManagerMock = Mockito.mock(TimerManager.class);
        // raw 类型：register 返回 ScheduledFuture<?>，泛型 capture 无法匹配 ScheduledFuture<Object>
        @SuppressWarnings({"rawtypes", "unchecked"})
        ScheduledFuture future = Mockito.mock(ScheduledFuture.class);
        when(timerManagerMock.register(any(), anyLong(), anyLong())).thenReturn(future);
        timerStatic.when(TimerManager::getInstance).thenReturn(timerManagerMock);

        map = new MapleMap(MAP_ID, WORLD, CHANNEL, MapId.NONE, 1.0f);
        map.setOnFirstUserEnter(""); // 空串 → 跳过 MapScriptManager 分支（否则 null.length() NPE）
        map.setOnUserEnter("");

        botClient = new BotClient(WORLD, CHANNEL);
        chr = Mockito.mock(Character.class);
        when(chr.getClient()).thenReturn(botClient);
        when(chr.getWorld()).thenReturn(WORLD);
        when(chr.getParty()).thenReturn(null);
        when(chr.getPets()).thenReturn(new Pet[0]);
        when(chr.getPosition()).thenReturn(new Point(0, 0));
        when(chr.getObjectId()).thenReturn(12345);

        collector = new MapEnteredCollector();
        BotEventBus.getInstance().subscribe(EventType.MAP_ENTERED, collector);
    }

    @AfterEach
    void tearDown() {
        BotEventBus.getInstance().reset();
        timerStatic.close();
        serverStatic.close();
    }

    @Test
    void shouldPublishMapEnteredIsTrueForRealPlayerIds() {
        for (int id : new int[]{42, 999999, 1_999_999_999, 0}) {
            when(chr.getId()).thenReturn(id);
            assertTrue(MapleMap.shouldPublishMapEntered(chr),
                    "real player id " + id + " must publish MAP_ENTERED");
        }
    }

    @Test
    void shouldPublishMapEnteredIsFalseForBotIds() {
        when(chr.getId()).thenReturn(2_000_000_001);
        assertFalse(MapleMap.shouldPublishMapEntered(chr),
                "bot id 2000000001 must not publish MAP_ENTERED");
    }

    @Test
    void realBotAddPlayerRunsWithoutExceptionAndPublishesNothing() {
        when(chr.getId()).thenReturn(2_000_000_001);

        // 核心冒烟：真实 MapleMap.addPlayer 全路径对 bot 区段 id 不得抛异常（此前盲区：
        // 全部 mock 的测试永远走不到真实对象对 headless client 的隐藏依赖），且不发布事件。
        map.addPlayer(chr);

        assertTrue(collector.events.isEmpty(), "bot landing on map must not publish MAP_ENTERED");
    }

    @Test
    void realPlayerAddPlayerPublishesExactlyOneMapEnteredEvent() {
        when(chr.getId()).thenReturn(42);

        map.addPlayer(chr);

        assertEquals(1, collector.events.size(), "real player landing must publish exactly one MAP_ENTERED");
        GameEvent event = collector.events.get(0);
        assertEquals(EventType.MAP_ENTERED, event.getType());
        assertEquals(WORLD, event.getWorld());
        assertEquals(CHANNEL, event.getChannel());
        assertEquals(MAP_ID, event.getMapId());
        assertEquals(42, event.getSourceCharacterId());
    }

    @Test
    void ghostCleanupSkipsBots() {
        // 回归防线：bot 的 awayFromWorld 语义与真实玩家不同，曾被 cleanupGhostPlayers
        // 当「断线未移除的幽灵玩家」误杀（落图即被移除）。
        when(chr.getId()).thenReturn(2_000_000_001);
        when(chr.isAwayFromWorld()).thenReturn(true); // 真实玩家掉线态；bot 不应因此被清
        map.addPlayer(chr);

        // 真实 Character（getDefault 内存构造，BotTestSupport 已让 GameConfig 可用）：
        // 第二个玩家进图会对图上 bot 广播 spawn 包，包构造需要完整角色字段，
        // mock 角色会因 getName()==null 等在 writeString 处 NPE。
        Character realPlayer = Character.getDefault(botClient);
        realPlayer.setId(42);
        realPlayer.setName("RealPlayer42");
        realPlayer.setMap(map); // 生产路径（登录/换图）在 addPlayer 前由调用方设 map 字段

        // spawn 包构造（addCharEquips）会触达 ItemInformationProvider 单例——其静态初始化
        // 直连数据库且构造器无法被 Mockito instrument。改为拦截它的上层调用点
        // PacketCreator.spawnPlayerMapObject（纯静态工具类，无静态初始化依赖）：
        // 本用例只验证幽灵清理跳过 bot，不关心 spawn 包内容。
        try (MockedStatic<PacketCreator> pcStatic = Mockito.mockStatic(PacketCreator.class)) {
            pcStatic.when(() -> PacketCreator.spawnPlayerMapObject(any(), any(), anyBoolean())).thenReturn(null);
            map.addPlayer(realPlayer); // 触发 cleanupGhostPlayers：bot 必须被跳过
        }

        assertTrue(map.getCharacters().contains(chr),
                "bot must survive ghost cleanup even when awayFromWorld is true");
        assertTrue(map.getCharacters().contains(realPlayer));
    }

    @Test
    void sendObjectPlacementSkipsHeadlessClient() {
        // 回归防线：图上存在掉落物时，sendObjectPlacement 若给无头客户端发送对象放置包，
        // MapItem.sendSpawnData 内部 client.getPlayer() 为 null 会 NPE 导致 createBot 回滚。
        MapItem item = Mockito.mock(MapItem.class);
        when(item.getType()).thenReturn(MapObjectType.ITEM);
        map.addMapObject(item);

        when(chr.getId()).thenReturn(2_000_000_001);
        map.addPlayer(chr); // headless botClient：不得触达任何 MapObject.sendSpawnData

        Mockito.verify(item, Mockito.never()).sendSpawnData(any());
    }
}
