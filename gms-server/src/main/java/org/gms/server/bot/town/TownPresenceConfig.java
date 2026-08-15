package org.gms.server.bot.town;

import org.yaml.snakeyaml.Yaml;

import java.awt.Point;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 加载 TownPresence.yaml：每个城镇的环境人口计划。YAML 优先 + 按设计定数。
 * 每个城镇列一个等级带与一个地图家族（城镇主图 + 室内子图），每图有其社交 bot 数量。
 * 从 classpath 资源读取（同其他 bot 配置）。标量回来是字符串，整数做防御性解析。
 */
public final class TownPresenceConfig {

    private TownPresenceConfig() {
    }

    private static final String YAML_PATH = "TownPresence.yaml";

    public record MapShare(int mapId, int count, TownOverrides overrides) {
    }

    public record TownEntry(String name, int levelLo, int levelHi, int wanderers, List<MapShare> maps,
                            String dialogueOverride) {
        public int mainMapId() {
            return maps.isEmpty() ? -1 : maps.get(0).mapId();
        }
    }

    private static volatile List<TownEntry> cached;

    public static List<TownEntry> towns() {
        List<TownEntry> local = cached;
        if (local == null) {
            local = load();
            cached = local;
        }
        return local;
    }

    public static List<TownEntry> reload() {
        cached = load();
        return cached;
    }

    public static Set<Integer> allTownMapIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (TownEntry t : towns()) {
            for (MapShare m : t.maps()) {
                ids.add(m.mapId());
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static List<TownEntry> load() {
        List<TownEntry> out = new ArrayList<>();
        try (InputStream in = TownPresenceConfig.class.getResourceAsStream(YAML_PATH)) {
            if (in == null) {
                System.out.println("[TownPresenceConfig] YAML resource not found: " + YAML_PATH);
                return out;
            }
            Yaml yaml = new Yaml();
            Map<String, Object> root = (Map<String, Object>) yaml.load(in);
            if (root == null) {
                return out;
            }
            Object townsNode = root.get("towns");
            if (!(townsNode instanceof List<?> townList)) {
                return out;
            }
            Map<Integer, List<Point>> sidecarPins = TownPinsStore.load(); // 只读一次 marked-pins 文件
            for (Object t : townList) {
                if (!(t instanceof Map<?, ?> town)) {
                    continue;
                }
                String name = str(town.get("name"), "town");
                int lo = toInt(town.get("level_lo"), 10);
                int hi = toInt(town.get("level_hi"), lo);
                int wanderers = toInt(town.get("wanderers"), 0);
                String dialogue = str(town.get("dialogue"), null);
                List<MapShare> shares = new ArrayList<>();
                Object mapsNode = town.get("maps");
                if (mapsNode instanceof List<?> mapList) {
                    for (Object m : mapList) {
                        if (!(m instanceof Map<?, ?> mm)) {
                            continue;
                        }
                        int mapId = toInt(mm.get("map"), -1);
                        int count = toInt(mm.get("count"), 0);
                        if (mapId > 0 && count > 0) {
                            shares.add(new MapShare(mapId, count,
                                    parseOverrides(mm, sidecarPins.getOrDefault(mapId, List.of()))));
                        }
                    }
                }
                if (!shares.isEmpty()) {
                    out.add(new TownEntry(name, lo, hi, wanderers, shares, dialogue));
                }
            }
        } catch (Exception e) {
            System.out.println("[TownPresenceConfig] failed to load " + YAML_PATH + ": " + e.getMessage());
            return new ArrayList<>();
        }
        return out;
    }

    public static TownOverrides overridesFor(int mapId) {
        for (TownEntry t : towns()) {
            for (MapShare m : t.maps()) {
                if (m.mapId() == mapId) {
                    return m.overrides();
                }
            }
        }
        List<Point> sidecar = TownPinsStore.forMap(mapId);
        return sidecar.isEmpty() ? TownOverrides.EMPTY : new TownOverrides(List.of(), List.of(), sidecar);
    }

    private static TownOverrides parseOverrides(Map<?, ?> mm, List<Point> sidecarPins) {
        List<TownOverrides.Zone> ban = parseZones(mm.get("ban"), 1.0);
        List<TownOverrides.Zone> boost = parseZones(mm.get("boost"), 2.0);
        List<Point> pins = new ArrayList<>();
        Object pinsNode = mm.get("pins");
        if (pinsNode instanceof List<?> pinList) {
            for (Object p : pinList) {
                if (p instanceof Map<?, ?> pm) {
                    int x = toInt(pm.get("x"), Integer.MIN_VALUE);
                    int y = toInt(pm.get("y"), Integer.MIN_VALUE);
                    if (x != Integer.MIN_VALUE && y != Integer.MIN_VALUE) {
                        pins.add(new Point(x, y));
                    }
                }
            }
        }
        pins.addAll(sidecarPins); // 合并 marked pins
        if (ban.isEmpty() && boost.isEmpty() && pins.isEmpty()) {
            return TownOverrides.EMPTY;
        }
        return new TownOverrides(ban, boost, pins);
    }

    private static List<TownOverrides.Zone> parseZones(Object node, double defaultMult) {
        List<TownOverrides.Zone> out = new ArrayList<>();
        if (!(node instanceof List<?> list)) {
            return out;
        }
        for (Object z : list) {
            if (!(z instanceof Map<?, ?> zm)) {
                continue;
            }
            int x1 = toInt(zm.get("x1"), Integer.MIN_VALUE);
            int x2 = toInt(zm.get("x2"), Integer.MAX_VALUE);
            int y1 = toInt(zm.get("y1"), Integer.MIN_VALUE);
            int y2 = toInt(zm.get("y2"), Integer.MAX_VALUE);
            double mult = toDouble(zm.get("mult"), defaultMult);
            out.add(new TownOverrides.Zone(x1, y1, x2, y2, mult));
        }
        return out;
    }

    private static double toDouble(Object o, double fallback) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o != null) {
            try {
                return Double.parseDouble(o.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private static int toInt(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(o.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through to fallback
            }
        }
        return fallback;
    }

    private static String str(Object o, String fallback) {
        return o != null ? o.toString() : fallback;
    }
}
