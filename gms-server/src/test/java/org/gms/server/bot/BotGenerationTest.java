package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.server.bot.types.IdleBot;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    }

    @AfterEach
    void tearDown() {
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
