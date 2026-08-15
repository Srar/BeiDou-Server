package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.client.SkinColor;
import org.gms.server.bot.types.IdleBot;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.awt.Point;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * BotGeneration：ID 分配（唯一、base 区间、并发安全）、创建计数、
 * 注册顺序（channel/world → 地图）、销毁清理顺序、基底角色工厂注入。
 * 全部经 FakeBotServerAccess + mock Character/MapleMap，不触碰 Server 单例与数据库。
 */
class BotGenerationTest {

    private static class FakeBotServerAccess implements BotServerAccess {
        final AtomicInteger addCalls = new AtomicInteger();
        final AtomicInteger removeCalls = new AtomicInteger();
        final ConcurrentLinkedQueue<Character> added = new ConcurrentLinkedQueue<>();

        @Override
        public void addBotToServer(Character bot) {
            addCalls.incrementAndGet();
            added.add(bot);
        }

        @Override
        public void removeBotFromServer(Character bot) {
            removeCalls.incrementAndGet();
        }

        @Override
        public MapleMap getMap(int world, int channel, int mapId) {
            return mock(MapleMap.class);
        }

        @Override
        public Character getCharacterById(int characterId) {
            return null;
        }

        @Override
        public boolean isNameTaken(String name) {
            return false;
        }
    }

