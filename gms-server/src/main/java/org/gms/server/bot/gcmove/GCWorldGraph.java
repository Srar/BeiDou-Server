package org.gms.server.bot.gcmove;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.provider.Data;
import org.gms.provider.DataDirectoryEntry;
import org.gms.provider.DataEntry;
import org.gms.provider.DataFileEntry;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;
import org.gms.server.bot.travel.BotScriptedWarp;
import org.gms.server.maps.Portal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/*
 * Portals-only world connectivity graph for GCTravel: which map walkably connects to
 * which. Scanned once from Map.wz — non-door, unscripted portals with a real target map,
 * following info/link exactly like MapFactory so the edge set matches the runtime map.
 *
 * Pure-movement scope: GreenCat's taxi / ferry / return-scroll / scripted-entrance content
 * edges are intentionally dropped. A route that would need one of those returns null from
 * .route and GCTravel warps that hop instead. Built lazily in-memory on first use
 * (~5k XMLs in a few seconds on a worker pool); not disk-cached.
 */
// Ported from GreenCatMS. Credit: NutNNut.
final class GCWorldGraph {
    private GCWorldGraph() {
    }

    private static final Logger log = LoggerFactory.getLogger(GCWorldGraph.class);

    private static final int NO_TARGET_MAPID = 999999999; // tm of spawn points / doors
    private static final int[] EMPTY = new int[0];
    private static volatile Map<Integer, int[]> edges;

    static Map<Integer, int[]> get() {
        Map<Integer, int[]> cached = edges;
        if (cached != null) {
            return cached;
        }
        synchronized (GCWorldGraph.class) {
            if (edges == null) {
                edges = build();
            }
            return edges;
        }
    }

    static boolean isReady() {
        return edges != null;
    }

    /*
     * Raw walkable-portal neighbours of mapId (the visually-adjacent maps) — for the LOD halo.
     * Unlike .neighbors this excludes taxi destinations, and unlike .get it never
     * triggers the world-graph build: returns empty until the graph already exists.
     */
    static int[] portalNeighbors(int mapId) {
        Map<Integer, int[]> g = edges; // read the volatile directly — do NOT force a build
        return g == null ? EMPTY : g.getOrDefault(mapId, EMPTY);
    }

    static int mapCount() {
        return get().size();
    }

    /*
     * Shortest portal-hop route from fromMapId to toMapId: the ordered map ids to
     * enter, ending with toMapId. Empty list when already there; null when not
     * reachable by walkable portals within maxHops (caller warps instead).
     */
    static List<Integer> route(int fromMapId, int toMapId, int maxHops) {
        if (fromMapId == toMapId) {
            return List.of();
        }
        if (maxHops <= 0) {
            return null;
        }
        Map<Integer, int[]> g = get();
        Map<Integer, Integer> cameFrom = new HashMap<>();
        ArrayDeque<Integer> frontier = new ArrayDeque<>();
        cameFrom.put(fromMapId, fromMapId);
        frontier.add(fromMapId);
        int depth = 0;
        while (!frontier.isEmpty() && depth < maxHops) {
            depth++;
            for (int level = frontier.size(); level > 0; level--) {
                int current = frontier.poll();
                for (int next : neighbors(g, current)) {
                    if (cameFrom.putIfAbsent(next, current) != null) {
                        continue;
                    }
                    if (next == toMapId) {
                        return reconstruct(cameFrom, fromMapId, toMapId);
                    }
                    frontier.add(next);
                }
            }
        }
        return null;
    }

    /* Walkable-portal neighbours plus taxi (cab) destinations and curated scripted warps (e.g. the
     * subway entrance), so town↔town and scripted-only training routes are routable. */
    private static int[] neighbors(Map<Integer, int[]> g, int mapId) {
        int[] portals = g.getOrDefault(mapId, EMPTY);
        int[] taxi = GCTaxi.destinations(mapId);
        int[] warp = BotScriptedWarp.destinations(mapId);
        if (taxi.length == 0 && warp.length == 0) {
            return portals;
        }
        int[] all = new int[portals.length + taxi.length + warp.length];
        System.arraycopy(portals, 0, all, 0, portals.length);
        System.arraycopy(taxi, 0, all, portals.length, taxi.length);
        System.arraycopy(warp, 0, all, portals.length + taxi.length, warp.length);
        return all;
    }

    private static List<Integer> reconstruct(Map<Integer, Integer> cameFrom, int from, int to) {
        List<Integer> hops = new ArrayList<>();
        for (int at = to; at != from; at = cameFrom.get(at)) {
            hops.add(at);
        }
        Collections.reverse(hops);
        return List.copyOf(hops);
    }

