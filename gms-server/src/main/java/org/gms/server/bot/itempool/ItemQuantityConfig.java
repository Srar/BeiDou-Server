package org.gms.server.bot.itempool;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 移植自 SoloMapling {@code soloMapling.itemPool.ItemQuantityConfig}。
 *
 * <p>适配点：源用 yamlbeans {@code reader.read(ItemQuantityConfig.class)} 从文件路径读，
 * gms 改为 snakeyaml + classpath 资源，并手动递归转换为 POJO（snakeyaml 对嵌套泛型 Map
 * 的 loadAs 不可靠），字段结构与源完全一致。资源位于
 * {@code /org/gms/server/bot/itempool/itemConfig/itemQuantities.yaml}。
 */
public class ItemQuantityConfig {
    public static class TierRange {
        public int min;
        public int max;
    }

    public static class ItemType {
        public Map<String, TierRange> tiers;
    }

    public Map<String, ItemType> itemQuantities;

    /**
     * 从 classpath 资源读取数量配置（相对本类包）。失败返回 null（与源行为一致）。
     */
    @SuppressWarnings("unchecked")
    public static ItemQuantityConfig readYaml(InputStream in) {
        try {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) {
                return null;
            }
            Map<String, Object> rootMap = (Map<String, Object>) root;
            Object rawQuantities = rootMap.get("itemQuantities");
            if (!(rawQuantities instanceof Map)) {
                return null;
            }

            ItemQuantityConfig config = new ItemQuantityConfig();
            config.itemQuantities = new HashMap<>();

            for (Map.Entry<String, Object> typeEntry : ((Map<String, Object>) rawQuantities).entrySet()) {
                Object rawType = typeEntry.getValue();
                if (!(rawType instanceof Map)) {
                    continue;
                }
                ItemType itemType = new ItemType();
                itemType.tiers = new HashMap<>();

                Object rawTiers = ((Map<String, Object>) rawType).get("tiers");
                if (rawTiers instanceof Map) {
                    for (Map.Entry<String, Object> tierEntry : ((Map<String, Object>) rawTiers).entrySet()) {
                        Object rawRange = tierEntry.getValue();
                        if (!(rawRange instanceof Map)) {
                            continue;
                        }
                        Map<String, Object> rangeMap = (Map<String, Object>) rawRange;
                        TierRange range = new TierRange();
                        range.min = ((Number) rangeMap.get("min")).intValue();
                        range.max = ((Number) rangeMap.get("max")).intValue();
                        itemType.tiers.put(tierEntry.getKey(), range);
                    }
                }
                config.itemQuantities.put(typeEntry.getKey(), itemType);
            }
            return config;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
