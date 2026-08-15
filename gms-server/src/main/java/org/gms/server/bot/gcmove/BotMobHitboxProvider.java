package org.gms.server.bot.gcmove;

import org.gms.provider.Data;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;
import org.gms.server.life.Monster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Loads a mob's body hitbox (lt/rb bounds) from its WZ Mob/%07d.img and maps it to world
// coordinates around the mob's position (mirrored when facing left). One rectangle is cached per
// distinct mob id - bounded by monster species, not by mob/bot count - and the cache is cleared if
// the WZ root path changes. Used by the bot contact-damage overlap check.
// Extracted from GreenCatMS and converted from a getInstance() singleton to all-static. Credit: NutNNut.
// 移植适配：WZ 读取由 SoloMapling 的 XML DOM 直读改为 gms 的 Data/DataProviderFactory/DataTool
// + WZFiles.MOB（参照 MonsterInformationProvider / LifeFactory 的 Mob.wz 读取方式），lt/rb/link 语义 1:1。
final class BotMobHitboxProvider {
    private static final Logger log = LoggerFactory.getLogger(BotMobHitboxProvider.class);

    // Sentinel stored when a mob has no usable hitbox frame, so we don't re-parse/re-log every tick
    // for mobs we already know are unresolvable.
    private static final Rectangle UNRESOLVED_BOUNDS = new Rectangle(Integer.MIN_VALUE, 0, 0, 0);

    // Body-pose frame groups only. Attack/hit frames can embed weapon-swing extents that would
    // inflate the body rect; flying mobs expose fly/0 instead of stand/0.
    private static final String[] FRAME_GROUP_FALLBACK = {"stand", "move", "fly"};

    private static final DataProvider DATA = DataProviderFactory.getDataProvider(WZFiles.MOB);

    private static final Map<Integer, Rectangle> boundsByMobId = new ConcurrentHashMap<>();
    private static volatile Path cachedMobRoot = null;

    private BotMobHitboxProvider() {
    }

    static Rectangle getMobBounds(Monster mob) {
        if (mob == null) {
            return null;
        }

        return getMobBounds(mob.getId(), mob.getPosition(), mob.isFacingLeft());
    }

    static Rectangle getMobBounds(int mobId, Point position, boolean facingLeft) {
        ensureCurrentMobRoot();
        Rectangle modelBounds = boundsByMobId.computeIfAbsent(mobId, BotMobHitboxProvider::loadMobBounds);
        if (modelBounds == UNRESOLVED_BOUNDS) {
            return null;
        }

        return calculateWorldBounds(modelBounds, position, facingLeft);
    }

    private static void ensureCurrentMobRoot() {
        Path currentMobRoot = WZFiles.MOB.getFile();
        Path previousMobRoot = cachedMobRoot;
        if (previousMobRoot != null && previousMobRoot.equals(currentMobRoot)) {
            return;
        }

        synchronized (boundsByMobId) {
            if (cachedMobRoot != null && cachedMobRoot.equals(currentMobRoot)) {
                return;
            }
            boundsByMobId.clear();
            cachedMobRoot = currentMobRoot;
        }
    }

    private static Rectangle loadMobBounds(int mobId) {
        Data mobData = DATA.getData(String.format("%07d.img", mobId));
        if (mobData == null) {
            log.debug("Bot mob hitbox: no WZ data for mob {} - caching miss", mobId);
            return UNRESOLVED_BOUNDS;
        }

        Rectangle bounds = loadFrameBounds(mobData);
        if (bounds == null) {
            log.debug("Bot mob hitbox: no lt/rb bounds on any of {} for mob {} - caching miss",
                    String.join(",", FRAME_GROUP_FALLBACK), mobId);
            return UNRESOLVED_BOUNDS;
        }
        return bounds;
    }

    private static Rectangle loadFrameBounds(Data root) {
        Data linkedRoot = resolveLinkedRoot(root);
        for (String frameGroup : FRAME_GROUP_FALLBACK) {
            Data group = linkedRoot.getChildByPath(frameGroup);
            if (group == null) {
                continue;
            }
            Data frame = group.getChildByPath("0");
            if (frame == null) {
                continue;
            }
            Rectangle bounds = toBounds(frame);
            if (bounds != null) {
                return bounds;
            }
        }
        return null;
    }

    private static Data resolveLinkedRoot(Data root) {
        int linkedMobId = DataTool.getInt(root.getChildByPath("info/link"), 0);
        if (linkedMobId <= 0) {
            return root;
        }

        Data linked = DATA.getData(String.format("%07d.img", linkedMobId));
        return linked != null ? linked : root;
    }

    private static Rectangle calculateWorldBounds(Rectangle modelBounds, Point origin, boolean facingLeft) {
        int left = modelBounds.x;
        int right = modelBounds.x + modelBounds.width;
        if (facingLeft) {
            int originalLeft = left;
            left = -right;
            right = -originalLeft;
        }

        return new Rectangle(origin.x + left, origin.y + modelBounds.y, right - left, modelBounds.height);
    }

    private static Rectangle toBounds(Data frame) {
        Point lt = DataTool.getPoint("lt", frame, null);
        Point rb = DataTool.getPoint("rb", frame, null);
        if (lt == null || rb == null) {
            return null;
        }

        int left = Math.min(lt.x, rb.x);
        int right = Math.max(lt.x, rb.x);
        int top = Math.min(lt.y, rb.y);
        int bottom = Math.max(lt.y, rb.y);
        if (left >= right || top >= bottom) {
            return null;
        }

        return new Rectangle(left, top, right - left, bottom - top);
    }
}
