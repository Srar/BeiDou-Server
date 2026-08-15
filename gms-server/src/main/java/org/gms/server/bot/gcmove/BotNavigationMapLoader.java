package org.gms.server.bot.gcmove;

import lombok.extern.slf4j.Slf4j;
import org.gms.server.maps.MapFactory;
import org.gms.server.maps.MapleMap;

// Ported from GreenCatMS. Credit: NutNNut.
//
// In SoloMapling this class re-implemented the WZ geometry load: info/link
// resolution, VR bounds / minimap bounds, portals, footholds (prev/next +
// forbidFallDown), ladderRope (rope/ladder), info/fs (foothold slipperiness)
// and info/swim.
//
// All of those fields are now populated by org.gms.server.maps.MapFactory
// .loadMapFromWz (P0 GCMoveSystem additions): setSwim, setFootholdSpeed (fs),
// Foothold.setForbidFallDown, addRope, bounds, portals and footholds are all
// handled there. There is no remaining geometry diff to keep, so this loader
// is reduced to a thin entry point that reuses the gms MapFactory loading flow.
@Slf4j
final class BotNavigationMapLoader {
    private BotNavigationMapLoader() {
    }

    static MapleMap loadMapGeometry(int mapId) {
        MapleMap map = MapFactory.loadMapFromWz(mapId, 0, 0, null);
        if (map == null) {
            log.warn("BotNavigationMapLoader.loadMapGeometry: failed to load map {}", mapId);
        }
        return map;
    }
}
