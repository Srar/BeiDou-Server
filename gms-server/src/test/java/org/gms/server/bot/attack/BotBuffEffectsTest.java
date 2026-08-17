package org.gms.server.bot.attack;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.net.packet.Packet;
import org.gms.server.StatEffect;
import org.gms.server.bot.gcmove.LodCounts;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.gms.util.Pair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * BotBuffEffects 观察门控：无人观察的图跳过 buff 视觉广播（C6 showBuffEffect + C7
 * giveForeignBuff），duration 照常返回；GM 强制路径（showBuffUnchecked / castBuff force）
 * 保持全量广播。防止无人观察图的全量广播推高客户端负载（2026-08-17 崩溃事故降载）。
 */
class BotBuffEffectsTest {

    /** 注入 SkillFactory 缓存的虚拟技能 id（远离真实 skill id 空间，避免冲突）。 */
    private static final int SKILL_ID = 888_888_888;
    /** 无对应 skill 的 id：验证门控判断先于 skill 解析（C6 广播前）。 */
    private static final int UNRESOLVABLE_SKILL_ID = 999_999_999;
    private static final int MAX_LEVEL = 30;
    private static final int DURATION_MS = 120_000;

    /** 测试前备份 SkillFactory.skills 原始引用，结束后还原（防同 fork 顺序污染）。 */
    private static Map<Integer, Skill> originalSkills;

    private Character bot;
    private MapleMap map;
    private MockedStatic<LodCounts> lodCountsMock;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
        // 纯单元测试不加载 wz：反射把 mock Skill 塞进 SkillFactory 私有缓存，
        // 让 showBuff 能解析出可控的 duration（测试结束还原）。
        try {
            Field field = SkillFactory.class.getDeclaredField("skills");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Integer, Skill> current = (Map<Integer, Skill>) field.get(null);
            originalSkills = current;
            Map<Integer, Skill> testSkills = new HashMap<>();
            testSkills.put(SKILL_ID, buildSkill());
            field.set(null, testSkills);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法注入 mock Skill 到 SkillFactory", e);
        }
    }

    @AfterAll
    static void restoreSkills() {
        try {
            Field field = SkillFactory.class.getDeclaredField("skills");
            field.setAccessible(true);
            field.set(null, originalSkills);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法还原 SkillFactory.skills", e);
        }
    }

    private static Skill buildSkill() {
        StatEffect effect = Mockito.mock(StatEffect.class);
        Mockito.when(effect.getDuration()).thenReturn(DURATION_MS);
        Mockito.when(effect.getStatups()).thenReturn(List.of(new Pair<>(BuffStat.SPEED, MAX_LEVEL)));
        Skill skill = Mockito.mock(Skill.class);
        Mockito.when(skill.getMaxLevel()).thenReturn(MAX_LEVEL);
        Mockito.when(skill.getEffect(MAX_LEVEL)).thenReturn(effect);
        return skill;
    }

    @BeforeEach
    void setUp() {
        bot = Mockito.mock(Character.class);
        // 落在 bot 区段（真实路径 bot id 均 > BOT_BASE_ID）
        Mockito.when(bot.getId()).thenReturn(2_000_000_100);

        map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getId()).thenReturn(100000000);
        Mockito.when(map.getCharacters()).thenReturn(Collections.emptyList());
        Mockito.when(bot.getMap()).thenReturn(map);

        // 显式钉死 trackerRunning()==false，稳定走 hasRealPlayerObserver 的线性
        // 扫描回退分支（避免同 fork 中其他测试启动 ObserverTracker 造成顺序污染）。
        lodCountsMock = Mockito.mockStatic(LodCounts.class);
        lodCountsMock.when(LodCounts::trackerRunning).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        lodCountsMock.close();
    }

    /** 无人观察：两次广播全部跳过，但 duration 照常返回（recast 计时不受门控影响）。 */
    @Test
    void showBuffSkipsBothBroadcastsWhenNoObserverButReturnsDuration() {
        int duration = BotBuffEffects.showBuff(bot, SKILL_ID);

        assertEquals(DURATION_MS, duration, "无人观察也必须返回 WZ duration，保证 recast 计时不受门控影响");
        verify(map, never()).broadcastMessage(any(Character.class), any(Packet.class), anyBoolean());
    }

    /** 无人观察 + skill 无法解析：C6 广播同样不触发（门控判断位于 skill 解析之前）。 */
    @Test
    void showBuffSkipsCastAnimationEvenForUnresolvableSkill() {
        int duration = BotBuffEffects.showBuff(bot, UNRESOLVABLE_SKILL_ID);

        assertEquals(0, duration, "无法解析的 skill 应返回 0");
        verify(map, never()).broadcastMessage(any(Character.class), any(Packet.class), anyBoolean());
    }

    /** 有人观察：C6 + C7 正常广播（statups 非空时为 2 次）。 */
    @Test
    void showBuffBroadcastsWhenRealPlayerObserves() {
        Character player = Mockito.mock(Character.class);
        Mockito.when(player.getId()).thenReturn(12345); // 真实玩家 id，非 bot 区段
        Mockito.when(map.getCharacters()).thenReturn(List.of(player));

        int duration = BotBuffEffects.showBuff(bot, SKILL_ID);

        assertEquals(DURATION_MS, duration);
        verify(map, times(2)).broadcastMessage(any(Character.class), any(Packet.class), anyBoolean());
    }

    /** GM 强制路径：无人观察也全量广播。 */
    @Test
    void showBuffUncheckedBroadcastsWithoutObserver() {
        int duration = BotBuffEffects.showBuffUnchecked(bot, SKILL_ID);

        assertEquals(DURATION_MS, duration);
        verify(map, times(2)).broadcastMessage(any(Character.class), any(Packet.class), anyBoolean());
    }

    /** castBuff 默认（普通 recast tick）：无人观察跳过广播，duration 正常。 */
    @Test
    void castBuffGatedSkipsWithoutObserver() {
        int duration = BotBuffEffects.castBuff(bot, SKILL_ID);

        assertEquals(DURATION_MS, duration);
        verify(map, never()).broadcastMessage(any(Character.class), any(Packet.class), anyBoolean());
    }

    /** castBuff force=true（GM !bot buff 强制路径）：无人观察也全量广播。 */
    @Test
    void castBuffForceBroadcastsWithoutObserver() {
        int duration = BotBuffEffects.castBuff(bot, SKILL_ID, true);

        assertEquals(DURATION_MS, duration);
        verify(map, times(2)).broadcastMessage(any(Character.class), any(Packet.class), anyBoolean());
    }
}
