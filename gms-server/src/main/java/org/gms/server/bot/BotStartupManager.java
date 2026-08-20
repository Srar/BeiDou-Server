package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.server.bot.decorate.BotDecorationQueue;
import org.gms.server.bot.decorate.BotEquipChecker;
import org.gms.server.bot.environment.EnvironmentManager;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.util.I18nUtil;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动编排：按 game_config 配置在服务器启动完成后生成 bot。
 * <p>
 * 两种模式（均缺省关闭，不影响现有部署）：
 * <ul>
 *   <li><b>环境模式（对齐 SoloMapling）</b>：{@code bot.spawn_on_startup=true} 且
 *       {@code bot.spawn_count<=0} 时，后台执行 {@link EnvironmentManager#environmentLoadStartup()}
 *       ——即 SoloMapling 的 SPAWN_BOTS_ON_STARTUP 语义：9 波完整环境（FM 商人/汉尼斯人口/小游戏/
 *       训练 Bot/城镇驻留与游荡者）+ SocialHotPotatoManager/ConversationManager/装饰后台任务接线。</li>
 *   <li><b>简单模式（gms 保留）</b>：显式配置 {@code bot.spawn_count>0} 时，按 count/maps/type
 *       三键批量生成单一类型 bot（错峰 + 分散出生点）。</li>
 * </ul>
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
     * 游戏服（重新）初始化完成后调用：由 {@link org.gms.net.server.Server#init()} 末尾统一触发，
     * 覆盖 Spring 启动（ServerManager → Server.init）与后台 REST in-place 重启
     * （restartServer / stopServer+startServer → shutdownInternal → init）两种路径。
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
        if (count > 0) {
            runSimpleMode(count);
            return;
        }
        // SoloMapling 对齐：SPAWN_BOTS_ON_STARTUP=true 且未显式配置数量时，跑完整 9 波环境。
        // 源由 Server.init 内 1s 延迟执行 environmentLoadStartup；gms 在 Spring ApplicationRunner
        // 的 Server.init 完成后执行，直接后台跑（异步不阻塞启动收尾），异常兜底不中断游戏服。
        log.info(I18nUtil.getLogMessage("BotStartupManager.envmode"));
        BotExecutors.runAsync(() -> {
            try {
                EnvironmentManager.environmentLoadStartup();
            } catch (Throwable t) {
                log.error("BotStartupManager environment startup failed", t);
            }
        });
    }

    private static void runSimpleMode(int count) {
        List<Integer> maps = parseMaps(GameConfig.getServerString(KEY_MAPS));
        String typeName = GameConfig.getServerString(KEY_TYPE);
        BotTypeManager.BotType type = parseType(typeName);

        // 对齐源 EnvironmentManager wave9 的接线语义：环境运行时启动裸奔兜底。
        // 两个 start() 均幂等（BotDecorationQueue.start 判 scheduler 是否已运行，
        // BotEquipChecker.start 判 task != null），在确有生成时于 spawn 前启动一次。
        BotDecorationQueue.start();
        BotEquipChecker.start();

        // 启动预热世界图（可步行传送门连通图）：TrainingBot 首次 DECIDE/旅行会同步触发 GCWorldGraph
        // 懒构建并阻塞其 tick 至多 10 分钟（首次扫全 Map.wz）。预热在后台执行，使构建与启动 spawn
        // 重叠，避免首个 TrainingBot 长时间僵住。SoloMapling 源由 EnvironmentManager wave8 的
        // mapsWithinHops 隐式预热，gms 无 9 波默认路径故显式预热。导航图本身已由 spawnOne 的
        // GCMovement.enable 异步 warm，无需额外处理。
        BotExecutors.runAsync(() -> {
            try {
                int mapCount = GCMovement.ensureWorldGraph();
                log.info("BotStartupManager world graph preheat done: {} maps indexed", mapCount);
            } catch (Throwable t) {
                log.error("BotStartupManager world graph preheat failed: {}", t.toString());
            }
        });

        log.info(I18nUtil.getLogMessage("BotStartupManager.spawning", count, maps, typeName));
        // 每张图预生成分散的出生点（以玩家出生 portal 为锚点沿 X 分桶随机 + 地面修正），
        // 避免批量 bot 全部挤在同一个 portal 出生点上（参考 SoloMapling BotSpotPicker）
        Map<Integer, List<Point>> spotsByMap = new HashMap<>();
        for (int mapId : maps) {
            MapleMap map = serverAccess.getMap(DefaultBotServerAccess.resolveBotWorld(),
                    DefaultBotServerAccess.resolveBotChannel(), mapId);
            if (map == null) {
                continue;
            }
            Point anchor = new Point(0, 0);
            Portal portal = map.getPortal(0);
            if (portal != null) {
                anchor = portal.getPosition();
            }
            spotsByMap.put(mapId, BotHelpers.pickGroundSpots(map, anchor, count));
        }
        for (int i = 0; i < count; i++) {
            int mapId = maps.get(i % maps.size());
            List<Point> spots = spotsByMap.getOrDefault(mapId, List.of());
            Point spot = spots.isEmpty() ? new Point(0, 0) : spots.get(Math.min(i / maps.size(), spots.size() - 1));
            BotTiming.after((long) i * STAGGER_MS, () -> spawnOne(type, mapId, spot));
        }
        log.info(I18nUtil.getLogMessage("BotStartupManager.done", count));
    }

    /** 包私有（测试可直调）：在指定地图的指定出生点生成一个指定类型 bot 并启动。 */
    static void spawnOne(BotTypeManager.BotType type, int mapId, Point spawnPoint) {
        int world = DefaultBotServerAccess.resolveBotWorld();
        int channel = DefaultBotServerAccess.resolveBotChannel();
        MapleMap map = serverAccess.getMap(world, channel, mapId);
        if (map == null) {
            log.warn(I18nUtil.getLogMessage("BotStartupManager.map.missing", mapId));
            return;
        }
        int botId = BotGeneration.createBot(spawnPoint, map);
        Character bot = serverAccess.getCharacterById(botId);
        if (bot == null) {
            log.warn(I18nUtil.getLogMessage("BotStartupManager.bot.missing", botId));
            return;
        }
        type.createAndSetBot(bot);
        BotTypeManager.manuallyStartBot(bot);
        // 对齐源环境启动链：首次 enable 会启动 ObserverTracker 1s 观察轮询（SocialBot 的 relocate
        // 依赖 isMapObserved）并异步 warm 导航图（TownPresenceSampler 依赖 reachableLedges，
        // 图未就绪时 relocate 直接失败）。enable 幂等，批量调用安全。
        GCMovement.enable(bot);
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
