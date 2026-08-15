package org.gms.server.bot.gcmove;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Client-free portal entry for the (clientless) dynamic bots.
 *
 * All SoloMapling bots share a single BotClient, so portal.enterPortal(client)
 * would warp whatever character that shared client currently points at — not this bot. The
 * dynamic engine therefore never touches a client: it resolves the destination map + target portal
 * straight off the SERVER (map -> channel -> map factory) and changeMaps the bot
 * Character directly. Mirrors WarpCommands.botWarpMapOnPortal (the recorded engine's
 * approach).
 */
// Ported from GreenCatMS. Credit: NutNNut.
final class GCPortals {
    private GCPortals() {
    }

    private static final Logger log = LoggerFactory.getLogger(GCPortals.class);

    /* Step bot through portal to its destination. Returns false only if nothing
     *  could be resolved or warped at all. */
    static boolean enter(Character bot, Portal portal) {
        if (bot == null || portal == null || bot.getMap() == null) {
            return false;
        }
        int targetMapId = portal.getTargetMapId();
        try {
            MapleMap to = bot.getEventInstance() == null
                    ? bot.getMap().getChannelServer().getMapFactory().getMap(targetMapId)
                    : bot.getEventInstance().getMapInstance(targetMapId);
            if (to == null) {
                bot.changeMap(targetMapId);
                return true;
            }
            Portal pto = to.getPortal(portal.getTarget());
            if (pto == null) {
                pto = to.getPortal(0); // fallback for a missing named target portal
            }
            bot.changeMap(to, pto);
            return true;
        } catch (Exception e) {
            // portal resolution failed; fall back to a plain warp (self-heals on the next travel poll)
            log.debug("GCPortals.enter resolution failed for bot {} -> map {}, falling back to plain warp",
                    bot != null ? bot.getId() : "null", targetMapId, e);
            try {
                bot.changeMap(targetMapId); // any resolution/lifecycle hiccup -> plain warp
                return true;
            } catch (Exception ignored) {
                log.debug("GCPortals.enter fallback warp failed for bot {} -> map {}",
                        bot != null ? bot.getId() : "null", targetMapId, ignored);
                return false;
            }
        }
    }
}
