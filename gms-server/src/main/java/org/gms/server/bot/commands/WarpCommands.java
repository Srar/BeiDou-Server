package org.gms.server.bot.commands;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.DefaultBotServerAccess;
import org.gms.server.bot.replay.MovementCommands;
import org.gms.server.bot.replay.MovementRecording;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.util.I18nUtil;

import java.util.HashMap;
import java.util.Map;

import static org.gms.server.bot.replay.InPacketReader.getMovementRecording;
import static org.gms.server.bot.replay.MovementCommands.BotMoveStreamOffset;

/**
 * 传送命令（SoloMapling BotCommandsPack.WarpCommands 逐行移植）。
 * <p>
 * 底座差异：无 BotClientHandler 的 getBotClient() 无参静态导入，改走
 * {@code fakechar.getMap().getChannelServer()}（有角色时）或
 * {@link DefaultBotServerAccess} 解析的 bot 频道（无角色入参的 FM portal 查询）；
 * {@link #botEnterPortalDropDown(Character, int)} 已恢复录制回放（"portalenterdrop"），
 * 回放前按导航原语模式拿移动锁（gms 底座出生即 enable gcmove，锁被占时跳过落下动画）。
 */
@Slf4j
public class WarpCommands {

    public static Map<Integer, Integer> FMRoomWarpPortalId = new HashMap<>();

    static {
        FMRoomWarpPortalId.put(1, 11); // henesysDoorPortalId 1
        for (int i = 2; i <= 6; i++) {
            FMRoomWarpPortalId.put(i, 10); // henesysDoorPortalId 2-6
        }
        for (int i = 7; i <= 12; i++) {
            FMRoomWarpPortalId.put(i, 10); // Ludibrium
        }
        for (int i = 13; i <= 17; i++) {
            FMRoomWarpPortalId.put(i, 10); // Perion
        }
        for (int i = 18; i <= 22; i++) {
            FMRoomWarpPortalId.put(i, 9); // El Nath
        }

        // Fixing the bug for FM room 10
        FMRoomWarpPortalId.put(10, 11);
    }

    /*
    Bot Enters the portal they're standing on. Does not require Map id's, portal id's, or coordinates.
     */
    public static void botWarpMapOnPortal(Character fakechar) {
        MapleMap currentMap = fakechar != null ? fakechar.getMap() : null;
        if (currentMap == null) {
            log.warn("WarpCommands.botWarpMapOnPortal skipped: bot has no current map");
            return;
        }
        Portal portal = currentMap.findClosestPortal(fakechar.getPosition());
        if (portal == null) {
            log.warn("WarpCommands.botWarpMapOnPortal skipped: no closest portal for bot {}", fakechar.getId());
            return;
        }
        MapleMap to;
        if (fakechar.getEventInstance() == null) {
            Channel channel = currentMap.getChannelServer();
            if (channel == null) {
                log.warn("WarpCommands.botWarpMapOnPortal skipped: bot {} has no channel server", fakechar.getId());
                return;
            }
            to = channel.getMapFactory().getMap(portal.getTargetMapId());
        } else {
            to = fakechar.getEventInstance().getMapInstance(portal.getTargetMapId());
        }
        if (to == null) {
            log.warn("WarpCommands.botWarpMapOnPortal skipped: target map {} not found", portal.getTargetMapId());
            return;
        }

        Portal pto = to.getPortal(portal.getTarget());
        if (pto == null) {// fallback for missing portals - no real life case anymore - interesting for not implemented areas
            pto = to.getPortal(0);
        }
        if (pto == null) {
            log.warn("WarpCommands.botWarpMapOnPortal skipped: no target portal on map {}", to.getId());
            return;
        }
        try {
            fakechar.changeMap(to, pto);
            botEnterPortalDropDown(fakechar);
        } catch (Exception e) {
            log.warn("WarpCommands.botWarpMapOnPortal failed for bot {}",
                    fakechar != null ? fakechar.getId() : "null", e);
        }
    }

    public static void botWarpMapOnPortal(Character fakechar, MapleMap warpMap, int portalId) {
        if (warpMap == null) {
            log.warn("WarpCommands.botWarpMapOnPortal skipped: warpMap is null");
            return;
        }
        fakechar.changeMap(warpMap, portalId);
        botEnterPortalDropDown(fakechar);
    }

    public static void botEnterFMRoom(Character fakechar, int roomNumber) {
        int freeMarketRoom = 910000000 + roomNumber;
        Integer portalId = FMRoomWarpPortalId.get(roomNumber);
        if (portalId == null) {
            log.warn("WarpCommands.botEnterFMRoom skipped: no warp portal id for room {}", roomNumber);
            return;
        }
        MapleMap currentMap = fakechar.getMap();
        if (currentMap == null) {
            log.warn("WarpCommands.botEnterFMRoom skipped: bot has no current map");
            return;
        }
        Channel channel = currentMap.getChannelServer();
        if (channel == null) {
            log.warn("WarpCommands.botEnterFMRoom skipped: bot has no channel server");
            return;
        }
        MapleMap warpMap = channel.getMapFactory().getMap(freeMarketRoom);
        if (warpMap == null) {
            log.warn("WarpCommands.botEnterFMRoom skipped: room map {} not found", freeMarketRoom);
            return;
        }
        botWarpMapOnPortal(fakechar, warpMap, portalId);
    }

