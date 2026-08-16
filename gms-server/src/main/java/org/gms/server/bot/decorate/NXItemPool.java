package org.gms.server.bot.decorate;

import org.gms.constants.inventory.EquipType;
import org.gms.server.ItemInformationProvider;
import org.gms.server.bot.itempool.EquipMetadataCache;
import org.gms.util.Pair;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Loads and serves the curated list of NX / cash cosmetic equips.
 * No level filter (NX has no reqLevel). Gender is resolved per item at load
 * time: explicit YAML override > body-slot ID-digit convention > unisex default.
 *
 * <p>Load-time filtering: ids that don't exist in WZ (checked once via
 * {@link EquipMetadataCache#equipExists(int)}, O(1) HashSet) are dropped from
 * the pool, so every runtime pick is guaranteed to be a real WZ equip.
 *
 * Call {@link #load()} once at startup (BotDecorateNX does this lazily).
 * Use {@link #getRandom(String, int)} to pick a random item for a given
 * category and bot gender.
 */
public class NXItemPool {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NXItemPool.class);

    // Classpath resource (mirrors the SoloMapling in-tree YAML location, relocated to
    // src/main/resources/org/gms/server/bot/decorate/). Loaded via getResourceAsStream
    // so it works both from an exploded classpath and from inside the jar.
    private static final String YAML_PATH = "NXItemPool.yaml";

    public static final int GENDER_MALE = 0;
    public static final int GENDER_FEMALE = 1;
    public static final int GENDER_UNISEX = 2;

    // Categories whose gender follows the v83 body-equip ID-digit convention.
    // Non-body categories (weapons/earrings/face/eye/rings) don't encode gender
    // in the ID the same way, so they default to unisex unless the YAML says
    // otherwise.
    private static final Set<String> BODY_SLOT_CATEGORIES = Set.of(
            "caps", "tops", "bottoms", "overalls", "shoes", "gloves", "capes"
    );

    public static class PoolItem {
        final int id;
        final int gender; // 0 male, 1 female, 2 unisex

        PoolItem(int id, int gender) {
            this.id = id;
            this.gender = gender;
        }
    }

    /**
     * Maps NXItemPool category names to EquipType(s) for auto-population
     * from {@link ItemInformationProvider} cash items when the YAML list is empty.
     * Weapons span many EquipTypes so they get their own array.
     */
    private static final Map<String, EquipType[]> CATEGORY_TO_EQUIP_TYPES = Map.ofEntries(
            Map.entry("caps",      new EquipType[]{EquipType.CAP}),
            Map.entry("tops",      new EquipType[]{EquipType.COAT}),
            Map.entry("bottoms",   new EquipType[]{EquipType.PANTS}),
            Map.entry("overalls",  new EquipType[]{EquipType.LONGCOAT}),
            Map.entry("shoes",     new EquipType[]{EquipType.SHOES}),
            Map.entry("gloves",    new EquipType[]{EquipType.GLOVES}),
            Map.entry("capes",     new EquipType[]{EquipType.CAPE}),
            Map.entry("earrings",  new EquipType[]{EquipType.EARRING}),
            Map.entry("face",      new EquipType[]{EquipType.FACE}),
            Map.entry("eye",       new EquipType[]{EquipType.ACCESSORY}),
            Map.entry("rings",     new EquipType[]{EquipType.RING}),
            Map.entry("weapons",   new EquipType[]{
                    EquipType.SWORD, EquipType.AXE, EquipType.MACE, EquipType.DAGGER,
                    EquipType.WAND, EquipType.STAFF, EquipType.SWORD_2H, EquipType.AXE_2H,
                    EquipType.MACE_2H, EquipType.SPEAR, EquipType.POLEARM, EquipType.BOW,
                    EquipType.CROSSBOW, EquipType.CLAW, EquipType.KNUCKLER, EquipType.PISTOL
            })
    );

    // Rebuilt cash-item id ranges. SoloMapling's EquipMetadataCache (not ported) derived
    // these from its EQUIP_RANGES table; we replicate that full table verbatim (including
    // FACE and RING, which gms' ItemInformationProvider.EQUIP_TYPE_RANGES does not carry)
    // so the getCashByType rebuild below sees the same item space.
    private static final Map<EquipType, int[]> EQUIP_RANGES = Map.ofEntries(
            Map.entry(EquipType.CAP,       new int[]{1000000, 1003073}),
            Map.entry(EquipType.FACE,      new int[]{1012000, 1012200}),
            Map.entry(EquipType.ACCESSORY, new int[]{1022000, 1022104}),
            Map.entry(EquipType.EARRING,   new int[]{1032000, 1032075}),
            Map.entry(EquipType.COAT,      new int[]{1040000, 1049000}),
            Map.entry(EquipType.LONGCOAT,  new int[]{1050000, 1052234}),
            Map.entry(EquipType.PANTS,     new int[]{1060000, 1062119}),
            Map.entry(EquipType.SHOES,     new int[]{1070000, 1072437}),
            Map.entry(EquipType.GLOVES,    new int[]{1080000, 1082262}),
            Map.entry(EquipType.SHIELD,    new int[]{1092000, 1092062}),
            Map.entry(EquipType.CAPE,      new int[]{1102000, 1102236}),
            Map.entry(EquipType.RING,      new int[]{1112000, 1112400}),
            Map.entry(EquipType.SWORD,     new int[]{1302000, 1302133}),
            Map.entry(EquipType.AXE,       new int[]{1312000, 1312046}),
            Map.entry(EquipType.MACE,      new int[]{1322000, 1322074}),
            Map.entry(EquipType.DAGGER,    new int[]{1332000, 1332088}),
            Map.entry(EquipType.WAND,      new int[]{1372000, 1372046}),
            Map.entry(EquipType.STAFF,     new int[]{1382000, 1382062}),
            Map.entry(EquipType.SWORD_2H,  new int[]{1402000, 1402072}),
            Map.entry(EquipType.AXE_2H,    new int[]{1412000, 1412046}),
            Map.entry(EquipType.MACE_2H,   new int[]{1422000, 1422047}),
            Map.entry(EquipType.SPEAR,     new int[]{1432000, 1432061}),
            Map.entry(EquipType.POLEARM,   new int[]{1442000, 1442103}),
            Map.entry(EquipType.BOW,       new int[]{1452000, 1452085}),
            Map.entry(EquipType.CROSSBOW,  new int[]{1462000, 1462075}),
            Map.entry(EquipType.CLAW,      new int[]{1472000, 1472100}),
            Map.entry(EquipType.KNUCKLER,  new int[]{1482000, 1482046}),
            Map.entry(EquipType.PISTOL,    new int[]{1492000, 1492048})
    );

    private static final Map<String, List<PoolItem>> pools = new HashMap<>();
    private static boolean loaded = false;

    @SuppressWarnings("unchecked")
    public static synchronized void load() {
        if (loaded) return;

        try (InputStream in = NXItemPool.class.getResourceAsStream(YAML_PATH)) {
            if (in == null) {
                System.err.println("[NXItemPool] YAML resource not found: " + YAML_PATH);
                return;
            }
            Yaml yaml = new Yaml();
            Map<String, Object> root = (Map<String, Object>) yaml.load(in);
            if (root == null) root = Collections.emptyMap();

            int itemCount = 0;
            int filteredCount = 0;
            for (Map.Entry<String, Object> entry : root.entrySet()) {
                String category = entry.getKey();
                Object val = entry.getValue();
                if (!(val instanceof List)) continue;

                List<PoolItem> list = new ArrayList<>();
                for (Object raw : (List<?>) val) {
                    PoolItem item = parseEntry(raw, category);
                    if (item != null) {
                        // wz 存在性过滤（一次性 O(n)，HashSet O(1) 判定）：剔除 wz 中
                        // 不存在的 id，保证运行时随机到的 NX 装备全部合法。
                        if (EquipMetadataCache.equipExists(item.id)) {
                            list.add(item);
                            itemCount++;
                        } else {
                            filteredCount++;
                        }
                    }
                }
                pools.put(category, list);
            }

            // Auto-populate empty categories from ItemInformationProvider cash items.
            // SoloMapling gates this behind EquipMetadataCache.isInitialized(); gms'
            // ItemInformationProvider is always available, so we always run this pass.
            int cacheCount = 0;
            for (Map.Entry<String, EquipType[]> mapping : CATEGORY_TO_EQUIP_TYPES.entrySet()) {
                String category = mapping.getKey();
                List<PoolItem> existing = pools.get(category);
                if (existing != null && !existing.isEmpty()) continue;

                List<PoolItem> cacheItems = loadCashItems(category, mapping.getValue());
                // isCash() 已保证 stats!=null，但统一过一遍存在性索引兜底（应恒为 0 剔除）。
                List<PoolItem> validItems = new ArrayList<>();
                for (PoolItem item : cacheItems) {
                    if (EquipMetadataCache.equipExists(item.id)) {
                        validItems.add(item);
                    } else {
                        filteredCount++;
                    }
                }
                if (!validItems.isEmpty()) {
                    pools.put(category, validItems);
                    cacheCount += validItems.size();
                    System.out.println("[NXItemPool]   Auto-populated '" + category
                            + "' with " + validItems.size() + " cash items from ItemInformationProvider");
                }
            }

            loaded = true;
            System.out.println("[NXItemPool] Loaded " + itemCount + " curated + "
                    + cacheCount + " auto items across " + pools.size() + " categories");
            if (filteredCount > 0) {
                log.info("[NXItemPool] Filtered {} ids not found in WZ", filteredCount);
            }
        } catch (Exception e) {
            System.err.println("[NXItemPool] Failed to load YAML: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Rebuild of SoloMapling's EquipMetadataCache.getCashByType(EquipType):
     * pulls all cash equips for the given EquipTypes by scanning
     * {@link ItemInformationProvider#getAllItems()} and filtering with
     * {@link ItemInformationProvider#isCash(int)}. Pure in-memory — no WZ access.
     */
    private static List<PoolItem> loadCashItems(String category, EquipType[] equipTypes) {
        List<PoolItem> result = new ArrayList<>();
        ItemInformationProvider iip = ItemInformationProvider.getInstance();
        for (EquipType eqType : equipTypes) {
            int[] range = EQUIP_RANGES.get(eqType);
            if (range == null) continue;
            for (Pair<Integer, String> item : iip.getAllItems()) {
                int id = item.getLeft();
                if (id >= range[0] && id <= range[1] && iip.isCash(id)) {
                    result.add(new PoolItem(id, deriveGender(id, category, null)));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static PoolItem parseEntry(Object raw, String category) {
        if (raw == null) return null;
        if (raw instanceof Number || raw instanceof String) {
            int id = toInt(raw);
            return new PoolItem(id, deriveGender(id, category, null));
        }
        if (raw instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) raw;
            int id = toInt(m.get("id"));
            Integer override = parseGender(m.get("gender"));
            return new PoolItem(id, deriveGender(id, category, override));
        }
        return null;
    }

    private static int deriveGender(int itemId, String category, Integer override) {
        if (override != null) return override;
        if (BODY_SLOT_CATEGORIES.contains(category)) {
            return (itemId / 1000) % 10;
        }
        return GENDER_UNISEX;
    }

    private static Integer parseGender(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().toLowerCase();
        switch (s) {
            case "male":
            case "0":
                return GENDER_MALE;
            case "female":
            case "1":
                return GENDER_FEMALE;
            case "unisex":
            case "2":
                return GENDER_UNISEX;
            default:
                return null;
        }
    }

    /**
     * Pick a random NX item from the given category for a bot of the given
     * gender. Filters by gender (unisex items always pass). Returns null if the
     * category is empty, the pool isn't loaded, or no item is eligible.
     */
    public static Integer getRandom(String category, int botGender) {
        if (!loaded) return null;
        List<PoolItem> list = pools.get(category);
        if (list == null || list.isEmpty()) return null;

        List<PoolItem> eligible = new ArrayList<>(list.size());
        for (PoolItem item : list) {
            if (item.gender == GENDER_UNISEX || item.gender == botGender) {
                eligible.add(item);
            }
        }
        if (eligible.isEmpty()) return null;

        return eligible.get(ThreadLocalRandom.current().nextInt(eligible.size())).id;
    }

    private static int toInt(Object obj) {
        if (obj instanceof Number) return ((Number) obj).intValue();
        if (obj instanceof String) return Integer.parseInt((String) obj);
        return 0;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * Force a reload — clears existing pools and re-reads YAML + cache.
     * Useful for hot-reloading curated lists without restarting the server.
     */
    public static synchronized void forceReload() {
        pools.clear();
        loaded = false;
        load();
    }
}
