package org.gms.server.bot.itempool;

import org.gms.constants.inventory.EquipType;
import org.gms.server.ItemInformationProvider;
import org.gms.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次性启动缓存的装备元数据（对齐 SoloMapling
 * {@code soloMapling.itemPool.EquipMetadataCache}）：启动时遍历一次
 * {@link ItemInformationProvider#getAllItems()}，过滤 cash、按装备 id 区间分桶，
 * 对每件装备调一次 {@link ItemInformationProvider#getEquipStats(int)} 缓存
 * reqLevel/reqJob，并派生 gender。之后所有随机取装查询都是纯内存过滤，不再访问 WZ。
 *
 * <p>同一遍历还顺带构建 {@code existingEquipIds}（O(1) 存在性索引，含 cash 装备）：
 * 装饰管线给 bot 穿的每件装备在写入装备栏/广播前都必须通过
 * {@link #equipExists(int)} 校验，防止 wz 中不存在的装备 id 进入 spawn 包
 * 的 addCharLook 导致 v83 客户端渲染崩溃。索引覆盖所有
 * stats!=null 且 classify 非 null 的 id（既有 EQUIP_RANGES 区间内），
 * 纯 HashSet 查询，运行时零 WZ 访问。
 *
 * <p>构建放在 {@link org.gms.net.server.Server#init()} 的虚拟线程加载列表里后台预热；
 * 同时 {@link #get()} 内置懒加载兜底（双检锁），保证任何调用路径（含测试）可用。
 */
public final class EquipMetadataCache {

    private static final Logger log = LoggerFactory.getLogger(EquipMetadataCache.class);

    // ── Entry ────────────────────────────────────────────────────────────

    public static final class EquipEntry {
        public final int id;
        public final EquipType equipType;
        /** 0=male, 1=female, 2=unisex（第 4 位数字约定）。 */
        public final int gender;
        public final int reqLevel;
        /** 0=beginner/all, 1=warrior, 2=mage, 4=bow, 8=thief, 16=pirate。 */
        public final int reqJob;
        public final boolean cash;

        EquipEntry(int id, EquipType equipType, int gender, int reqLevel, int reqJob, boolean cash) {
            this.id = id;
            this.equipType = equipType;
            this.gender = gender;
            this.reqLevel = reqLevel;
            this.reqJob = reqJob;
            this.cash = cash;
        }
    }

    // ── ID ranges（对齐 SoloMapling EquipMetadataCache.EQUIP_RANGES） ─────
    // 覆盖 v83 全部装备类别，含 face accessory / ring 等无 EquipType range 的类别。
    // 区间均为闭区间。

    private static final Map<EquipType, int[]> EQUIP_RANGES = new LinkedHashMap<>();

    static {
        EQUIP_RANGES.put(EquipType.CAP, new int[]{1000000, 1003073});
        EQUIP_RANGES.put(EquipType.FACE, new int[]{1012000, 1012200});
        EQUIP_RANGES.put(EquipType.ACCESSORY, new int[]{1022000, 1022104});
        EQUIP_RANGES.put(EquipType.EARRING, new int[]{1032000, 1032075});
        EQUIP_RANGES.put(EquipType.COAT, new int[]{1040000, 1049000});
        EQUIP_RANGES.put(EquipType.LONGCOAT, new int[]{1050000, 1052234});
        EQUIP_RANGES.put(EquipType.PANTS, new int[]{1060000, 1062119});
        EQUIP_RANGES.put(EquipType.SHOES, new int[]{1070000, 1072437});
        EQUIP_RANGES.put(EquipType.GLOVES, new int[]{1080000, 1082262});
        EQUIP_RANGES.put(EquipType.SHIELD, new int[]{1092000, 1092062});
        EQUIP_RANGES.put(EquipType.CAPE, new int[]{1102000, 1102236});
        EQUIP_RANGES.put(EquipType.RING, new int[]{1112000, 1112400});
        EQUIP_RANGES.put(EquipType.SWORD, new int[]{1302000, 1302133});
        EQUIP_RANGES.put(EquipType.AXE, new int[]{1312000, 1312046});
        EQUIP_RANGES.put(EquipType.MACE, new int[]{1322000, 1322074});
        EQUIP_RANGES.put(EquipType.DAGGER, new int[]{1332000, 1332088});
        EQUIP_RANGES.put(EquipType.WAND, new int[]{1372000, 1372046});
        EQUIP_RANGES.put(EquipType.STAFF, new int[]{1382000, 1382062});
        EQUIP_RANGES.put(EquipType.SWORD_2H, new int[]{1402000, 1402072});
        EQUIP_RANGES.put(EquipType.AXE_2H, new int[]{1412000, 1412046});
        EQUIP_RANGES.put(EquipType.MACE_2H, new int[]{1422000, 1422047});
        EQUIP_RANGES.put(EquipType.SPEAR, new int[]{1432000, 1432061});
        EQUIP_RANGES.put(EquipType.POLEARM, new int[]{1442000, 1442103});
        EQUIP_RANGES.put(EquipType.BOW, new int[]{1452000, 1452085});
        EQUIP_RANGES.put(EquipType.CROSSBOW, new int[]{1462000, 1462075});
        EQUIP_RANGES.put(EquipType.CLAW, new int[]{1472000, 1472100});
        EQUIP_RANGES.put(EquipType.KNUCKLER, new int[]{1482000, 1482046});
        EQUIP_RANGES.put(EquipType.PISTOL, new int[]{1492000, 1492048});
    }

    // ── Singleton ────────────────────────────────────────────────────────

    private static volatile EquipMetadataCache instance;
    private static volatile boolean initialized = false;

    private final Map<EquipType, List<EquipEntry>> nonCashByType;
    /** wz 中确实存在（stats!=null 且 classify!=null）的装备 id 全集，含 cash。 */
    private final Set<Integer> existingEquipIds;

    private EquipMetadataCache(Map<EquipType, List<EquipEntry>> nonCashByType, Set<Integer> existingEquipIds) {
        this.nonCashByType = nonCashByType;
        this.existingEquipIds = existingEquipIds;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * 懒加载兜底：未初始化时同步 build 一次（双检锁）。生产路径由
     * {@code Server.init()} 后台预热，正常情况下这里直接返回已就绪的实例。
     */
    public static EquipMetadataCache get() {
        if (!initialized) {
            synchronized (EquipMetadataCache.class) {
                if (!initialized) {
                    initialize();
                }
            }
        }
        return instance;
    }

    // ── Initialization ───────────────────────────────────────────────────

    /** 一次性构建（幂等，线程安全）。构建完成前会阻塞调用方（数秒，仅一次）。 */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        log.info("[EquipMetadataCache] Initializing - scanning all items for equip metadata...");
        long start = System.currentTimeMillis();

        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        // 一次性遍历全量目录（getAllItems 本身每次调用都重建 String.wz 列表，
        // 因此必须且只能在这里调用一次）。顺带构建 wz 存在性索引：
        // cash 判断在 getEquipStats 之后，索引收录所有 stats!=null 且 classify
        // 非 null 的 id（含 cash），供 equipExists() 做 O(1) 存在性校验。
        List<EquipEntry> all = new ArrayList<>();
        Set<Integer> existingIds = new HashSet<>();
        for (Pair<Integer, String> item : ii.getAllItems()) {
            int itemId = item.getLeft();
            EquipType type = classify(itemId);
            if (type == null) {
                continue;
            }
            Map<String, Integer> stats = ii.getEquipStats(itemId);
            if (stats == null) {
                continue;
            }
            existingIds.add(itemId);
            int cash = stats.getOrDefault("cash", 0);
            if (cash == 1) {
                continue;
            }
            int reqLevel = stats.getOrDefault("reqLevel", 0);
            int reqJob = stats.getOrDefault("reqJob", 0);
            all.add(new EquipEntry(itemId, type, deriveGender(itemId), reqLevel, reqJob, false));
        }

        // selectWeightedRandom 依赖升序 id 以偏向经典（低 id）装备，分组前先排序。
        all.sort(Comparator.comparingInt(e -> e.id));

        Map<EquipType, List<EquipEntry>> byType = new LinkedHashMap<>();
        for (EquipType type : EQUIP_RANGES.keySet()) {
            byType.put(type, new ArrayList<>());
        }
        for (EquipEntry entry : all) {
            byType.get(entry.equipType).add(entry);
        }
        byType.replaceAll((k, v) -> Collections.unmodifiableList(v));

        instance = new EquipMetadataCache(Collections.unmodifiableMap(byType), Collections.unmodifiableSet(existingIds));
        initialized = true;

        long elapsed = System.currentTimeMillis() - start;
        log.info("[EquipMetadataCache] Initialized in {}ms - {} non-cash equips cached across {} types, {} wz-existing equip ids indexed",
                elapsed, all.size(), EQUIP_RANGES.size(), existingIds.size());
    }

    private static EquipType classify(int id) {
        for (Map.Entry<EquipType, int[]> e : EQUIP_RANGES.entrySet()) {
            int[] range = e.getValue();
            if (id >= range[0] && id <= range[1]) {
                return e.getKey();
            }
        }
        return null;
    }

    private static int deriveGender(int itemId) {
        // 4th digit convention（对齐 SoloMapling EquipMetadataCache.java:342-349）。
        String s = Integer.toString(itemId);
        if (s.length() >= 4) {
            int digit = Character.getNumericValue(s.charAt(3));
            if (digit >= 0 && digit <= 2) {
                return digit;
            }
        }
        return 2; // default unisex
    }

    // ── Query API ────────────────────────────────────────────────────────

    /** 获取某类型全部非 cash 装备条目（升序 id，不可变）。 */
    public List<EquipEntry> nonCash(EquipType type) {
        return nonCashByType.getOrDefault(type, Collections.emptyList());
    }

    /**
     * wz 存在性校验（O(1) HashSet 查询，无 WZ 访问）：{@code itemId} 是否在
     * initialize() 既有遍历中确认存在（stats!=null 且 classify!=null，含 cash）。
     * 装饰管线选中任何装备 id 后、写入装备栏前必须调用；false 表示该 id 不在
     * v83 wz 中（或超出既有装备区间），不得穿给 bot。首次调用触发懒加载兜底。
     * <p>
     * 语义警示：false ≠ 一定不存在。false 有四种场景：① id 真不存在；
     * ② getEquipStats 返回 null；③ id 在 EQUIP_RANGES 区间外但真实存在
     * （如 170xxxx cash 武器）；④ Character.wz 有但 String.wz 缺失。区间外
     * 真实存在 id 的调用方（BotCustomization.EquipBot）有 getEquipStats 兜底；
     * 池过滤与 QuickEquip/BotDecorateNX 视 false 为不存在——YAML 池维护时仅限
     * 既有区间内 id。
     */
    public static boolean equipExists(int itemId) {
        return get().existingEquipIds.contains(itemId);
    }
}
