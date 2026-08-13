package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.util.I18nUtil;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动编排（对应 SoloMapling 环境启动的简化版）：按 game_config 配置在服务器
 * 启动完成后批量生成 bot，逐个错峰（BotTiming.after 阶梯）避免同拍动作。
 * 所有配置缺省关闭——不影响任何现有部署。
 */
@Slf4j
public final class BotStartupManager {

    private static final String KEY_ENABLED = "bot.spawn_on_startup";
    private static final String KEY_COUNT = "bot.spawn_count";
    private static final String KEY_MAPS = "bot.spawn_maps";
    private static final String KEY_TYPE = "bot.spawn_type";

    private static final int DEFAULT_MAP = 100000000; // 汉尼斯
    private static final String DEFAULT_TYPE = "SOCIAL_BOT";
    private static final long STAGGER_MS = 300;

    private BotStartupManager() {
    }

    private static volatile BotServerAccess serverAccess = DefaultBotServerAccess.INSTANCE;

    /** 测试注入接缝（传 null 恢复生产默认）。 */
    static void setServerAccess(BotServerAccess access) {
        serverAccess = access == null ? DefaultBotServerAccess.INSTANCE : access;
    }

    /**
     * 服务器启动完成后调用（挂 ServerManager.run 的 Server.init 之后）。
     * 先注册进图响应订阅者（无论是否开启批量生成），再按配置生成 bot。
     * 整体兜底捕获：bot 属实验性功能，任何配置/生成异常都不允许中断游戏服启动。
     */
    public static void startup() {
        try {
            startupInternal();
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("BotStartupManager.startup.error"), e);
        }
    }

    private static void startupInternal() {
        BotMapEntryResponder.register();
        if (!GameConfig.getServerBoolean(KEY_ENABLED)) {
            return;
        }
        int count = GameConfig.getServerInt(KEY_COUNT);
        if (count <= 0) {
            log.warn(I18nUtil.getLogMessage("BotStartupManager.count.invalid"));
            return;
        }
        List<Integer> maps = parseMaps(GameConfig.getServerString(KEY_MAPS));
        String typeName = GameConfig.getServerString(KEY_TYPE);
        BotTypeManager.BotType type = parseType(typeName);

        log.info(I18nUtil.getLogMessage("BotStartupManager.spawning", count, maps, typeName));
        for (int i = 0; i < count; i++) {
            int mapId = maps.get(i % maps.size());
            BotTiming.after((long) i * STAGGER_MS, () -> spawnOne(type, mapId));
        }
        log.info(I18nUtil.getLogMessage("BotStartupManager.done", count));
    }

    /** 包私有（测试可直调）：在指定地图生成一个指定类型 bot 并启动。 */
    static void spawnOne(BotTypeManager.BotType type, int mapId) {
        int world = DefaultBotServerAccess.resolveBotWorld();
        int channel = DefaultBotServerAccess.resolveBotChannel();
        MapleMap map = serverAccess.getMap(world, channel, mapId);
        if (map == null) {
            log.warn(I18nUtil.getLogMessage("BotStartupManager.map.missing", mapId));
            return;
        }
        // 出生点用玩家出生 portal（getRandomSP 是怪物刷点：无 type="m" 的地图返回 null，
        // 回退 (0,0) 会把 bot 扔到天空左上角）。portal 坐标可能略高于地面——
        // BotGeneration.placeBotOnMap 会按 foothold 修正到脚下地面。
        Point spawnPoint = new Point(0, 0);
        Portal portal = map.getPortal(0);
        if (portal != null) {
            spawnPoint = portal.getPosition();
        }
        int botId = BotGeneration.createBot(spawnPoint, map);
        Character bot = serverAccess.getCharacterById(botId);
        if (bot == null) {
            log.warn(I18nUtil.getLogMessage("BotStartupManager.bot.missing", botId));
            return;
        }
        type.createAndSetBot(bot);
        BotTypeManager.manuallyStartBot(bot);
    }

    private static List<Integer> parseMaps(String csv) {
        List<Integer> maps = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            maps.add(DEFAULT_MAP);
            return maps;
        }
        for (String part : csv.split(",")) {
            try {
                int mapId = Integer.parseInt(part.trim());
                if (mapId > 0) {
                    maps.add(mapId);
                }
            } catch (NumberFormatException ignored) {
                log.warn(I18nUtil.getLogMessage("BotStartupManager.map.parse.error", part));
            }
        }
        if (maps.isEmpty()) {
            maps.add(DEFAULT_MAP);
        }
        return maps;
    }

    private static BotTypeManager.BotType parseType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return BotTypeManager.BotType.valueOf(DEFAULT_TYPE);
        }
        try {
            return BotTypeManager.BotType.valueOf(typeName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn(I18nUtil.getLogMessage("BotStartupManager.type.unknown", typeName));
            return BotTypeManager.BotType.valueOf(DEFAULT_TYPE);
        }
    }
}