    public static void botExitFMRoom(Character fakechar, int doorNumber) {
        int freeMarketEntrance = 910000000;
        MapleMap currentMap = fakechar.getMap();
        if (currentMap == null) {
            log.warn("WarpCommands.botExitFMRoom skipped: bot has no current map");
            return;
        }
        Channel channel = currentMap.getChannelServer();
        if (channel == null) {
            log.warn("WarpCommands.botExitFMRoom skipped: bot has no channel server");
            return;
        }
        MapleMap warpMap = channel.getMapFactory().getMap(freeMarketEntrance);
        if (warpMap == null) {
            log.warn("WarpCommands.botExitFMRoom skipped: FM entrance map not found");
            return;
        }
        Portal entrancePortal = getFMEntrancePortal(doorNumber);
        if (entrancePortal == null) {
            log.warn("WarpCommands.botExitFMRoom skipped: no FM entrance portal for door {}", doorNumber);
            return;
        }
        botWarpMapOnPortal(fakechar, warpMap, entrancePortal.getId());
    }

    public static Portal getFMEntrancePortal(int doorNumber) {
        int freeMarketEntrance = 910000000;
        String portalName = String.format("in%02d", doorNumber);
        Channel channel = botChannel();
        if (channel == null) {
            log.warn("WarpCommands.getFMEntrancePortal skipped: bot channel unavailable");
            return null;
        }
        MapleMap warpMap = channel.getMapFactory().getMap(freeMarketEntrance);
        if (warpMap == null) {
            log.warn("WarpCommands.getFMEntrancePortal skipped: FM entrance map not found");
            return null;
        }
        return warpMap.getPortal(portalName);
    }

    public static Portal getFMEUpArrows(int upArrow) {
        // upArrow 0 = row 1 -> 2
        // upArrow 1 = row 2 -> 3
        // upArrow 2 = row 3 -> 4
        int freeMarketEntrance = 910000000;
        String arrowName = String.format("up%02d", upArrow);
        MapleMap warpMap = botChannel().getMapFactory().getMap(freeMarketEntrance);
        return warpMap.getPortal(arrowName);
    }

    /** bot 所在频道服务器（等价 SoloMapling getBotClient().getChannelServer()）。 */
    private static Channel botChannel() {
        return Server.getInstance().getChannel(DefaultBotServerAccess.resolveBotWorld(), DefaultBotServerAccess.resolveBotChannel());
    }

    // Deliberate synchronous choreography: fake portal lag, then a blocking
    // recording replay. Part of the spawn/warp arrival scripts that hold their thread.
    public static void botEnterPortalDropDown(Character fakechar, int variablePortalLag) {
        BotHelpers.blockingSleep(variablePortalLag);
        String recName = "portalenterdrop";
        try {
            MovementRecording mvr = getMovementRecording(0, recName);
            // 录制引擎接线（P5-H2）：回放与 gcmove 会话互斥。SoloMapling 原语义不拿锁
            //（其出生链不 enable gcmove）；gms 底座出生即 enable（BotStartupManager.spawnOne），
            // 故此处按导航原语模式拿锁：锁被动态会话占住时跳过落下动画（假 portal 延迟已睡过），
            // 避免两个引擎并发驱动同一 Character。
            if (!MovementCommands.tryAcquireMovementLock(fakechar)) {
                log.warn(I18nUtil.getLogMessage("WarpCommands.portalDropDown.lockBusy", fakechar.getId()));
                return;
            }
            try {
                BotMoveStreamOffset(mvr, fakechar);
            } finally {
                MovementCommands.releaseMovementLock(fakechar);
            }
        } catch (Exception e) {
            // Missing/corrupt recording shouldn't break the warp — arrival position is already set.
            log.warn(I18nUtil.getLogMessage("WarpCommands.portalDropDown.playbackFailed",
                    fakechar.getId(), recName));
        }
    }

    public static void botEnterPortalDropDown(Character fakechar) {
        botEnterPortalDropDown(fakechar, 1500);
    }

    //test only
    public static void botMoveMap(Character fakechar, int mapId) {
        MapleMap currentMap = fakechar.getMap();
        if (currentMap == null) {
            log.warn("WarpCommands.botMoveMap skipped: bot has no current map");
            return;
        }
        Channel channel = currentMap.getChannelServer();
        if (channel == null) {
            log.warn("WarpCommands.botMoveMap skipped: bot has no channel server");
            return;
        }
        MapleMap warpMap = channel.getMapFactory().getMap(mapId);
        if (warpMap == null) {
            log.warn("WarpCommands.botMoveMap skipped: map {} not found", mapId);
            return;
        }
        Portal portal = warpMap.getPortal(11);
        if (portal == null) {
            log.warn("WarpCommands.botMoveMap skipped: portal 11 not found on map {}", mapId);
            return;
        }
        fakechar.changeMap(warpMap, portal);

////        MapleMap target = fakechar.getMap().getChannelServer().getMapFactory().getMap(gotomaps.get(params[0]));
//
//        Portal targetPortal = target.findMarketPortal();
////        Portal targetPortal = target.getRandomPlayerSpawnpoint();
//        fakechar.saveLocationOnWarp();
//        fakechar.changeMap(target, targetPortal);
//        // broadcast that player enter
//        // in the air packet, then dropping them
    }
}
