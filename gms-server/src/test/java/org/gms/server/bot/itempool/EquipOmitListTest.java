package org.gms.server.bot.itempool;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.constants.inventory.EquipType;
import org.gms.manager.ServerManager;
import org.gms.server.ItemInformationProvider;
import org.gms.server.bot.decorate.GenericEquipPool;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EquipOmitList} 黑名单语义 + 装饰随机路径（{@link GenericEquipPool}）
 * 接入过滤的回归测试。
 *
 * <p>运行前提（多轮抽取断言部分，同 EquipMetadataCacheTest）：
 * 测试 CWD = gms-server 模块根，{@code ItemInformationProvider} 按相对路径读 wz
 * XML；离线无 DB（DataSource 桩为抛 SQLException）；{@code EquipMetadataCache}
 * 一次性构建需数秒，@BeforeAll 预热一次。</p>
 */
class EquipOmitListTest {

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();

        // 离线桩：DataSource.getConnection 抛 SQLException → 生产代码 catch(SQLException)
        // 跳过 DB 段（同 EquipMetadataCacheTest）。
        ApplicationContext ctx = ServerManager.getApplicationContext();
        DataSource dataSource = Mockito.mock(DataSource.class);
        try {
            Mockito.when(dataSource.getConnection()).thenThrow(new SQLException("offline test: no DB"));
        } catch (SQLException e) {
            throw new AssertionError("stubbing DataSource must not fail", e);
        }
        Mockito.when(ctx.getBean(DataSource.class)).thenReturn(dataSource);

        // 预热 wz 存在性索引：GenericEquipPool.load 的 equipExists 过滤依赖它。
        assertTrue(EquipMetadataCache.get().equipExists(1040000),
                "equip metadata cache must index wz-existing equips (e.g. 1040000)");
    }

    // ── EquipOmitList.isOmitted ─────────────────────────────────────────────

    @Test
    void isOmittedHitsListedIds() {
        // YAML ids 列表中的旗杆武器与旗类外观必须命中。
        assertTrue(EquipOmitList.isOmitted(1302033), "Maple Flag 1302033 must be omitted");
        assertTrue(EquipOmitList.isOmitted(1452049), "Singapore Flag (Bow) 1452049 must be omitted");
        assertTrue(EquipOmitList.isOmitted(1002500), "Korean Flag Bandana 1002500 must be omitted");
        assertTrue(EquipOmitList.isOmitted(1102152), "Pirate Emblem Flag 1102152 must be omitted");
    }

    @Test
    void isOmittedMissesRegularEquips() {
        // 不在黑名单的常规装备不得误伤。
        assertFalse(EquipOmitList.isOmitted(1040085), "regular top 1040085 must not be omitted");
        assertFalse(EquipOmitList.isOmitted(1302028), "regular sword 1302028 must not be omitted");
        assertFalse(EquipOmitList.isOmitted(0), "id 0 must not be omitted");
    }

    @Test
    void disabledToggleTurnsListOff() {
        try {
            EquipOmitList.setEnabled(false);
            assertFalse(EquipOmitList.isOmitted(1302033),
                    "disabled list must let everything through");
        } finally {
            EquipOmitList.setEnabled(true);
        }
        assertTrue(EquipOmitList.isOmitted(1302033),
                "re-enabled list must filter again");
    }

    // ── GenericEquipPool 装饰随机路径接入过滤 ────────────────────────────────

    /**
     * 加载真实换装池（weapons 分类含 1302033 Maple Flag 等多个黑名单条目），
     * 高等级 bot 多轮抽取，结果不得出现任何黑名单条目。
     */
    @Test
    void genericPoolDrawsNeverReturnOmittedIds() {
        GenericEquipPool.load();
        assertTrue(GenericEquipPool.isLoaded(), "generic equip pool must be loaded");

        int draws = 0;
        int nonNullDraws = 0;
        // botLevel 取 200（高于 v83 装备天花板），gender 取 2（unisex），
        // 使 weapons 分类全部条目都通过硬等级上限与性别闸门，仅剩黑名单过滤在起作用。
        for (int i = 0; i < 300; i++) {
            Integer id = GenericEquipPool.getRandom("weapons", 200, 2);
            draws++;
            if (id == null) {
                continue; // 候选被过滤空时返回 null（防御性跳过，正常不会发生）
            }
            nonNullDraws++;
            assertFalse(EquipOmitList.isOmitted(id),
                    "generic pool draw #" + i + " returned omitted item " + id);
        }
        assertEquals(300, draws);
        assertTrue(nonNullDraws > 100,
                "expected most draws to yield an item, got " + nonNullDraws + "/300");
    }

    /**
     * {@link ItemInformationProvider#getRandomEquipForWearing} 抽取回归：
     * 构造让黑名单弓（1452049 Singapore Flag Bow，reqLevel 30 / reqJob 4 / gender 2）
     * 必然落入候选窗口的角色参数（35 级弓箭手），N 轮抽取结果不得出现黑名单条目。
     * 复用 @BeforeAll 已预热的 {@link EquipMetadataCache}，不再触发 wz 重建。
     */
    @Test
    void randomEquipForWearingNeverReturnsOmittedIds() {
        EquipOmitList.load();
        int level = 35;
        // 与 ItemInformationProvider.getRandomEquipForStyle 相同的窗口下界公式：
        // maxLevel - max(25% of maxLevel, 10)。
        int minLevel = level - Math.max((int) (level * 0.25), 10);

        // 抽取前断言：候选池（缓存元数据推导的窗口）中确实含黑名单条目——
        // 否则本测试对过滤分支无覆盖意义。
        boolean poolContainsOmitted = EquipMetadataCache.get().nonCash(EquipType.BOW).stream()
                .anyMatch(e -> e.id == 1452049
                        && e.reqLevel >= minLevel && e.reqLevel <= level
                        && e.reqJob == 4 && e.gender == 2);
        assertTrue(poolContainsOmitted,
                "candidate window [level 35 bowman] must contain omitted id 1452049");

        Character chr = Mockito.mock(Character.class);
        Mockito.when(chr.getLevel()).thenReturn(level);
        Mockito.when(chr.getJobStyle()).thenReturn(Job.BOWMAN);
        Mockito.when(chr.getGender()).thenReturn(2);

        int nonNullDraws = 0;
        for (int i = 0; i < 300; i++) {
            int id = ItemInformationProvider.getInstance().getRandomEquipForWearing(EquipType.BOW, chr);
            if (id == 0) {
                continue; // 候选被过滤空时返回 0（防御性跳过，正常不会发生）
            }
            nonNullDraws++;
            assertFalse(EquipOmitList.isOmitted(id),
                    "wearing draw #" + i + " returned omitted item " + id);
        }
        assertTrue(nonNullDraws > 100,
                "expected most draws to yield an item, got " + nonNullDraws + "/300");
    }
}
