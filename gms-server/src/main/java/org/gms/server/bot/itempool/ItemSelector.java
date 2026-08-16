package org.gms.server.bot.itempool;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 移植自 SoloMapling {@code soloMapling.itemPool.ItemSelector}。
 *
 * <p>适配点：
 * <ul>
 *   <li>yamlbeans → snakeyaml（gms 惯例，见 DesirableEquipList）：源 YamlReader 把一切标量读为 String，
 *       snakeyaml 读为原生类型，故 mapToItemNode/mapToScrollNode/ItemDatabase.processItem 改为
 *       {@link #toInteger(Object)} 宽容转换，运行语义与源一致。</li>
 *   <li>文件路径 → classpath 资源（jar 部署安全）：{@code itemConfig/<池名>.yaml} 相对本类包解析为
 *       {@code /org/gms/server/bot/itempool/itemConfig/}。</li>
 *   <li>前导零 variant id（如 {@code 01332003}）：snakeyaml YAML 1.1 会按八进制解析（→373763），
 *       源 yamlbeans 读为字符串再由 toInteger 修正。此处用 {@link DecimalOnlyResolver}
 *       禁用八进制/十六进制隐式解析，前导零数字落为字符串，toInteger 解析结果与源一致。</li>
 *   <li>soloMapling.server.MapleVersionManager.getItemPoolVersion() → {@link #ITEM_POOL_VERSION}（=55）。
 *       池数据最高版本键为 72（chair.yaml），版本常量对齐源 MapleVersionManager=55 语义
 *       （55 以上条目可见），与 {@code ServerConstants.VERSION}=83 无关——用 83 会导致
 *       版本键 55~83 区间的 tier 过滤偏移（v23s/v23a 消失、v49s S→A 等）。</li>
 *   <li>soloMapling.server.SoloMaplingUtilities.pickRandomItem → 本地 Random 抽取（仅 main 测试入口用）。</li>
 * </ul>
 */
public class ItemSelector {

    /**
     * 物品池版本常量：对齐源 SoloMapling {@code MapleVersionManager.getItemPoolVersion()}=55 语义。
     * 池数据最高版本键为 72（chair.yaml），故 55 以上条目全部可见；
     * 不得改用 {@code ServerConstants.VERSION}（83），否则 tier 版本区间过滤整体偏移
     * （55~83 之间的条目 tier 被提前推进一档）。ItemDatabase 同用此常量。
     */
    public static final int ITEM_POOL_VERSION = 55;

    private Map<String, List<Map<String, Object>>> items;

    /**
     * 从 classpath 资源构造（相对本类包），例如 {@code "itemConfig/darkscrolls.yaml"}。
     */
    @SuppressWarnings("unchecked")
    public ItemSelector(String yamlResource) throws Exception {
        items = (Map<String, List<Map<String, Object>>>) (Object) loadYamlResource(yamlResource);
    }

    /**
     * 共享 YAML 加载器：包内其他类（ItemDatabase 等）复用同一解析器配置。
     * 返回顶层 Map；标量类型与 yamlbeans 源行为对齐（数字为 Integer/Number，前导零数字为字符串）。
     */
    public static Map<String, Object> loadYamlResource(String resourcePath) throws Exception {
        InputStream in = ItemSelector.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new java.io.FileNotFoundException("Item pool YAML resource not found: " + resourcePath);
        }
        try (InputStream stream = in) {
            LoaderOptions options = new LoaderOptions();
            Yaml yaml = new Yaml(new SafeConstructor(options),
                    new Representer(new DumperOptions()),
                    new DumperOptions(),
                    options,
                    new DecimalOnlyResolver());
            Object root = yaml.load(stream);
            return (Map<String, Object>) root;
        }
    }

    /**
     * snakeyaml YAML 1.1 解析器会把 {@code 01332003} 这类前导零整数按八进制解析，
     * 与 yamlbeans（标量全读为 String）行为不符。此 resolver 只保留十进制整型隐式规则，
     * 前导零数字落为字符串，由 toInteger 做十进制转换。
     */
    private static final class DecimalOnlyResolver extends Resolver {
        private static final Pattern DECIMAL = Pattern.compile("[-+]?(0|[1-9][0-9_]*)");

        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO");
            addImplicitResolver(Tag.INT, DECIMAL, "-+0123456789");
            addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
            addImplicitResolver(Tag.MERGE, MERGE, "<");
            addImplicitResolver(Tag.NULL, NULL, "~nN\0");
            addImplicitResolver(Tag.NULL, EMPTY, null);
            addImplicitResolver(Tag.TIMESTAMP, TIMESTAMP, "0123456789");
        }
    }

    // Convert a map to an ItemNode instance
    public static ItemNode mapToItemNode(Map<String, Object> itemMap) {
        ItemNode itemNode = new ItemNode(
                (String) itemMap.get("item"),
                toIntegerList(itemMap.get("variant_id")),
                toTierMap(itemMap.get("tier")),
                toPriceMap(itemMap.get("price")));
        return itemNode;
    }

    // Convert a map to an ScrollNode instance
    public static ScrollNode mapToScrollNode(Map<String, Object> itemMap) {
        ScrollNode scrollNode = new ScrollNode(
                (String) itemMap.get("item"),
                toIntegerList(itemMap.get("variant_id")),
                toTierMap(itemMap.get("tier")),
                toPriceMap(itemMap.get("price")),
                toInteger(itemMap.get("success_rate")),
                toInteger(itemMap.get("stat_bonus"))
        );
        return scrollNode;
    }

    // 宽容转换：源 yamlbeans 把 tier/price 键值全读为 String，snakeyaml 读为 Integer —— 统一走 toInteger。
    public static Map<Integer, String> toTierMap(Object raw) {
        if (raw == null) {
            return null;
        }
        Map<Integer, String> out = new HashMap<>();
        ((Map<?, ?>) raw).forEach((k, v) -> out.put(toInteger(k), String.valueOf(v)));
        return out;
    }

    public static Map<Integer, Integer> toPriceMap(Object raw) {
        if (raw == null) {
            return null;
        }
        Map<Integer, Integer> out = new HashMap<>();
        ((Map<?, ?>) raw).forEach((k, v) -> out.put(toInteger(k), toInteger(v)));
        return out;
    }

    public static List<Integer> toIntegerList(Object raw) {
        if (raw == null) {
            return null;
        }
        List<Integer> out = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            out.add(toInteger(o));
        }
        return out;
    }

    // Method to get random item based on itemType, tier, and version
    public ItemNode getRandomItem(String itemType, String tier, int version) {
        List<ItemNode> filteredItems = new ArrayList<>();
        List<Map<String, Object>> itemList = items.get(itemType);
        List<ItemNode> itemNodes = new ArrayList<>();

        if (itemList == null) {
            return null;
        }

        // Iterate through each item map and convert it to an ItemNode
        for (Map<String, Object> itemMap : itemList) {
            ItemNode itemNode = mapToItemNode(itemMap);
            itemNodes.add(itemNode);
        }

        if (itemNodes == null) {
            System.out.println("No items found for item type: " + itemType);
            return null;
        }

        // todo make method
        for (ItemNode item : itemNodes) {
            Map<Integer, String> tierMap = item.getTier();
            for (Entry<Integer, String> entry : tierMap.entrySet()) {
                Object currentTier = entry.getValue();

                int entryVersion;
                try {
                    entryVersion = toInteger(entry.getKey());
                } catch (NumberFormatException e) {
                    System.out.println("Invalid version key: " + entry.getKey());
                    continue;
                }

                int startVersion = entryVersion;
                int endVersion = getEndVersion(tierMap, String.valueOf(entry.getKey()));

                if (tier.equals(currentTier) && version >= startVersion && version < endVersion) {
                    filteredItems.add(item);
                    break;
                }
            }
        }

        if (filteredItems.isEmpty()) {
            return null;
        }

        // Select random item from the filtered list
        Random random = new Random();
        return filteredItems.get(random.nextInt(filteredItems.size()));
    }

    // Helper to get the end version for the tier range
    private int getEndVersion(Map<Integer, String> tierMap, String currentVersionKey) {
        int currentVersion = Integer.parseInt(currentVersionKey);
        int endVersion = Integer.MAX_VALUE;

        for (Object key : tierMap.keySet()) {
            int version; // = toInteger(key);

            try {
                version = toInteger(key);
            } catch (NumberFormatException e) {
                System.out.println("Invalid version key: " + key);
                continue;
            }

            if (version > currentVersion && version < endVersion) {
                endVersion = version;
            }
        }

        return endVersion;
    }

    public static Integer pickRandomVariantId(List<Integer> variantIds) {
        if (variantIds == null || variantIds.isEmpty()) {
            return null;  // Return null if the list is empty or null
        }

        Random random = new Random();
        int randomIndex = random.nextInt(variantIds.size());
        return toInteger(variantIds.get(randomIndex));
    }

    public static int getPriceForVersion(Map<Integer, Integer> versionPriceMap) {
        // Sort the map by key using a TreeMap to ensure natural ordering
        SortedMap<Integer, Integer> sortedMap = new TreeMap<>(versionPriceMap);

        int result = -1;
        int version = ITEM_POOL_VERSION;
        for (Map.Entry<Integer, Integer> entry : sortedMap.entrySet()) {
            int currentVersion = toInteger(entry.getKey());
            if (version < currentVersion) {
                break;  // Exit loop if the input version is lower than the current range start
            }
            result = toInteger(entry.getValue());
        }
        return result;
    }

    public static Integer toInteger(Object value) {
        if (value instanceof Integer) {
            return (Integer) value; // Already an Integer
        } else if (value instanceof String) {
            try {
                return Integer.parseInt((String) value); // Parse String to Integer
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("String cannot be parsed to Integer: " + value);
            }
        } else if (value instanceof Number) {
            return ((Number) value).intValue(); // Convert Number to Integer
        } else {
            throw new IllegalArgumentException("Unsupported type: " + value.getClass().getName());
        }
    }

    /**
     * gms 增强：按资源路径缓存的 ItemSelector 实例池。源实现每次调用都 {@code new ItemSelector}
     * 全量解析 YAML，摆摊管线每摊多位商品高频调用造成重复解析（2 核环境增强，源无缓存）。
     * 构造失败不写缓存（computeIfAbsent 抛异常即弃），下次调用自动重试。
     */
    private static final ConcurrentHashMap<String, ItemSelector> CACHE = new ConcurrentHashMap<>();

    public static ItemNode getRandomItemFull(String itemPool, String itemType, String tier) {
        try {
            int version = ITEM_POOL_VERSION;
            String resource = "itemConfig/" + itemPool;
            ItemSelector itemSelector = CACHE.computeIfAbsent(resource, key -> {
                try {
                    return new ItemSelector(key);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to load item pool resource: " + key, e);
                }
            });
            ItemNode randomItem = itemSelector.getRandomItem(itemType, tier, version);
            if (randomItem != null) {
                return randomItem;
            } else {
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static ScrollNode getScrollNodeData(int itemId) {
        return ItemDatabase.getInstance().getScrollData(itemId);
    }

    public static void main(String[] args) {
        Random random = new Random();
        for (int i = 0; i < 30; i++) {
            List<String> ALL_SCROLLS = List.of("Earring", "Overall", "Claw", "Shoes", "Gloves", "Cape", "ETC");
            List<String> ALL_DARK_SCROLLS = List.of(
                    "Earring", "Overall", "Shoes",
                    "Gloves", "Cape", "Hat", "Top", "Bottom", "Shield", "ETC");

            String randomItem = ALL_DARK_SCROLLS.get(random.nextInt(ALL_DARK_SCROLLS.size()));
            getRandomItemFull("darkscrolls.yaml", randomItem, "B");
        }
    }
}
