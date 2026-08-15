package org.gms.server.bot.grind;

import java.awt.Point;
import java.util.Map;

// Registry of "deep hub" spawn maps: non-town maps a training cohort calls home, sitting inside a dungeon
// rather than a town. They differ from an ordinary town home in three ways:
//   - grindable: a hub that has its own mobs (Sharp Cliff I, Ant Tunnel Park) is itself a valid training
//     candidate for its cohort. That is handled generically in TrainingBot.doDecide (home map + has mobs),
//     because the map BFS drops its own origin — this registry does not gate it.
//   - potion vendor: the hub's restock NPC is an ON-MAP food-cart / grocer, not a separate shop map
//     (unlike TOWN_SHOPS). The town errand walks to it in place instead of travelling to a store map.
//   - downwardOnly: a high-level "pro" hub (deep Ludibrium) whose cohort should only ever migrate DEEPER
//     (harder mobs), never back up toward town — enforced by a raised admission floor in doDecide.
// Keyed by the cohort's home (spawn) map id. Our own creation (not a GreenCat extraction).
public final class DeepHub {

    private DeepHub() {
    }

    // gms 移植：SoloMapling 的 MapId.ANT_TUNNEL_PARK / SHARP_CLIFF_I / PATH_OF_TIME_HUB 常量在 gms MapId
    // 中不存在，这里按原值 1:1 内联（MapId.ANT_TUNNEL_PARK=105070001, SHARP_CLIFF_I=211040300,
    // PATH_OF_TIME_HUB=220050300）。
    private static final int ANT_TUNNEL_PARK = 105070001;
    private static final int SHARP_CLIFF_I = 211040300;
    private static final int PATH_OF_TIME_HUB = 220050300;

    // potionNpc = the on-map food-cart / grocer to walk to as the town errand (null = no vendor on the hub).
    public record Info(int mapId, Point potionNpc, boolean downwardOnly) {
    }

    private static final Map<Integer, Info> HUBS = Map.of(
            // Ant Tunnel Park: 58-spawn grind field; restock at the "24 Hr Mobile Store" food cart (NPC 1061001).
            ANT_TUNNEL_PARK, new Info(ANT_TUNNEL_PARK, new Point(333, -249), false),
            // Sharp Cliff I: 33-spawn grind field reached one-way from Ice Valley II (Jeff); no on-map vendor.
            SHARP_CLIFF_I, new Info(SHARP_CLIFF_I, null, false),
            // Path of Time Hub: mobless deep-Ludibrium potion junction (grocer "Toly", NPC 2040051) for pros;
            // one-directional — its high-level cohort only trains DEEPER, never back up toward Ludibrium town.
            PATH_OF_TIME_HUB, new Info(PATH_OF_TIME_HUB, new Point(-83, 2918), true));

    // The hub descriptor for a spawn map, or null if the map isn't a registered deep hub.
    public static Info of(int mapId) {
        return HUBS.get(mapId);
    }

    public static boolean isDeepHub(int mapId) {
        return HUBS.containsKey(mapId);
    }
}
