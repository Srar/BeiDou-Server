package org.gms.server.bot.itempool;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SoloMapling DesirableEquipList 移植：把 itemConfig/desirableEquips.yaml 里的白名单装备 ID
 * 加载为内存集合，供对话/掉落排序判定「值得注意的装备」。源用 yamlbeans 直接读源树文件，
 * gms 改用 classpath 资源 + snakeyaml（org.yaml.snakeyaml，已在 P3-B/P5-D 落地），加载方式
 * 与 NXItemPool / TownChatterLines 一致。与源相同：启动期显式 {@link #load()} 一次，
 * {@link #isDesirable(int)} 只读集合、不触发 IO。
 */
public final class DesirableEquipList {

    // Classpath 资源（镜像 SoloMapling 源内 YAML 位置，迁到 resources 对应包路径）。
    private static final String YAML_PATH = "itemConfig/desirableEquips.yaml";

    private static final Set<Integer> desirableIds = new HashSet<>();
    private static boolean loaded = false;

    private DesirableEquipList() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        long start = System.currentTimeMillis();
        try (InputStream in = DesirableEquipList.class.getResourceAsStream(YAML_PATH)) {
            if (in == null) {
                System.err.println("[DesirableEquipList] YAML resource not found: " + YAML_PATH);
                loaded = true;
                return;
            }
            Yaml yaml = new Yaml();
            @SuppressWarnings("unchecked")
            Map<String, Object> categories = (Map<String, Object>) yaml.load(in);
            if (categories == null) {
                loaded = true;
                return;
            }
            for (Map.Entry<String, Object> entry : categories.entrySet()) {
                Object ids = entry.getValue();
                if (!(ids instanceof List<?> list)) {
                    continue;
                }
                for (Object raw : list) {
                    if (raw == null) {
                        continue;
                    }
                    try {
                        desirableIds.add(Integer.parseInt(raw.toString().trim()));
                    } catch (NumberFormatException ignored) {
                        // 非数字白名单项（注释/占位）——跳过
                    }
                }
            }
            loaded = true;
            System.out.println("[DesirableEquipList] Loaded " + desirableIds.size() + " whitelisted item IDs in "
                    + (System.currentTimeMillis() - start) + "ms");
        } catch (Exception e) {
            System.err.println("[DesirableEquipList] Failed to load: " + e.getMessage());
            loaded = true;
        }
    }

    public static boolean isDesirable(int itemId) {
        return desirableIds.contains(itemId);
    }

    public static Set<Integer> getAll() {
        return Collections.unmodifiableSet(desirableIds);
    }
}
