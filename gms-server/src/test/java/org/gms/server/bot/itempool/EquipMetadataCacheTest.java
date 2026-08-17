package org.gms.server.bot.itempool;

import org.gms.manager.ServerManager;
import org.gms.server.bot.decorate.GenericEquipPool;
import org.gms.server.bot.decorate.NXItemPool;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EquipMetadataCache#isStandardV83EquipId(int)} 的 v83 标准装备 ID
 * 区间白名单判定 + 换装池加载层过滤语义测试。
 *
 * <p><b>背景</b>：服务端 wz（5725 图）含客户端（5609 图）没有的自定义装备，
 * bot 穿上后广播 spawn 包会导致 v83 客户端渲染崩溃。本白名单只放行客户端
 * 必然可渲染的标准 ID 区间，区间外的 ID 一律不进换装池；区间内但 wz 不存在
 * 的 ID 由 {@link EquipMetadataCache#equipExists(int)} 拦截，两层互补。
 *
 * <p><b>运行前提（池加载断言部分，缺一不可）</b>：
 * <ol>
 *   <li>测试 CWD = gms-server 模块根：{@code ItemInformationProvider} 按相对路径
 *       {@code wz/}（及 {@code wz-zh-CN/} 语言目录，缺失文件回落基目录）读取 XML
 *       格式 WZ 数据；</li>
 *   <li>离线无 DB：{@code ItemInformationProvider} 构造器里的 {@code loadCardIdData}
 *       直连 {@code DataSource}，本类把 DataSource bean 桩为抛 {@link SQLException}
 *       （原实现 catch 后跳过）；</li>
 *   <li>{@code EquipMetadataCache} 一次性构建（全量扫描 String.wz + 逐件读 Item.wz，
 *       数秒~数十秒），@BeforeAll 预热一次。</li>
 * </ol>
 *
 * <p>纯静态区间判定（isStandardV83EquipId）不依赖以上环境，可独立运行。
 */
class EquipMetadataCacheTest {

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();

        // 离线桩：DataSource.getConnection 抛 SQLException → 生产代码 catch(SQLException)
        // 跳过 DB 段（同 ArtificialFreeMarketSmokeTest；不桩则 mock 返回 null 连接，
        // try-with-resources 里 con.prepareStatement 会 NPE 炸掉静态单例）。
        ApplicationContext ctx = ServerManager.getApplicationContext();
        DataSource dataSource = Mockito.mock(DataSource.class);
        try {
            Mockito.when(dataSource.getConnection()).thenThrow(new SQLException("offline test: no DB"));
        } catch (SQLException e) {
            throw new AssertionError("stubbing DataSource must not fail", e);
        }
        Mockito.when(ctx.getBean(DataSource.class)).thenReturn(dataSource);

        // 预热 wz 存在性索引：供池加载层的 equipExists 过滤使用（懒加载兜底，仅构建一次）。
        assertTrue(EquipMetadataCache.get().equipExists(1040000),
                "equip metadata cache must index wz-existing equips (e.g. 1040000)");
    }

    // ── 纯静态区间判定 ─────────────────────────────────────────────────────

    @Test
    void standardIdsAcrossCategoriesAreAccepted() {
        assertTrue(EquipMetadataCache.isStandardV83EquipId(1000001), "帽子 1000001");
        assertTrue(EquipMetadataCache.isStandardV83EquipId(1040085), "上衣 1040085");
        assertTrue(EquipMetadataCache.isStandardV83EquipId(1302028), "单手剑 1302028");
        assertTrue(EquipMetadataCache.isStandardV83EquipId(1702035), "现金装备 1702035");
        assertTrue(EquipMetadataCache.isStandardV83EquipId(1112001), "戒指 1112001");
    }

    @Test
    void nonStandardIdsAreRejected() {
        assertFalse(EquipMetadataCache.isStandardV83EquipId(99999), "过小的 id");
        assertFalse(EquipMetadataCache.isStandardV83EquipId(99999999), "过大的 id");
        assertFalse(EquipMetadataCache.isStandardV83EquipId(2200000), "脸型/表情段 2200000");
        assertFalse(EquipMetadataCache.isStandardV83EquipId(5000000), "现金道具段 5000000");
        assertFalse(EquipMetadataCache.isStandardV83EquipId(3010071), "椅子 3010071 不属于装备段");
    }

    @Test
    void rangeBoundariesAreInclusive() {
        // 各段下界收、上界收、上界 +1 拒（区间均为闭区间）。
        assertTrue(EquipMetadataCache.isStandardV83EquipId(1000000), "帽段下界");
        assertTrue(EquipMetadataCache.isStandardV83EquipId(1002999), "帽段上界");
        assertFalse(EquipMetadataCache.isStandardV83EquipId(1003000), "帽段上界 +1");

        assertTrue(EquipMetadataCache.isStandardV83EquipId(1700000), "现金装备下界");
        assertTrue(EquipMetadataCache.isStandardV83EquipId(1799999), "现金装备上界");
        assertFalse(EquipMetadataCache.isStandardV83EquipId(1800000), "现金装备上界 +1");

        // 骑宠 1900000-1902999 保留（不在装备池），应被拒。
        assertFalse(EquipMetadataCache.isStandardV83EquipId(1900000), "骑宠段不属于装备白名单");
        assertFalse(EquipMetadataCache.isStandardV83EquipId(1902999), "骑宠段不属于装备白名单");
    }

    // ── 池加载层过滤 ───────────────────────────────────────────────────────

    /**
     * 加载真实换装池（GenericEquipPool + NXItemPool，静态资源 + 真实 wz 索引），
     * 断言池内全部条目均满足 {@link EquipMetadataCache#isStandardV83EquipId}，
     * 且 YAML 中已知的非标准 ID（服务端 wz 有、客户端没有的自定义装备）被剔除。
     */
    @Test
    void loadedPoolsOnlyContainStandardV83EquipIds() {
        GenericEquipPool.load();
        NXItemPool.load();

        List<Integer> genericIds = readPoolIds(GenericEquipPool.class);
        List<Integer> nxIds = readPoolIds(NXItemPool.class);

        assertFalse(genericIds.isEmpty(), "generic equip pool must not be empty");
        assertFalse(nxIds.isEmpty(), "NX item pool must not be empty");

        for (int id : genericIds) {
            assertTrue(EquipMetadataCache.isStandardV83EquipId(id),
                    "generic pool id " + id + " must be inside a v83 standard equip range");
        }
        for (int id : nxIds) {
            assertTrue(EquipMetadataCache.isStandardV83EquipId(id),
                    "NX pool id " + id + " must be inside a v83 standard equip range");
        }

        // YAML 中已知的非标准 ID（1062000/1062001 裤子、1003797 帽子，超出标准区间，
        // 属服务端 wz 有、客户端没有的自定义装备）必须被加载层剔除。
        assertFalse(genericIds.contains(1062000), "1062000 must be filtered from the generic pool");
        assertFalse(genericIds.contains(1062001), "1062001 must be filtered from the generic pool");
        assertFalse(nxIds.contains(1003797), "1003797 must be filtered from the NX pool");
    }

    /** 反射读取池类私有 pools 映射中全部条目的 id（加载完成后调用）。 */
    @SuppressWarnings("unchecked")
    private static List<Integer> readPoolIds(Class<?> poolClass) {
        try {
            Field poolsField = poolClass.getDeclaredField("pools");
            poolsField.setAccessible(true);
            Map<String, List<Object>> pools = (Map<String, List<Object>>) poolsField.get(null);
            List<Integer> ids = new ArrayList<>();
            for (List<Object> list : pools.values()) {
                for (Object item : list) {
                    Field idField = item.getClass().getDeclaredField("id");
                    idField.setAccessible(true);
                    ids.add(idField.getInt(item));
                }
            }
            return ids;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to read pool ids from " + poolClass.getName(), e);
        }
    }
}
