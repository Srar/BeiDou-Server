package org.gms.server.bot.types;

import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * DropGame 掉落池：按 tier（medium/elite）从 YAML 资源加载普通与特殊掉落，
 * 33% 概率落入特殊池，按权重掷取条目。
 */
@Slf4j
public class DropGameLootPool {

    // Absolute classpath path: a leading '/' anchors the lookup at the classpath root, so
    // getResourceAsStream resolves it against src/main/resources instead of the class's own
    // package (org/gms/server/bot/types), where the file does not exist.
    private static final String LOOT_POOL_PATH = "/org/gms/server/bot/dialogue/BotDialoguePack/DropGameLootPool.yaml";
    private static final Random random = new Random();
    private static final int SPECIAL_CHANCE_PERCENT = 33;

    private final List<LootEntry> entries = new ArrayList<>();
    private int totalWeight = 0;

    private final List<LootEntry> specialEntries = new ArrayList<>();
    private int specialTotalWeight = 0;

    public static class LootEntry {
        public final int itemId;
        public final int weight;
        public final boolean isEquip;

        public LootEntry(int itemId, int weight, boolean isEquip) {
            this.itemId = itemId;
            this.weight = weight;
            this.isEquip = isEquip;
        }
    }

    public static DropGameLootPool load(String tier) {
        DropGameLootPool pool = new DropGameLootPool();
        try (InputStream in = DropGameLootPool.class.getResourceAsStream(LOOT_POOL_PATH)) {
            if (in == null) {
                log.warn("[DropGameLootPool] YAML resource not found: {}", LOOT_POOL_PATH);
                return pool;
            }
            Yaml yaml = new Yaml();
            Map<String, Object> root = (Map<String, Object>) yaml.load(in);
            if (root == null) {
                return pool;
            }
            Map<String, Object> tierNode = (Map<String, Object>) root.get(tier);
            if (tierNode == null) {
                log.warn("[DropGameLootPool] No tier found for: {}", tier);
                return pool;
            }

            loadEntries(tierNode, "items", pool.entries, pool);
            loadEntries(tierNode, "special_items", pool.specialEntries, pool);
        } catch (Exception e) {
            log.warn("[DropGameLootPool] Failed to load tier: {}", tier, e);
        }
        return pool;
    }

    private static void loadEntries(Map<String, Object> tierNode, String key,
                                     List<LootEntry> targetList, DropGameLootPool pool) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) tierNode.get(key);
        if (items == null) return;

        int weight = 0;
        for (Map<String, Object> entry : items) {
            int itemId = Integer.parseInt(String.valueOf(entry.get("id")));
            int w = Integer.parseInt(String.valueOf(entry.get("weight")));
            boolean isEquip = entry.containsKey("equip") && Boolean.parseBoolean(String.valueOf(entry.get("equip")));
            targetList.add(new LootEntry(itemId, w, isEquip));
            weight += w;
        }

        if (key.equals("special_items")) {
            pool.specialTotalWeight = weight;
        } else {
            pool.totalWeight = weight;
        }
    }

    public LootEntry rollItem() {
        if (entries.isEmpty() && specialEntries.isEmpty()) {
            return null;
        }

        boolean useSpecial = !specialEntries.isEmpty()
                && random.nextInt(100) < SPECIAL_CHANCE_PERCENT;

        if (useSpecial) {
            return rollFrom(specialEntries, specialTotalWeight);
        }

        if (!entries.isEmpty()) {
            return rollFrom(entries, totalWeight);
        }

        return rollFrom(specialEntries, specialTotalWeight);
    }

    private LootEntry rollFrom(List<LootEntry> list, int total) {
        if (list.isEmpty() || total == 0) return null;
        int roll = random.nextInt(total);
        int cumulative = 0;
        for (LootEntry entry : list) {
            cumulative += entry.weight;
            if (roll < cumulative) {
                return entry;
            }
        }
        return list.get(list.size() - 1);
    }

    public boolean isEmpty() {
        return entries.isEmpty() && specialEntries.isEmpty();
    }

    public int size() {
        return entries.size() + specialEntries.size();
    }
}
