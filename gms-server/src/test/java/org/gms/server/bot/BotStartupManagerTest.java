package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BotStartupManager（生产启动入口）：默认关闭即空转、缺图跳过、全路径
 * （取图→createBot→按 ID 取角色→注册→挂 tick 轮）。经 FakeBotServerAccess
 * 注入接缝（setServerAccess）与 BotGeneration 的基底角色注入，不触碰 Server 单例。
 */
class BotStartupManagerTest {

    private static class FakeBotServerAccess implements BotServerAccess {
        final AtomicInteger addCalls = new AtomicInteger();
        final AtomicInteger getMapCalls = new AtomicInteger();
        final AtomicInteger lastRequestedId = new AtomicInteger();
        MapleMap map;

        @Override
        public void addBotToServer(Character bot) {
            addCalls.incrementAndGet();
        }

        @Override
        public void removeBotFromServer(Character bot) {
        }

        @Override
        public MapleMap getMap(int world, int channel, int mapId) {
            getMapCalls.incrementAndGet();
            return map;
        }

        @Override
        public Character getCharacterById(int characterId) {
            lastRequestedId.set(characterId);
            Character chr = mock(Character.class);
            when(chr.getId()).thenReturn(characterId);
            return chr;
        }

        @Override
        public boolean isNameTaken(String name) {
            return false;
        }
    }

    private FakeBotServerAccess fakeAccess;
    private MapleMap map;
    private Character baseCharacter;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        fakeAccess = new FakeBotServerAccess();
        BotStartupManager.setServerAccess(fakeAccess);
        BotGeneration.setServerAccess(fakeAccess);
        baseCharacter = mock(Character.class);
        BotGeneration.setBaseCharacterSupplier(() -> baseCharacter);
        map = mock(MapleMap.class);
    }

    @AfterEach
    void tearDown() {
        // 清理 spawnOne 全路径用例遗留的注册条目与 tick 轮（stopScheduledTask 已含 unregister）
        List<Integer> ids = new ArrayList<>(BotStorage.getAllBots().keySet());
        for (int id : ids) {
            BotSM sm = BotStorage.getBotById(id);
            if (sm != null) {
                sm.stopScheduledTask();
            }
            BotTickService.unregister(id);
            BotStorage.removeActiveBot(id);
        }
        BotEventBus.getInstance().reset();
        BotStartupManager.setServerAccess(null);
        BotGeneration.setServerAccess(DefaultBotServerAccess.INSTANCE);
        BotGeneration.setBaseCharacterSupplier(null);
    }

    @Test
    void startupDisabledByDefaultDoesNothing() {
        // 空 GameConfig：getServerBoolean 回落 false → 注册订阅后直接返回，不触达任何 server 访问
        BotStartupManager.startup();

        assertEquals(0, fakeAccess.addCalls.get(), "disabled startup must not register any bot");
        assertEquals(0, fakeAccess.getMapCalls.get(), "disabled startup must not fetch any map");
    }

    @Test
    void spawnOneSkipsMissingMap() {
        fakeAccess.map = null;

        BotStartupManager.spawnOne(BotTypeManager.BotType.SOCIAL_BOT, 123, new Point(7, 8));

        assertEquals(0, fakeAccess.addCalls.get(), "missing map must abort spawn before registration");
    }

    @Test
    void spawnOneFullPathRegistersBotAndStarts() {
        fakeAccess.map = map;

        BotStartupManager.spawnOne(BotTypeManager.BotType.SOCIAL_BOT, 123, new Point(7, 8));

        assertEquals(1, fakeAccess.addCalls.get(), "spawn must register the bot exactly once");
        int botId = fakeAccess.lastRequestedId.get();
        assertTrue(botId > BotHelpers.BOT_BASE_ID, "bot id must be in the bot id range: " + botId);
        BotSM registered = BotStorage.getBotById(botId);
        assertNotNull(registered, "spawned bot must be present in BotStorage");
        assertEquals(botId, registered.getChr().getId(), "registered bot must carry the created id");
        // manuallyStartBot 首 tick 延迟 2-5s，不会在测试窗口内触发；断言已挂轮盘
        assertTrue(BotTickService.isRegistered(botId), "spawned bot must be registered on the tick wheel");
    }

    @Test
    void spawnOnePassesGivenSpawnPointThrough() {
        // 回归防线：出生点由调用方（startupInternal 预生成的分散点位）传入，
        // spawnOne 必须原样交给 createBot（经 placeBotOnMap 地面修正，getPointBelow 默认 null → 回退原坐标）
        fakeAccess.map = map;

        BotStartupManager.spawnOne(BotTypeManager.BotType.IDLE_BOT, 123, new Point(7, 8));

        org.mockito.Mockito.verify(baseCharacter).setPosition(new Point(7, 8));
    }
}
