package org.gms.server.bot.types;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.awt.Point;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SocialBot（SoloMapling 649 行版）的时间语义边界测试。
 * <p>
 * 旧简化版的 {@code nextActionMs}/{@code lastReplyMs} 已不存在，其「应答冷却 /
 * 动作间隔」意图由两个源常量接管：{@code CONVERSATION_TIMEOUT_MS = 35_000}
 * （会话超时）与 {@code InteractionTracker.COOLDOWN_MS = 300_000}（反骚扰冷却）。
 * 本类通过反射验证这两个时间边界，不驱动 ambient tick（其需要真实导航图/观察者）。
 */
class SocialBotTimingTest {

    private static final int BOT_ID = 965_000_000;
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
        Mockito.when(chr.getName()).thenReturn("TimingBot");
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
        bot.stopScheduledTask();
        BotStorage.removeActiveBot(BOT_ID);
        BotEventBus.getInstance().reset();
    }

    private Character newPlayer() {
        Character player = Mockito.mock(Character.class);
        Mockito.when(player.getId()).thenReturn(5);
        return player;
    }

    // ── 会话超时（CONVERSATION_TIMEOUT_MS = 35_000） ──

    @Test
    void conversationTimeoutResetsConversation() {
        bot.getInteractors().setRespondant(newPlayer());
        setField(bot, "lastRespondantMessageTime", System.currentTimeMillis() - 36_000); // 已过 35s

        invokeCheckConversationTimeout(bot);

        assertFalse(bot.hasActiveRespondant(), "超过 35s 会话超时必须重置 respondant");
        assertEquals(0L, getField(bot, "lastRespondantMessageTime"),
                "超时重置后 lastRespondantMessageTime 必须归零");
    }

    @Test
    void conversationNotTimedOutBeforeThreshold() {
        bot.getInteractors().setRespondant(newPlayer());
        setField(bot, "lastRespondantMessageTime", System.currentTimeMillis() - 10_000); // 10s < 35s

        invokeCheckConversationTimeout(bot);

        assertTrue(bot.hasActiveRespondant(), "未到 35s 会话超时不得重置 respondant");
    }

    // ── InteractionTracker 反骚扰冷却（COOLDOWN_MS = 300_000） ──

    @Test
    void interactionTrackerEscalatesThenIgnores() throws Exception {
        Object tracker = newTracker();

        assertEquals("NORMAL", trackerLevel(tracker), "1-3 次交互为 NORMAL");
        trackerIncrement(tracker);
        trackerIncrement(tracker);
        trackerIncrement(tracker);
        assertEquals("NORMAL", trackerLevel(tracker));

        trackerIncrement(tracker); // 第 4 次
        assertEquals("REDUCED", trackerLevel(tracker));

        trackerIncrement(tracker); // 第 5 次
        assertEquals("NONVERBAL", trackerLevel(tracker));

        trackerIncrement(tracker); // 第 6 次
        assertEquals("IGNORE", trackerLevel(tracker), "6 次及以上进入 IGNORE 反骚扰");
    }

    @Test
    void interactionTrackerCooldownResetsToNormal() throws Exception {
        Object tracker = newTracker();
        for (int i = 0; i < 6; i++) {
            trackerIncrement(tracker);
        }
        assertEquals("IGNORE", trackerLevel(tracker));

        // 超过 300s 冷却：getLevel 触发 reset 回到 NORMAL
        setTrackerLastInteraction(tracker, System.currentTimeMillis() - 300_001L);
        assertEquals("NORMAL", trackerLevel(tracker), "超过 300s 冷却后应重置为 NORMAL");
    }

    // ── 反射工具 ──

    private static void invokeCheckConversationTimeout(SocialBot bot) {
        try {
            Method m = SocialBot.class.getDeclaredMethod("checkConversationTimeout");
            m.setAccessible(true);
            m.invoke(bot);
        } catch (ReflectiveOperationException e) {
            fail("无法调用 checkConversationTimeout: " + e.getMessage());
        }
    }

    private static Object newTracker() throws Exception {
        Class<?> c = Class.forName("org.gms.server.bot.types.SocialBot$InteractionTracker");
        Constructor<?> ctor = c.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static void trackerIncrement(Object tracker) throws Exception {
        Method m = tracker.getClass().getDeclaredMethod("increment");
        m.setAccessible(true);
        m.invoke(tracker);
    }

    private static String trackerLevel(Object tracker) throws Exception {
        Method m = tracker.getClass().getDeclaredMethod("getLevel");
        m.setAccessible(true);
        return String.valueOf(m.invoke(tracker));
    }

    private static void setTrackerLastInteraction(Object tracker, long ms) throws Exception {
        Field f = tracker.getClass().getDeclaredField("lastInteractionTime");
        f.setAccessible(true);
        f.setLong(tracker, ms);
    }

    private static void setField(Object target, String name, long value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.setLong(target, value);
        } catch (ReflectiveOperationException e) {
            fail("无法写入时间语义字段 " + name + ": " + e.getMessage());
        }
    }

    private static long getField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getLong(target);
        } catch (ReflectiveOperationException e) {
            fail("无法读取时间语义字段 " + name + ": " + e.getMessage());
            return -1; // 不可达：fail 必然抛出
        }
    }
}
