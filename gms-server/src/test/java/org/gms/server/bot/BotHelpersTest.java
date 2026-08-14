package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.server.bot.types.IdleBot;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.gms.util.I18nUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.awt.Point;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BotHelpers：bot 判定（区段初筛 + 注册表双判据）、随机名字池格式。
 */
class BotHelpersTest {

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @AfterEach
    void tearDown() {
        BotStorage.removeActiveBot(BotHelpers.BOT_BASE_ID + 100);
    }

    @Test
    void isBotIdBySegmentBoundary() {
        // 区段初筛（高频发布点用）：真实玩家 id 由数据库自增，恒在区段之外
        assertFalse(BotHelpers.isBotId(BotHelpers.BOT_BASE_ID), "id == base must not be in bot segment");
        assertTrue(BotHelpers.isBotId(BotHelpers.BOT_BASE_ID + 1), "id > base must be in bot segment");
        assertFalse(BotHelpers.isBotId(12345), "real character id must not be in bot segment");
        assertFalse(BotHelpers.isBotId(0));
    }

    @Test
    void isBotRequiresRegistryHit() {
        int botId = BotHelpers.BOT_BASE_ID + 100;

        // 区段内但未注册：不是 bot（防真实玩家 id 意外越界被误判）
        assertFalse(BotHelpers.isBot(botId));

        // 注册后：是 bot
        BotStorage.addActiveBot(botId, new IdleBot(Mockito.mock(Character.class)));
        assertTrue(BotHelpers.isBot(botId));

        // 注销后：不再是 bot
        BotStorage.removeActiveBot(botId);
        assertFalse(BotHelpers.isBot(botId));
    }

    @Test
    void isBotByCharacter() {
        int botId = BotHelpers.BOT_BASE_ID + 100;
        Character bot = Mockito.mock(Character.class);
        Mockito.when(bot.getId()).thenReturn(botId);
        assertFalse(BotHelpers.isBot(bot), "unregistered segment character must not be a bot");

        BotStorage.addActiveBot(botId, new IdleBot(bot));
        assertTrue(BotHelpers.isBot(bot));

        Character player = Mockito.mock(Character.class);
        Mockito.when(player.getId()).thenReturn(99);
        assertFalse(BotHelpers.isBot(player));
        assertFalse(BotHelpers.isBot(null));
    }

    @Test
    void randomBotNameComesFromPool() {
        String pool = I18nUtil.getMessage("bot.name.pool");
        assertNotNull(pool);
        Set<String> poolNames = new HashSet<>(Arrays.asList(pool.split(",")));
        assertFalse(poolNames.isEmpty(), "name pool must not be empty");

        for (int i = 0; i < 50; i++) {
            String name = BotHelpers.randomBotName();
            assertNotNull(name);
            assertFalse(name.isBlank(), "generated name must not be blank");
            assertTrue(poolNames.contains(name), "generated name not from pool: " + name);
        }
    }

    @Test
    void pickGroundSpotsSpreadsBatchAcrossX() {
        // 回归防线（「批量 bot 都站在一起」）：出生点必须沿 X 分散且保持最小间距，
        // 每个点经 getPointBelow 修正到地面（mock：地面 Y 恒为 100）
        MapleMap map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getPointBelow(Mockito.any())).thenAnswer(inv -> new Point(
                ((Point) inv.getArgument(0)).x, 100));

        List<Point> spots = BotHelpers.pickGroundSpots(map, new Point(0, 100), 10);

        assertEquals(10, spots.size());
        for (Point spot : spots) {
            assertEquals(100, spot.y, "every spot must be grounded via getPointBelow");
        }
        for (int i = 0; i < spots.size(); i++) {
            for (int j = i + 1; j < spots.size(); j++) {
                assertTrue(Math.abs(spots.get(i).x - spots.get(j).x) >= 20,
                        "spots must keep horizontal spacing, got " + spots.get(i) + " vs " + spots.get(j));
            }
        }
    }

    @Test
    void pickGroundSpotsFallsBackToAnchorWhenNoFoothold() {
        MapleMap map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getPointBelow(Mockito.any())).thenReturn(null);

        List<Point> spots = BotHelpers.pickGroundSpots(map, new Point(50, 60), 5);

        assertEquals(5, spots.size());
        for (Point spot : spots) {
            assertEquals(new Point(50, 60), spot,
                    "without footholds every spot must fall back to the anchor");
        }
    }

    @Test
    void pickGroundSpotsHandlesDegenerateInputs() {
        MapleMap map = Mockito.mock(MapleMap.class);
        assertTrue(BotHelpers.pickGroundSpots(null, new Point(0, 0), 3).isEmpty());
        assertTrue(BotHelpers.pickGroundSpots(map, null, 3).isEmpty());
        assertTrue(BotHelpers.pickGroundSpots(map, new Point(0, 0), 0).isEmpty());
    }
}
