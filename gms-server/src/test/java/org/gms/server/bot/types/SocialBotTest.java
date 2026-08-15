package org.gms.server.bot.types;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.EventType;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.awt.Point;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SocialBot（SoloMapling 649 行版移植）：LEVEL_UP 订阅与重启重订阅、事件过滤、
 * respondant 驱动的可用性门控、variant 合法取值。旧简化版的「主动动作间隔 /
 * CHAT 应答冷却」语义已由对话会话超时 + InteractionTracker 反骚扰接管，相关
 * 时间边界见 {@link SocialBotTimingTest}。
 */
class SocialBotTest {

    private static final int BOT_ID = 20101;
    private static final int MAP_ID = 100000000;

    private Character chr;
    private Client client;
    private MapleMap map;
    private SocialBot bot;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        chr = Mockito.mock(Character.class);
        Mockito.when(chr.getId()).thenReturn(BOT_ID);
        Mockito.when(chr.getName()).thenReturn("SocialBot");
        Mockito.when(chr.getWorld()).thenReturn(0);
        Mockito.when(chr.getPosition()).thenReturn(new Point(0, 0));
        Mockito.when(chr.getWhiteChat()).thenReturn(false);
        Mockito.when(chr.getMapId()).thenReturn(MAP_ID);

        client = Mockito.mock(Client.class);
        Mockito.when(client.getChannel()).thenReturn(1);
        Mockito.when(chr.getClient()).thenReturn(client);

        map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getId()).thenReturn(MAP_ID);
        Mockito.when(map.getCharacters()).thenReturn(Collections.emptyList());
        Mockito.when(chr.getMap()).thenReturn(map);

        bot = new SocialBot(chr);
        BotStorage.addActiveBot(BOT_ID, bot);
        bot.setRunning(true);
    }

    @AfterEach
    void tearDown() {
        bot.setRunning(false);
        bot.stopScheduledTask(); // 退订事件 + 取消轮盘
        BotStorage.removeActiveBot(BOT_ID);
        BotEventBus.getInstance().reset();
    }

    @Test
    void subscribesToLevelUpOnConstruction() {
        // 相对断言：不依赖全 JVM 只有本 bot 一个订阅者
        assertTrue(BotEventBus.getInstance().subscriberCount(EventType.LEVEL_UP) >= 1,
                "构造器必须订阅 LEVEL_UP");
    }

    @Test
    void stopThenStartResubscribesLevelUp() {
        // stopScheduledTask 退订、onScheduledStart 重订阅的回归防线
        int baseline = BotEventBus.getInstance().subscriberCount(EventType.LEVEL_UP);
        assertTrue(baseline >= 1, "setUp must have subscribed this bot");

        bot.stopScheduledTask();
        assertEquals(baseline - 1, BotEventBus.getInstance().subscriberCount(EventType.LEVEL_UP),
                "stopping must unsubscribe");

        bot.startScheduledTask(60_000);
        assertEquals(baseline, BotEventBus.getInstance().subscriberCount(EventType.LEVEL_UP),
                "re-starting must re-subscribe the LEVEL_UP listener");
    }

    @Test
    void levelUpEventFromOtherWorldIsFiltered() {
        GameEvent otherWorld = GameEvent.levelUp(1, 1, MAP_ID, 5);
        assertFalse(bot.matchesFilter(otherWorld), "不同世界的升级事件不应命中订阅过滤器");
    }

    @Test
    void levelUpEventFromOtherMapIsFiltered() {
        GameEvent otherMap = GameEvent.levelUp(0, 1, 100000001, 5);
        assertFalse(bot.matchesFilter(otherMap), "不同地图的升级事件不应命中订阅过滤器");
    }

    @Test
    void levelUpEventOnSameMapMatchesFilter() {
        GameEvent sameMap = GameEvent.levelUp(0, 1, MAP_ID, 5);
        assertTrue(bot.matchesFilter(sameMap), "同世界同地图的升级事件应命中订阅过滤器");
    }

    @Test
    void hasActiveRespondantReflectsInteractors() {
        assertFalse(bot.hasActiveRespondant(), "初始无 respondant");

        Character player = Mockito.mock(Character.class);
        Mockito.when(player.getId()).thenReturn(5);
        bot.getInteractors().setRespondant(player);

        assertTrue(bot.hasActiveRespondant(), "setRespondant 后应有活跃 respondant");
    }

    @Test
    void availabilityGatedByRespondant() {
        Character player = Mockito.mock(Character.class);
        Mockito.when(player.getId()).thenReturn(5);

        // 初始（无 respondant、无闲聊、无脚本会话、未漂移）：可用
        assertTrue(bot.isAvailableForAmbientActions());

        bot.getInteractors().setRespondant(player);
        assertFalse(bot.isAvailableForAmbientActions(), "对话中（有 respondant）不应可用");

        bot.getInteractors().resetRespondant();
        assertTrue(bot.isAvailableForAmbientActions(), "对话结束恢复可用");
    }

    @Test
    void variantIsKnownValue() {
        SocialBot.SocialBotVariant v = bot.getVariant();
        assertNotNull(v);
        assertTrue(v == SocialBot.SocialBotVariant.SINGLE_RESPONSE
                        || v == SocialBot.SocialBotVariant.INTERACTIVE,
                "variant 必须是 SINGLE_RESPONSE 或 INTERACTIVE");
    }
}
