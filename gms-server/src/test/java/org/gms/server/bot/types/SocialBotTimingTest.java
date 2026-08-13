package org.gms.server.bot.types;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * SocialBot 的时间语义边界测试。
 * <p>
 * 行为测试（{@link SocialBotTest}）只能断言静态边界：动作间隔与应答冷却的语义
 * 取决于 {@code nextActionMs}/{@code lastReplyMs} 两个时间字段，不注入时间就无法
 * 确定性地测「到期/未到期/恰到期/冷却内/冷却外」。本类通过反射读写这两个私有
 * 字段（不修改主代码），对每个时间语义边界做确定性断言。
 */
class SocialBotTimingTest {

    /** 本测试类的 bot id 段位：965_000_000，与 SocialBotTest 的 20101 隔离（BotStorage 是全局注册表）。 */
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
        // 与 SocialBotTest 相同的 mock 套路
        chr = Mockito.mock(Character.class);
        Mockito.when(chr.getId()).thenReturn(BOT_ID);
        Mockito.when(chr.getName()).thenReturn("TimingBot");
        Mockito.when(chr.getWorld()).thenReturn(0);
        Mockito.when(chr.getPosition()).thenReturn(new Point(0, 0));
        Mockito.when(chr.getStance()).thenReturn(0);
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

    /** 图上放一个真实玩家（id=5，非 bot 区段），让观察门槛放行动作路径。 */
    private void putRealPlayerOnMap() {
        Character player = Mockito.mock(Character.class);
        Mockito.when(player.getId()).thenReturn(5);
        Mockito.when(map.getCharacters()).thenReturn(List.of(player));
    }

    @Test
    void actionFiresAtLeastOnceWhenNextActionDue() {
        putRealPlayerOnMap();
        long now = System.currentTimeMillis();
        setField(bot, "nextActionMs", now - 1); // 到期（now < nextActionMs 为 false）

        bot.updateState();

        verify(map, atLeast(1)).broadcastMessage(any());
        assertTrue(getField(bot, "nextActionMs") > now,
                "动作触发后 nextActionMs 必须一次性掷定到未来");
    }

    @Test
    void actionSuppressedBeforeNextActionMs() {
        putRealPlayerOnMap();
        long due = System.currentTimeMillis() + 60_000;
        setField(bot, "nextActionMs", due); // 未到期

        bot.updateState();

        verify(map, never()).broadcastMessage(any());
        assertEquals(due, getField(bot, "nextActionMs"),
                "间隔未到不得动作，也不得推进 nextActionMs");
    }

    @Test
    void actionBoundaryExactDue() {
        putRealPlayerOnMap();
        long now = System.currentTimeMillis();
        setField(bot, "nextActionMs", now); // 恰到期：now < nextActionMs 为 false → 动作

        bot.updateState();

        verify(map, atLeast(1)).broadcastMessage(any());
        assertTrue(getField(bot, "nextActionMs") > now,
                "恰到期触发动作后 nextActionMs 应推进到未来");
    }

    @Test
    void replyCooldownBlocksSecondReply() {
        // 空图：主动动作不参与，本用例 broadcast 全部来自应答
        long now = System.currentTimeMillis();
        setField(bot, "lastReplyMs", now); // 冷却起点：now - lastReplyMs ≈ 0 < 20s
        BotEventBus.getInstance().publish(GameEvent.chat(0, 1, MAP_ID, 5, "你好"));

        bot.updateState();

        verify(map, never()).broadcastMessage(any());

        setField(bot, "lastReplyMs", now - 21_000); // 冷却已过
        BotEventBus.getInstance().publish(GameEvent.chat(0, 1, MAP_ID, 5, "还在吗？"));

        bot.updateState();

        verify(map, times(1)).broadcastMessage(any());
    }

    @Test
    void replyDoesNotHappenWhenMapNull() {
        Mockito.when(chr.getMap()).thenReturn(null); // 已离图/销毁窗口
        setField(bot, "lastReplyMs", 0); // 冷却早已满足：若 map 非 null 必应答
        BotEventBus.getInstance().publish(GameEvent.chat(0, 1, MAP_ID, 5, "你好"));

        bot.updateState();

        verify(map, never()).broadcastMessage(any());
        assertEquals(0, getField(bot, "lastReplyMs"),
                "map null 时不应答，也不消耗冷却（lastReplyMs 不得被写入）");
    }

    @Test
    void repliedTickSkipsActiveAction() {
        putRealPlayerOnMap();
        setField(bot, "lastReplyMs", 0); // 冷却已过 → 本 tick 必应答
        setField(bot, "nextActionMs", System.currentTimeMillis() - 1); // 主动动作同时到期
        BotEventBus.getInstance().publish(GameEvent.chat(0, 1, MAP_ID, 5, "你好"));

        bot.updateState();

        // 恰好 1 次广播：应答发生，主动动作被「本 tick 刚应答过」分支抑制
        verify(map, times(1)).broadcastMessage(any());
    }

    // ── 反射工具：注入/读取时间语义字段（字段缺失/不可见时 fail 并给出字段名） ──

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