    private static Map<Integer, int[]> build() {
        long startedAt = System.currentTimeMillis();
        log.info("GCWorldGraph build started: scanning Map.wz for walkable-portal connectivity...");
        Map<Integer, int[]> out = new ConcurrentHashMap<>();
        try {
            ThreadLocal<DataProvider> mapSources =
                    ThreadLocal.withInitial(() -> DataProviderFactory.getDataProvider(WZFiles.MAP));
            ExecutorService pool = Executors.newFixedThreadPool(
                    Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())));
            // ADAPTATION: SoloMapling enumerated map files by walking the extracted WZ directory tree
            // directly (Files.list over Map/Map{area}/*.img.xml). gms exposes the same tree through the
            // DataProvider root — MapFactory.resolveDir does exactly this — so enumerate
            // Map/Map{area}/*.img entries here instead of touching the filesystem. Semantics unchanged:
            // the 9 area directories (Map0..Map9) and only *.img map files.
            DataDirectoryEntry root = DataProviderFactory.getDataProvider(WZFiles.MAP).getRoot();
            DataEntry mapEntry = root.getEntry("Map");
            if (mapEntry instanceof DataDirectoryEntry mapDir) {
                for (DataDirectoryEntry areaDir : mapDir.getSubdirectories()) {
                    String areaName = areaDir.getName();
                    if (!areaName.startsWith("Map")) {
                        continue; // skip non-area subdirs (Back/Obj/Tile/WorldMap/...)
                    }
                    final int area;
                    try {
                        area = Integer.parseInt(areaName.substring("Map".length()));
                    } catch (NumberFormatException ignored) {
                        continue; // non-numeric subdir under Map/ — skip
                    }
                    for (DataFileEntry file : areaDir.getFiles()) {
                        String name = file.getName();
                        if (!name.endsWith(".img")) {
                            continue;
                        }
                        final int mapId;
                        try {
                            mapId = Integer.parseInt(name.substring(0, name.length() - ".img".length()));
                        } catch (NumberFormatException ignored) {
                            continue; // non-map file (e.g. AreaCode.img)
                        }
                        pool.execute(() -> {
                            try {
                                readMap(mapSources.get(), area, mapId, out);
                            } catch (RuntimeException ignored) {
                                // skip an unparseable map file
                            }
                        });
                    }
                }
            } else {
                log.warn("GCWorldGraph: WZ root missing Map node (WZ 根目录缺少 Map 节点); mapEntry={} — world graph will be empty",
                        mapEntry);
            }
            pool.shutdown();
            try {
                pool.awaitTermination(10, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("GCWorldGraph build finished: {} maps indexed in {} ms",
                    out.size(), System.currentTimeMillis() - startedAt);
            return Collections.unmodifiableMap(out);
        } catch (Throwable t) {
            log.error("GCWorldGraph build failed after {} ms; world graph remains unbuilt (will retry on next access)",
                    System.currentTimeMillis() - startedAt, t);
            throw t;
        }
    }

    private static void readMap(DataProvider mapSource, int area, int mapId, Map<Integer, int[]> out) {
        Data mapData = mapSource.getData(mapImgPath(area, mapId));
        if (mapData == null) {
            return;
        }
        Data info = mapData.getChildByPath("info");
        String link = info != null ? DataTool.getString("link", info, "") : "";
        if (!link.isEmpty()) {
            try {
                int linkId = Integer.parseInt(link);
                mapData = mapSource.getData(mapImgPath(linkId / 100000000, linkId));
                if (mapData == null) {
                    out.put(mapId, EMPTY);
                    return;
                }
            } catch (NumberFormatException ignored) {
                // malformed link — read the map as-is
            }
        }
        Data portals = mapData.getChildByPath("portal");
        if (portals == null) {
            out.put(mapId, EMPTY);
            return;
        }
        Set<Integer> targets = new TreeSet<>();
        for (Data portal : portals) {
            int targetMapId = DataTool.getInt("tm", portal, NO_TARGET_MAPID);
            if (targetMapId == NO_TARGET_MAPID || targetMapId == mapId) {
                continue;
            }
            if (DataTool.getInt("pt", portal, 0) == Portal.DOOR_PORTAL) {
                continue;
            }
            String script = DataTool.getString("script", portal, "");
            if (!script.isEmpty()) {
                continue; // scripted portals execute their own warp — out of pure-movement scope
            }
            if (!isPortalInCurrentVersion(targetMapId)) {
                continue; // destination is gated out of the current server version (e.g. Nautilus on v55) —
                          // real players are blocked at the portal, so bots must not path there either
            }
            targets.add(targetMapId);
        }
        int[] arr = new int[targets.size()];
        int i = 0;
        for (int t : targets) {
            arr[i++] = t;
        }
        out.put(mapId, arr);
    }

    private static String mapImgPath(int area, int mapId) {
        return "Map/Map" + area + "/" + String.format("%09d", mapId) + ".img";
    }

    // ADAPTATION: SoloMapling gated portal edges behind MapleVersionManager.isPortalinCurrentVersion
    // (a destination map not in the current server version was not pathable). gms has no equivalent
    // version gate; treat every map as available (always true) until a gms-side gate is introduced.
    private static boolean isPortalInCurrentVersion(int targetMapId) {
        return true;
    }
}
