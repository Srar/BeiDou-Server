package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.server.bot.types.IdleBot;
import org.gms.test.BotTestSupport;
import org.gms.util.I18nUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
}