    private FakeBotServerAccess fakeAccess;
    private MapleMap map;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        fakeAccess = new FakeBotServerAccess();
        BotGeneration.setServerAccess(fakeAccess);
        BotGeneration.setBaseCharacterSupplier(BotGenerationTest::newBaseCharacter);
        map = mock(MapleMap.class);
        // createBot 现在会走装饰管线（baseClass<=0 -> setBotVariables）；本类只测生命周期，
        // 装饰是 mock Character 无法承载的静态副作用，用注入接缝屏蔽（跨线程安全，装饰有独立语义）。
        BotGeneration.setDecorator((bot, baseClass, minLevel, maxLevel, forcedJobId) -> { });
    }

    @AfterEach
    void tearDown() {
        BotGeneration.setDecorator(null); // null = 恢复生产默认
        BotGeneration.setServerAccess(DefaultBotServerAccess.INSTANCE);
        BotGeneration.setBaseCharacterSupplier(null); // null = 恢复生产默认
    }

    private static Character newBaseCharacter() {
        return mock(Character.class);
    }

    @Test
    void createBotAllocatesUniqueIdsInBaseRange() {
        int n = 30;
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < n; i++) {
            int botId = BotGeneration.createBot(new Point(0, 0), map);
            assertTrue(botId > BotHelpers.BOT_BASE_ID, "bot id must be above base: " + botId);
            ids.add(botId);
        }
        assertEquals(n, ids.size(), "ids must be unique");
    }

    @Test
    void createBotRegistersAndPlacesOnMap() {
        BotGeneration.createBot(new Point(0, 0), map);

        assertEquals(1, fakeAccess.addCalls.get(), "bot must be registered to channel/world once");
        verify(map, times(1)).addPlayer(any());
    }

    @Test
    void createBotRegistersBeforePlacingOnMap() {
        // 注册顺序是生命周期不变量：先 channel/world 存储，后落图（参考实现顺序）。
        // 两个动作跨对象（Fake 与 map mock），用共享日志记录调用顺序断言先后。
        List<String> order = new CopyOnWriteArrayList<>();
        BotGeneration.setServerAccess(new FakeBotServerAccess() {
            @Override
            public void addBotToServer(Character bot) {
                order.add("register");
                super.addBotToServer(bot);
            }
        });
        Mockito.doAnswer(inv -> {
            order.add("map");
            return null;
        }).when(map).addPlayer(Mockito.any());

        BotGeneration.createBot(new Point(0, 0), map);

        assertEquals(List.of("register", "map"), order,
                "channel/world registration must happen before placing on map");
    }

    @Test
    void createBotSetsIdentityFields() {
        Character bot = newBaseCharacter();
        BotGeneration.setBaseCharacterSupplier(() -> bot);

        int botId = BotGeneration.createBot(new Point(0, 0), map);

        // 若实现漏调 setWorld/setName/setClient（mock 静默吸收），这些断言会拦住
        Mockito.verify(bot).setId(botId);
        Mockito.verify(bot).setName(Mockito.anyString());
        Mockito.verify(bot).setClient(Mockito.any());
        Mockito.verify(bot).setWorld(Mockito.anyInt());
        Mockito.verify(bot).setFame(botId);
    }

    @Test
    void createBotRollsBackRegistrationOnMapFailure() {
        // 落图抛异常：注册必须被回滚（channel/world 移除），异常向上传播
        Character bot = newBaseCharacter();
        BotGeneration.setBaseCharacterSupplier(() -> bot);
        Mockito.doThrow(new IllegalStateException("addPlayer boom")).when(map).addPlayer(Mockito.any());

        assertThrows(IllegalStateException.class,
                () -> BotGeneration.createBot(new Point(0, 0), map));

        assertEquals(1, fakeAccess.addCalls.get());
        assertEquals(1, fakeAccess.removeCalls.get(), "failed registration must be rolled back");
    }

    @Test
    void createBotGroundsAndStandsBot() {
        // 回归防线（「卡在空中不动 / 浮空姿势」）：出生坐标必须修正到脚下地面、
        // stance 必须用站立帧 4/5（0 会被客户端渲染成悬空姿势）、落图后必须补发
        // 一次 MOVE_PLAYER 站立包（客户端只对「有后续移动包」的角色完成进图落地）
        Character bot = newBaseCharacter();
        BotGeneration.setBaseCharacterSupplier(() -> bot);
        Mockito.when(map.getPointBelow(Mockito.any())).thenReturn(new Point(5, 10));

        BotGeneration.createBot(new Point(3, 7), map);

        Mockito.verify(bot).setPosition(new Point(5, 10));
        ArgumentCaptor<Integer> stanceCaptor = ArgumentCaptor.forClass(Integer.class);
        Mockito.verify(bot).setStance(stanceCaptor.capture());
        Mockito.verify(bot).broadcastStance(stanceCaptor.getValue());
        assertTrue(stanceCaptor.getValue() == 4 || stanceCaptor.getValue() == 5,
                "stance must be a standing frame (4 or 5), got " + stanceCaptor.getValue());
    }

    @Test
    void createBotKeepsOriginalPositionWhenNoFootholdBelow() {
        // getPointBelow 在下方无 foothold 时返回 null：保留原坐标，不得回退到 (0,0)
        Character bot = newBaseCharacter();
        BotGeneration.setBaseCharacterSupplier(() -> bot);
        Mockito.when(map.getPointBelow(Mockito.any())).thenReturn(null);

        BotGeneration.createBot(new Point(3, 7), map);

        Mockito.verify(bot).setPosition(new Point(3, 7));
        Mockito.verify(bot).setStance(Mockito.anyInt());
        Mockito.verify(bot).broadcastStance(Mockito.anyInt());
    }

    @Test
    void createBotMarksEnteredChannelWorld() {
        // 回归防线：不标记进入频道世界（awayFromWorld 保持 true），
        // MapleMap.cleanupGhostPlayers 会把 bot 当断线幽灵误杀
        Character bot = newBaseCharacter();
        BotGeneration.setBaseCharacterSupplier(() -> bot);

        BotGeneration.createBot(new Point(0, 0), map);

        Mockito.verify(bot).setEnteredChannelWorld();
    }

    @Test
    void applyAppearanceUsesValuesFromPools() {
        // 回归防线：face/hair=0 是 WZ 中不存在的 id，spawn 包会崩客户端——
        // 外观必须来自注入的合法池
        Character bot = newBaseCharacter();
        Set<Integer> faces = Set.of(20000, 20001, 20002);
        Set<Integer> hairs = Set.of(30000, 30030);
        Set<Integer> skins = Set.of(0, 1, 2);

        BotGeneration.applyAppearance(bot, true, faces, hairs, skins);

        Mockito.verify(bot).setGender(0);
        ArgumentCaptor<Integer> faceCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> hairCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<SkinColor> skinCaptor = ArgumentCaptor.forClass(SkinColor.class);
        Mockito.verify(bot).setFace(faceCaptor.capture());
        Mockito.verify(bot).setHair(hairCaptor.capture());
        Mockito.verify(bot).setSkinColor(skinCaptor.capture());

        assertTrue(faces.contains(faceCaptor.getValue()), "face must come from the pool: " + faceCaptor.getValue());
        assertTrue(hairs.contains(hairCaptor.getValue()), "hair must come from the pool: " + hairCaptor.getValue());
        assertTrue(skins.contains(skinCaptor.getValue().getId()), "skin must come from the pool");
    }

    @Test
    void applyAppearanceFallsBackWhenPoolsEmpty() {
        Character bot = newBaseCharacter();

        BotGeneration.applyAppearance(bot, false, Set.of(), Set.of(), Set.of());

        Mockito.verify(bot).setGender(1);
        Mockito.verify(bot).setFace(20000);
        Mockito.verify(bot).setHair(30000);
        Mockito.verify(bot).setSkinColor(SkinColor.getById(0));
    }

    @Test
    void randomPickFallsBackOnEmptyPool() {
        assertEquals(20000, BotGeneration.randomPick(Set.of(), 20000));
        assertEquals(7, BotGeneration.randomPick(null, 7));
        Set<Integer> pool = Set.of(42);
        assertEquals(42, BotGeneration.randomPick(pool, 7), "non-empty pool must return its member");
    }

    @Test
    void botsCreatedCountTracksCreations() {
        int before = BotGeneration.getBotsCreatedCount();
        int n = 5;
        for (int i = 0; i < n; i++) {
            BotGeneration.createBot(new Point(0, 0), map);
        }
        assertEquals(before + n, BotGeneration.getBotsCreatedCount());
    }

    @Test
    void concurrentSpawnsNeverCollideOnIds() throws Exception {
        int threads = 8;
        int perThread = 10;
        Set<Integer> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    ids.add(BotGeneration.createBot(new Point(0, 0), map));
                }
            });
            workers[t].start();
        }
        for (Thread worker : workers) {
            worker.join(10_000);
            assertFalse(worker.isAlive(), "spawn thread did not finish");
        }
        assertEquals(threads * perThread, ids.size(), "concurrent spawns must produce unique ids");
    }

    @Test
    void removeBotFromServerCleansMapRegistryAndStorage() {
        Character bot = newBaseCharacter();
        Mockito.when(bot.getId()).thenReturn(BotHelpers.BOT_BASE_ID + 5);
        Mockito.when(bot.getMap()).thenReturn(map);

        BotStorage.addActiveBot(bot.getId(), new IdleBot(bot));

        BotGeneration.removeBotFromServer(bot);

        verify(map, times(1)).removePlayer(bot);
        assertEquals(1, fakeAccess.removeCalls.get(), "bot must be removed from channel/world");
        assertNull(BotStorage.getBotById(bot.getId()), "storage entry must be cleared");
    }

    @Test
    void removeBotFromServerStopsWheelFirst() {
        Character bot = newBaseCharacter();
        int id = BotHelpers.BOT_BASE_ID + 6;
        Mockito.when(bot.getId()).thenReturn(id);
        Mockito.when(bot.getMap()).thenReturn(map);

        IdleBot sm = new IdleBot(bot);
        BotStorage.addActiveBot(id, sm);
        sm.setRunning(true);
        sm.startScheduledTask(60_000);
        assertTrue(BotTickService.isRegistered(id));

        BotGeneration.removeBotFromServer(bot);

        assertFalse(BotTickService.isRegistered(id), "teardown must unregister the wheel entry");
    }

    @Test
    void removeBotWithoutMapIsSafe() {
        Character bot = newBaseCharacter();
        Mockito.when(bot.getId()).thenReturn(BotHelpers.BOT_BASE_ID + 7);
        // getMap() 返回 null：清理路径必须容忍
        BotGeneration.removeBotFromServer(bot);

        assertEquals(1, fakeAccess.removeCalls.get());
    }
}
