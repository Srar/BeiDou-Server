package org.gms.server.bot.environment;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.config.GameConfig;
import org.gms.constants.id.MapId;
import org.gms.constants.id.NpcId;
import org.gms.server.bot.BotCustomization;
import org.gms.server.bot.BotExecutors;
import org.gms.server.bot.BotGeneration;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotMapEntryResponder;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.BotTypeManager;
import org.gms.server.bot.DefaultBotServerAccess;
import org.gms.server.bot.decorate.BotDecorate;
import org.gms.server.bot.decorate.BotDecorationQueue;
import org.gms.server.bot.decorate.BotEquipChecker;
import org.gms.server.bot.dialogue.ConversationManager;
import org.gms.server.bot.freemarket.ArtificialFreeMarket;
import org.gms.server.bot.environment.platform.Platform;
import org.gms.server.bot.environment.platform.PlatformParser;
import org.gms.server.bot.environment.platform.PlatformSpawner;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.grind.BotSpotPicker;
import org.gms.server.bot.social.SocialHotPotatoManager;
import org.gms.server.bot.town.TownPresenceConfig;
import org.gms.server.bot.town.TownPresenceSampler;
import org.gms.server.bot.types.blackjack.BlackjackDealerBot;
import org.gms.server.life.LifeFactory;
import org.gms.server.life.NPC;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.util.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * 可手动触发的环境生成器（对应 SoloMapling EnvironmentManager 的移植）。
 * <p>
 * 环境模式由 {@link org.gms.server.bot.BotStartupManager}（spawn_on_startup=true）自动触发，
 * 也可由 GM 命令 {@code !env loadenv} 与各 {@code !env spawn*} 子命令手动触发
 * （防重复 guard 见 {@link #ENV_LOADED}）。
 * 9 波启动编排逐波对齐源实现；平台系统已移植（PlatformPlacement / PlatformParser /
 * PlatformSpawner / Platform，CSV 位于 jar 内 src/main/resources/movementDataPackets/），
 * spawn 路径平台优先 + 地面回退；平台级查询（getCurrentPlatform 等）由
 * {@link org.gms.server.bot.environment.platform.PlatformPlacement} 提供。
 */
@Slf4j
public final class EnvironmentManager {

    private EnvironmentManager() {
    }

    // gms 未移植 SoloMapling 的 CasinoChipConfig；原值 CASINO_NPC_ID = 9000055 硬编码在此。
    public static final int CASINO_NPC_ID = 9000055;

    private static final Random random = new Random();

    // gms 增强：防重复 loadenv guard。源实现（SoloMapling）无此 guard——在 spawn_on_startup=true
    // 启动链已跑过一遍 9 波的情况下，手动 !env loadenv 会再跑一遍并把 bot 规模翻倍（2 核小机 CPU 全满）。
    private static final AtomicBoolean ENV_LOADED = new AtomicBoolean(false);

    private static final int FM_ENTRANCE = 910000000;
    private static final int HENESYS = 100000000;
    private static final int HENESYS_MARKET = 100000100;
    private static final int HENESYS_PARK = 100000200;
    private static final int HENESYS_POTION_SHOP = 100000102;
    private static final int HENESYS_GAME_ZONE = 100000203;
    private static final int HENESYS_PET_PARK = 100000202;
    private static final int MAPLE_ISLAND_TUTORIAL = 10000;
    private static final int OPQ_LOBBY = 200080101;

    // 深枢纽地图：gms MapId 未收录这三个常量，按源 MapId 值内联。
    private static final int ANT_TUNNEL_PARK = 105070001;
    private static final int PATH_OF_TIME_HUB = 220050300;
    private static final int SHARP_CLIFF_I = 211040300;

    // ── 9 波环境启动 ────────────────────────────────────────────────────────

    /**
     * @return true = 本次实际执行了 9 波生成；false = 已被防重复 guard 拦截（环境已加载过）
     */
    public static boolean environmentLoadStartup() {
        // gms 增强：源（SoloMapling）无防重复 guard。spawn_on_startup=true 时启动链已跑过一遍，
        // 手动 !env loadenv 再来一遍会把 bot 规模翻倍（2 核小机直接 CPU 全满），此处用一次性开关挡住。
        if (!ENV_LOADED.compareAndSet(false, true)) {
            log.info("gms 增强：环境已加载，跳过重复 environmentLoadStartup（累计已生成 {} 个 bot；如需强制重跑用 !env loadenv force）",
                    BotGeneration.getBotsCreatedCount());
            return false;
        }
        try {
            doEnvironmentLoadStartup();
        } catch (Throwable t) {
            // 审计修正（m3）：guard 先置位后执行——生成中途失败时复位 flag，否则永久跳过、
            // 且 force 恢复会重跑 1-7 波造成重复生成。复位后下次 loadenv 可完整重试。
            ENV_LOADED.set(false);
            throw t;
        }
        return true;
    }

    /**
     * 强制重跑完整环境生成：先置位 guard 再直接执行加载逻辑（跳过防重复检查）。
     * 供 !env loadenv force 使用。
     */
    public static void forceEnvironmentLoad() {
        ENV_LOADED.set(true);
        log.info("gms 增强：forceEnvironmentLoad - 忽略已加载 guard，强制重跑环境生成");
        doEnvironmentLoadStartup();
    }

    public static boolean isEnvironmentLoaded() {
        return ENV_LOADED.get();
    }

    /** 供测试在 @AfterEach 复位防重复 guard（当前 src/test 无引用，保留给未来测试）。 */
    static void resetEnvironmentForTest() {
        ENV_LOADED.set(false);
    }

    /**
     * 停机复位钩子（审计修正 m2）：in-place 重启（Server.doShutdownInternal → getInstance().init()）
     * 后 bot 全部销毁、世界重开，ENV_LOADED 若不复位则 restart 的 spawn_on_startup 环境模式会
     * 静默跳过 9 波生成（世界空无 bot）。由 Server 停机钩子链调用。
     */
    public static void resetForShutdown() {
        ENV_LOADED.set(false);
    }

    private static void doEnvironmentLoadStartup() {
        long startupStart = System.currentTimeMillis();

        // 真人在场时唤醒 bot（移动 + 宏脑），双向：玩家进入有人图，或 bot 回到玩家图。
        BotMapEntryResponder.register();

        runWave(1, "Essentials", List.of(
                EnvironmentManager::spawnCasinoNpcs,
                EnvironmentManager::spawnTutorialBot,
                () -> spawnHenesysBotsBatch(10, 0, 0, 0),
                () -> populateFreeMarketRegion("henesys"),
                () -> spawnFMEntranceBotsBatch(5, 5, 5)
        ));

        runWave(2, "FM buildout", List.of(
                () -> populateFreeMarketRegion("ludi"),
                () -> spawnFMEntranceBotsBatch(5, 5, 5),
                () -> spawnMerchBotsBatch("m1", 2, 2, 1),
                () -> spawnMerchBotsBatch("m2", 2, 2, 0),
                () -> spawnMerchBotsBatch("m5", 2, 2, 0),
                EnvironmentManager::spawnGachaBotsHenesys
        ));

        runWave(3, "Henesys population", List.of(
                EnvironmentManager::spawnJQBotsPetPark,
                () -> spawnHenesysBotsBatch(10, 10, 0, 5),
                EnvironmentManager::spawnFillerBotsHenesys
        ));
        SocialHotPotatoManager.getInstance().start();
        ConversationManager.getInstance().start();

        runWave(4, "Expand FM + Henesys Market", List.of(
                () -> populateFreeMarketRegion("perion"),
                () -> spawnFMEntranceBotsBatch(5, 5, 5),
                () -> spawnMerchBotsBatch("m1", 3, 3, 0),
                () -> spawnMerchBotsBatch("m2", 2, 2, 1),
                () -> spawnMerchBotsBatch("m5", 3, 3, 1),
                EnvironmentManager::spawnFillerBotsHenesysMarket
        ));

        runWave(5, "Henesys sub-areas", List.of(
                () -> populateFreeMarketRegion("elnath"),
                () -> spawnHenesysBotsBatch(10, 10, 10, 4),
                EnvironmentManager::spawnFillerBotsHenesysPark,
                EnvironmentManager::spawnFillerBotsPotionShop,
                EnvironmentManager::spawnFillerBotsGameZone,
                EnvironmentManager::spawnGameZoneHostBots
        ));

        runWave(6, "Specialty areas", List.of(
                EnvironmentManager::spawnBlackjackTables,
                EnvironmentManager::spawnDropGameBotPotionShop,
                EnvironmentManager::spawnDropGameSpectatorsPotionShop,
                EnvironmentManager::spawnSocialBotsPetPark,
                EnvironmentManager::convertRandomFillersToScrollBots
        ));

        runWave(7, "Late arrivals", List.of(
                EnvironmentManager::spawnOPQBotsInLobby,
                () -> spawnMerchBotsBatch("m1", 2, 2, 0),
                () -> spawnMerchBotsBatch("m2", 2, 2, 1),
                () -> spawnMerchBotsBatch("m5", 2, 2, 1)
        ));

        // gms 增强：低核机器上训练波（波 8 大头 ~2390 bot）按 scaleForCores() 缩放；
        // 缩放生效时打印核数、scale 与调整后训练 bot 总数。
        double envScale = scaleForCores();
        if (envScale < 1.0) {
            int rawTotal = 0;
            int scaledTotal = 0;
            for (int c : WAVE8_TRAINING_COUNTS) {
                rawTotal += c;
                scaledTotal += Math.max(1, (int) Math.round(c * envScale));
            }
            log.info("gms 增强：env 缩放生效 cores={} scale={} 训练 bot {} -> {}",
                    Runtime.getRuntime().availableProcessors(), envScale, rawTotal, scaledTotal);
        }

        runWave(8, "Training bots", List.of(
                () -> GCMovement.mapsWithinHops(MapId.HENESYS, 1), // 预热一次 portal 图
                () -> spawnTrainingBotsAt(MapId.LITH_HARBOUR, 20, 1, 15),
                () -> spawnTrainingBotsAt(MapId.HENESYS, 225, 10, 95),
                () -> spawnTrainingBotsAt(MapId.KERNING_CITY, 225, 10, 65),
                () -> spawnTrainingBotsAt(MapId.PERION, 225, 10, 65),
                () -> spawnTrainingBotsAt(MapId.ELLINIA, 225, 10, 65),
                () -> spawnTrainingBotsAt(MapId.SLEEPYWOOD, 225, 25, 95),
                () -> spawnTrainingBotsAt(ANT_TUNNEL_PARK, 180, 40, 95),
                () -> spawnTrainingBotsAt(MapId.ORBIS, 220, 30, 86),
                () -> spawnTrainingBotsAt(MapId.LUDIBRIUM, 200, 25, 95),
                () -> spawnTrainingBotsAt(PATH_OF_TIME_HUB, 160, 70, 95),
                () -> spawnTrainingBotsAt(MapId.EL_NATH, 200, 50, 80),
                () -> spawnTrainingBotsAt(SHARP_CLIFF_I, 200, 60, 90),
                () -> spawnTrainingBotsAt(MapId.HENESYS, 25, 1, 9),
                () -> spawnTrainingBotsAt(MapId.KERNING_CITY, 20, 1, 9),
                () -> spawnTrainingBotsAt(MapId.PERION, 20, 1, 9),
                () -> spawnTrainingBotsAt(MapId.ELLINIA, 20, 1, 9)
        ));

        List<Runnable> townTasks = new ArrayList<>();
        for (TownPresenceConfig.TownEntry town : TownPresenceConfig.towns()) {
            townTasks.add(() -> spawnTown(town));
        }
        runWave(9, "Town presence", townTasks);

        BotDecorationQueue.start();
        BotEquipChecker.start();

        double totalSeconds = (System.currentTimeMillis() - startupStart) / 1000.0;
        log.info("=== All bots initialized: {} bots in {}s ===", BotGeneration.getBotsCreatedCount(), String.format("%.1f", totalSeconds));
    }

    // gms 增强：波 8 各 spawnTrainingBotsAt 调用的原始数量，与上方 runWave(8) 一一对应，
    // 仅用于缩放生效时的日志统计；实际缩放发生在 spawnTrainingBotsAt 内部。
    private static final int[] WAVE8_TRAINING_COUNTS = {
            20, 225, 225, 225, 225, 225, 180, 220, 200, 160, 200, 200, 25, 20, 20, 20
    };

    /**
     * gms 增强：按 CPU 核数缩放 bot 环境规模（SoloMapling 运行于高核机器，无此缩放）。
     * 8+ 核 → 1.0；&lt;=2 核 → 0.25；3-7 核线性插值 0.25 + (cores-2)/6*0.75，结果钳制 [0.25, 1.0]。
     * GameConfig 键 bot.env_scale（double，默认 -1 = 自动；键缺失时 getServerDouble 返回 0，等效自动；
     * 显式 &gt;0 时直接用作缩放因子并钳制 [0.1, 1.0]）。
     */
    private static double scaleForCores() {
        double configured = GameConfig.getServerDouble("bot.env_scale");
        if (configured > 0) {
            return Math.max(0.1, Math.min(1.0, configured));
        }
        int cores = Runtime.getRuntime().availableProcessors();
        double scale;
        if (cores >= 8) {
            scale = 1.0;
        } else if (cores <= 2) {
            scale = 0.25;
        } else {
            scale = 0.25 + (cores - 2) / 6.0 * 0.75;
        }
        return Math.max(0.25, Math.min(1.0, scale));
    }

    /** public 访问器：当前生效的环境缩放因子（供 !env status 诊断）。 */
    public static double environmentScale() {
        return scaleForCores();
    }

    private static int spawnTrainingBotsAt(int townMapId, int n, int loLevel, int hiLevel) {
        // gms 增强：训练波按核数缩放（SoloMapling 无此缩放，其运行于高核机器）。
        n = Math.max(1, (int) Math.round(n * scaleForCores()));
        MapleMap map = getMapleMapById(townMapId);
        Point sp = spawnPortal(map);
        if (map == null || sp == null) {
            debugprint(fmt("TrainingBots: no map / spawn portal for {}", townMapId));
            return 0;
        }
        int spawned = spawnScatteredTrainingBots(map, sp, n, loLevel, hiLevel).size();
        debugprint(fmt("TrainingBots: {} spawned on map {} (lv {}..{})", spawned, townMapId, loLevel, hiLevel));
        return spawned;
    }

    public static List<Integer> spawnScatteredTrainingBots(MapleMap map, Point anchor, int n, int loLevel, int hiLevel) {
        List<Point> spots = BotSpotPicker.pickGroundSpots(map, anchor.x, anchor.y, n);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Point spawnAt = i < spots.size() ? spots.get(i) : anchor;
            int baseClass = BotDecorate.rollBaseClass();
            try {
                int botId = createBotId(spawnAt, map, baseClass, loLevel, hiLevel);
                if (botId > 0) {
                    ids.add(botId);
                }
            } catch (Exception e) {
                debugprint(fmt("TrainingBots: create failed on {} ({})", map.getId(), e.getMessage()));
            }
        }
        setAndStartBots(ids, BotTypeManager.BotType.TRAINING_BOT);
        return ids;
    }

    // ── 城镇氛围人口 ────────────────────────────────────────────────────────

    public static void spawnTownPresence() {
        for (TownPresenceConfig.TownEntry town : TownPresenceConfig.towns()) {
            spawnTown(town);
        }
    }

    public static void spawnTown(TownPresenceConfig.TownEntry town) {
        // gms 增强：城镇人口同属大规模 spawn（波 9），入口统一计算 local scale 后缩放
        // share.count() 与 wanderers（SoloMapling 无此缩放）。
        double scale = scaleForCores();
        for (TownPresenceConfig.MapShare share : town.maps()) {
            int scaled = share.count() > 0 ? Math.max(1, (int) Math.round(share.count() * scale)) : 0;
            int n = spawnSocialCohort(share.mapId(), scaled, town.levelLo(), town.levelHi());
            debugprint(fmt("TownPresence: {} social bots on map {} ({}, lv {}..{})",
                    n, share.mapId(), town.name(), town.levelLo(), town.levelHi()));
        }
        if (town.wanderers() > 0) {
            int w = Math.max(1, (int) Math.round(town.wanderers() * scale));
            int spawned = spawnTownWanderers(town.mainMapId(), w, town.levelLo(), town.levelHi());
            debugprint(fmt("TownPresence: {} wanderers on map {} ({})", spawned, town.mainMapId(), town.name()));
        }
    }

    public static int spawnSocialCohort(int mapId, int n, int loLevel, int hiLevel) {
        return spawnTownCohort(mapId, n, loLevel, hiLevel, BotTypeManager.BotType.SOCIAL_BOT);
    }

    public static int spawnTownWanderers(int mapId, int n, int loLevel, int hiLevel) {
        return spawnTownCohort(mapId, n, loLevel, hiLevel, BotTypeManager.BotType.TOWN_WANDERER_BOT);
    }

    private static int spawnTownCohort(int mapId, int n, int loLevel, int hiLevel, BotTypeManager.BotType type) {
        MapleMap map = getMapleMapById(mapId);
        Point anchor = spawnPortal(map);
        if (map == null || anchor == null) {
            debugprint(fmt("TownPresence: no map / spawn portal for {}", mapId));
            return 0;
        }
        List<Point> spots = TownPresenceSampler.sample(map, anchor, n, TownPresenceConfig.overridesFor(mapId));
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Point spawnAt = i < spots.size() ? spots.get(i) : anchor;
            int baseClass = BotDecorate.rollBaseClass();
            try {
                int botId = createBotId(spawnAt, map, baseClass, loLevel, hiLevel);
                if (botId > 0) {
                    ids.add(botId);
                }
            } catch (Exception e) {
                debugprint(fmt("TownPresence: create failed on {} ({})", mapId, e.getMessage()));
            }
        }
        setAndStartBots(ids, type);
        return ids.size();
    }

    // ── 波编排 ──────────────────────────────────────────────────────────────

    private static void runWave(int number, String name, List<Runnable> tasks) {
        log.info("=== Wave {} ({}) starting ===", number, name);
        long start = System.currentTimeMillis();
        int botsBefore = BotGeneration.getBotsCreatedCount();

        runPhase(tasks);

        double seconds = (System.currentTimeMillis() - start) / 1000.0;
        int botsSpawned = BotGeneration.getBotsCreatedCount() - botsBefore;
        log.info("=== Wave {} ({}) complete - {} bots spawned in {}s ===",
                number, name, botsSpawned, String.format("%.1f", seconds));
    }

    // gms 无 ExecutorServiceManager 虚拟线程池；用 BotExecutors.runAsync 并行 + latch 阻塞等待。
    private static void runPhase(List<Runnable> tasks) {
        CountDownLatch latch = new CountDownLatch(tasks.size());
        for (Runnable task : tasks) {
            BotExecutors.runAsync(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    debugprint(fmt("wave task failed: {}", e.getMessage()));
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── FM / Henesys 批量 ───────────────────────────────────────────────────

    private static void spawnFMEntranceBotsBatch(int m1Count, int m2Count, int m5Count) {
        if (m1Count > 0) {
            setAndStartBots(spawnBotsOnMapOnPlatform(m1Count, FM_ENTRANCE, "m1"), BotTypeManager.BotType.FM_BOT);
        }
        if (m2Count > 0) {
            setAndStartBots(spawnBotsOnMapOnPlatform(m2Count, FM_ENTRANCE, "m2"), BotTypeManager.BotType.FM_BOT);
        }
        if (m5Count > 0) {
            setAndStartBots(spawnBotsOnMapOnPlatform(m5Count, FM_ENTRANCE, "m5"), BotTypeManager.BotType.FM_BOT);
        }
    }

    private static void spawnMerchBotsBatch(String platform, int selling, int buying, int nx) {
        if (selling > 0) {
            setAndStartBots(spawnBotsOnMapOnPlatform(selling, FM_ENTRANCE, platform), BotTypeManager.BotType.SELLING_MERCHANT_BOT);
        }
        if (buying > 0) {
            setAndStartBots(spawnBotsOnMapOnPlatform(buying, FM_ENTRANCE, platform), BotTypeManager.BotType.BUYING_MERCHANT_BOT);
        }
        if (nx > 0) {
            setAndStartBots(spawnBotsOnMapOnPlatform(nx, FM_ENTRANCE, platform), BotTypeManager.BotType.NX_MERCHANT_BOT);
        }
    }

    private static void spawnHenesysBotsBatch(int mainCount, int marketCount, int parkCount, int socialCount) {
        if (mainCount > 0) {
            setAndStartBots(spawnBotsOnMapOnPlatform(mainCount, HENESYS, "m1"), BotTypeManager.BotType.HENESYS_BOT);
        }
        if (marketCount > 0) {
            setAndStartBots(spawnBotsOnMapOnPlatform(marketCount, HENESYS_MARKET, "m1"), BotTypeManager.BotType.HENESYS_BOT);
        }
        if (parkCount > 0) {
            setAndStartBots(spawnBotsOnMapOnPlatform(parkCount, HENESYS_PARK, "m1"), BotTypeManager.BotType.HENESYS_BOT);
        }
        if (socialCount > 0) {
            // 源实现此处仅创建 bot 而不 setAndStart（遗留死代码，会留下无 FSM 的裸角色）；
            // gms 侧改为照常启动为 HENESYS_BOT，避免出现无人接管的静态角色。
            int perSpot = Math.max(1, socialCount / 3);
            setAndStartBots(spawnBotsOnMapOnPlatform(perSpot, HENESYS, "m4_social"), BotTypeManager.BotType.HENESYS_BOT);
            setAndStartBots(spawnBotsOnMapOnPlatform(perSpot, HENESYS, "m5_social"), BotTypeManager.BotType.HENESYS_BOT);
            setAndStartBots(spawnBotsOnMapOnPlatform(perSpot, HENESYS, "m6_social"), BotTypeManager.BotType.HENESYS_BOT);
        }
    }

    // FM 房间 mapId 表（源自 SoloMapling FMShopInfoManager 各 region 房间列表；
    // 坐标表不移植，gms 侧以平台撒点替代；perion/elnath 房间无平台 CSV，自动地面回退）。
    private static final Map<String, int[]> FM_ROOM_MAP_IDS = Map.of(
            "henesys", new int[]{910000001, 910000002, 910000003, 910000004, 910000005, 910000006},
            "ludi", new int[]{910000007, 910000008, 910000009, 910000010, 910000011, 910000012},
            "perion", new int[]{910000013, 910000014, 910000015, 910000016, 910000017},
            "elnath", new int[]{910000018, 910000019, 910000020, 910000021, 910000022}
    );

    // 对应源 ArtificialFreeMarket.populateFreeMarketRegion（并行点燃区域内每个房间的商店生成）。
    // gms 增强（波 2）：摊位商店经济已完整移植（org.gms.server.bot.freemarket.ArtificialFreeMarket：
    // 雇佣商人箱 + bot 个人商店 + 定价/招牌），本方法现在两层并存——
    //   1) spawnFMRoomBots：商人 bot 试点的既有行为（每房间小规模 FSM 商人，按核数缩放）；
    //   2) ArtificialFreeMarket.populateFreeMarketRegion：完整摊位生成管线。
    // 摊位数对齐源原值（源每房 24-28 点、全量约 577 摊），不按 scaleForCores() 缩放；
    // 2 核小机如遇性能问题，可后续在此接线处加按 scale 的子集逻辑。
    // gms 简化：商人 bot 试点 region 内顺序执行（region 级已由 wave task 并行）。
    // 审计修正（LOW）：region 名做 toLowerCase 容错（源实现同款容错，调用方可能传 "HENESYS"），
    // region 为 null 先判再查表。
    // 审计修正（P2）：第 2 层摊位生成已并入 wave 时序（同步等待完成）——不再 runAsync 包裹，
    // 否则外层 wave 任务立即返回、latch 提前放行，摊位生成与后续 wave 训练 bot 并发叠加峰值。
    private static void populateFreeMarketRegion(String region) {
        int[] roomMapIds = region == null ? null : FM_ROOM_MAP_IDS.get(region.toLowerCase(Locale.ROOT));
        if (roomMapIds == null) {
            // 源对非法 region 抛 IllegalArgumentException；wave 任务跑在异步线程里异常会被吞掉，
            // gms 改为 warn + return 显式可见。
            log.warn("populateFreeMarketRegion: unknown region '{}' (expected henesys/ludi/perion/elnath)", region);
            return;
        }
        // 第 1 层：商人 bot 试点（既有行为，保留）。
        for (int roomMapId : roomMapIds) {
            spawnFMRoomBots(roomMapId);
        }
        // 第 2 层：完整摊位生成管线（波 2 接线）。审计修正（P2）：当前线程同步执行——
        // 旧实现 runAsync 包裹使本 wave 任务立即返回、latch 提前放行，摊位生成与后续
        // wave（波 8 训练 bot）生成并发叠加（2 核 CPU 峰值）。gms 对齐源 wave 时序（源语义
        // 即 wave 内完成）；摊位数 577 在 2 核上生成较慢，但错峰 200ms/摊已内置。
        // 审计修正（LOW-6）：catch(Throwable) 对齐 fmshop 命令写法（Error 同样可见可记）。
        try {
            ArtificialFreeMarket.populateFreeMarketRegion(region);
        } catch (Throwable t) {
            log.warn("populateFreeMarketRegion: ArtificialFreeMarket pipeline failed for region '{}'", region, t);
        }
    }

    private static void spawnFMRoomBots(int roomMapId) {
        if (getMapleMapById(roomMapId) == null) {
            debugprint(fmt("populateFreeMarketRegion: room map {} not found, skipped", roomMapId));
            return;
        }
        // 商人 bot 试点（波 2 后与完整摊位管线并存）：源每房间约 24-28 摊位（坐标硬编码于
        // FMShopInfoManager）；试点部分以 selling=2 / buying=2 / nx=1 为基数并按核数缩放（2 核下实际更少），
        // 完整摊位由 ArtificialFreeMarket.populateFreeMarketRegion 异步生成（数量对齐源原值，不缩放）。
        double scale = scaleForCores();
        int selling = (int) Math.round(2 * scale);
        int buying = (int) Math.round(2 * scale);
        int nx = (int) Math.round(1 * scale);
        if (selling > 0) {
            setAndStartBots(spawnBotsOnMapOnPlatform(selling, roomMapId, "m1"), BotTypeManager.BotType.SELLING_MERCHANT_BOT);
        }
        if (buying > 0) {
            setAndStartBots(spawnBotsOnMapOnPlatform(buying, roomMapId, "m1"), BotTypeManager.BotType.BUYING_MERCHANT_BOT);
        }
        if (nx > 0) {
            setAndStartBots(spawnBotsOnMapOnPlatform(nx, roomMapId, "m1"), BotTypeManager.BotType.NX_MERCHANT_BOT);
        }
        debugprint(fmt("populateFreeMarketRegion: room {} merchants selling={} buying={} nx={}", roomMapId, selling, buying, nx));
    }

    public static void spawnCasinoNpcs() {
        int casinoMap = 100000203;
        spawnNpc(CASINO_NPC_ID, casinoMap, 1321, 214);
        spawnNpc(NpcId.RPS_ADMIN, casinoMap, 899, 275);
    }

    // ── 手动 spawn 入口（对应源 EnvironmentManager 各 public 方法） ─────────

    public static void spawnBotsInFMEntrance() {
        int fm_entrance = 910000000;
        setAndStartBots(spawnBotsOnMapOnPlatform(15, fm_entrance, "m1"), BotTypeManager.BotType.FM_BOT);
        setAndStartBots(spawnBotsOnMapOnPlatform(15, fm_entrance, "m5"), BotTypeManager.BotType.FM_BOT);
        setAndStartBots(spawnBotsOnMapOnPlatform(15, fm_entrance, "m2"), BotTypeManager.BotType.FM_BOT);
    }

    public static void spawnMerchBotsInFMEntrance() {
        int fm_entrance = 910000000;
        setAndStartBots(spawnBotsOnMapOnPlatform(7, fm_entrance, "m1"), BotTypeManager.BotType.SELLING_MERCHANT_BOT);
        setAndStartBots(spawnBotsOnMapOnPlatform(7, fm_entrance, "m1"), BotTypeManager.BotType.BUYING_MERCHANT_BOT);
        setAndStartBots(spawnBotsOnMapOnPlatform(1, fm_entrance, "m1"), BotTypeManager.BotType.NX_MERCHANT_BOT);

        setAndStartBots(spawnBotsOnMapOnPlatform(7, fm_entrance, "m5"), BotTypeManager.BotType.SELLING_MERCHANT_BOT);
        setAndStartBots(spawnBotsOnMapOnPlatform(7, fm_entrance, "m5"), BotTypeManager.BotType.BUYING_MERCHANT_BOT);
        setAndStartBots(spawnBotsOnMapOnPlatform(2, fm_entrance, "m5"), BotTypeManager.BotType.NX_MERCHANT_BOT);

        setAndStartBots(spawnBotsOnMapOnPlatform(6, fm_entrance, "m2"), BotTypeManager.BotType.SELLING_MERCHANT_BOT);
        setAndStartBots(spawnBotsOnMapOnPlatform(6, fm_entrance, "m2"), BotTypeManager.BotType.BUYING_MERCHANT_BOT);
        setAndStartBots(spawnBotsOnMapOnPlatform(2, fm_entrance, "m2"), BotTypeManager.BotType.NX_MERCHANT_BOT);
    }

    public static void spawnHenesysBots() {
        int henesys_map = 100000000;
        setAndStartBots(spawnBotsOnMapOnPlatform(30, henesys_map, "m1"), BotTypeManager.BotType.HENESYS_BOT);

        List<Integer> bots2 = spawnBotsOnMapOnPlatform(10, 100000100, "m1");
        List<Integer> bots3 = spawnBotsOnMapOnPlatform(10, 100000100, "m2");
        setAndStartBots(bots2, BotTypeManager.BotType.HENESYS_BOT);
        setAndStartBots(bots3, BotTypeManager.BotType.HENESYS_BOT);

        setAndStartBots(spawnBotsOnMapOnPlatform(10, 100000200, "m1"), BotTypeManager.BotType.HENESYS_BOT);

        setAndStartBots(spawnBotsOnMapOnPlatform(3, henesys_map, "m4_social"), BotTypeManager.BotType.HENESYS_BOT);
        setAndStartBots(spawnBotsOnMapOnPlatform(3, henesys_map, "m5_social"), BotTypeManager.BotType.HENESYS_BOT);
        setAndStartBots(spawnBotsOnMapOnPlatform(3, henesys_map, "m6_social"), BotTypeManager.BotType.HENESYS_BOT);
    }

    public static void spawnGachaBotsHenesys() {
        setAndStartBots(
                spawnBotsOnMapOnPlatformInRadius(3, 100000100, "m1", new Point(366, 154), 250),
                BotTypeManager.BotType.GACHA_BOT);
    }

    private static int randomizeCount(int base) {
        return Math.max(1, base + random.nextInt(3) - 1);
    }

    public static void spawnFillerBotsHenesys() {
        int map = HENESYS;
        debugprint("Spawning filler bots in Henesys...");
        List<Integer> allIds = new ArrayList<>();
        allIds.addAll(spawnFillerBots(randomizeCount(5), map, new Point(-696, 274), new Point(-10, 274)));
        allIds.addAll(spawnFillerBots(randomizeCount(2), map, new Point(-144, 218), new Point(36, 218)));
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(-286, 101), new Point(7, 94)));
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(248, 274), new Point(573, 274)));
        allIds.addAll(spawnFillerBots(randomizeCount(6), map, new Point(2596, 334), new Point(3347, 334)));
        allIds.addAll(spawnFillerBots(randomizeCount(6), map, new Point(3393, 124), new Point(4247, 124)));
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(3831, 454), new Point(4382, 454)));
        allIds.addAll(spawnFillerBots(randomizeCount(8), map, new Point(4832, 454), new Point(5762, 454)));
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(5547, -176), new Point(6232, -176)));
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(4732, -116), new Point(5424, -116)));
        setAndStartBots(allIds, BotTypeManager.BotType.SOCIAL_BOT);
        debugprint("Henesys filler bots complete.");
    }

    public static void spawnFillerBotsHenesysMarket() {
        int map = HENESYS_MARKET;
        debugprint("Spawning filler bots in Henesys Market...");
        List<Integer> allIds = new ArrayList<>();
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(-548, 154), new Point(568, 154)));
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(592, 154), new Point(1148, 154)));
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(-105, 154), new Point(568, 154)));
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(1340, 214), new Point(2442, 214)));
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(1369, -56), new Point(2546, -56)));
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(2689, -116), new Point(3636, -116)));
        allIds.addAll(spawnFillerBots(randomizeCount(5), map, new Point(2744, 94), new Point(3494, 94)));
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(3760, 94), new Point(5100, 94)));
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(3852, -176), new Point(4427, -176)));
        setAndStartBots(allIds, BotTypeManager.BotType.SOCIAL_BOT);
        debugprint("Henesys Market filler bots complete.");
    }

    public static void spawnFillerBotsHenesysPark() {
        int map = HENESYS_PARK;
        debugprint("Spawning filler bots in Henesys Park...");
        List<Integer> allIds = new ArrayList<>();
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(-53, 454), new Point(597, 454)));
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(982, 424), new Point(1288, 424)));
        allIds.addAll(spawnFillerBots(randomizeCount(5), map, new Point(984, 574), new Point(1606, 574)));
        allIds.addAll(spawnFillerBots(randomizeCount(2), map, new Point(1563, 304), new Point(1769, 304)));
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(1915, 574), new Point(2909, 574)));
        allIds.addAll(spawnFillerBots(randomizeCount(1), map, new Point(2019, 364), new Point(2118, 364)));
        allIds.addAll(spawnFillerBots(randomizeCount(2), map, new Point(2198, 424), new Point(2471, 424)));
        allIds.addAll(spawnFillerBots(randomizeCount(1), map, new Point(2549, 364), new Point(2663, 364)));
        allIds.addAll(spawnFillerBots(randomizeCount(2), map, new Point(3233, 334), new Point(3607, 334)));
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(3585, 694), new Point(4390, 694)));
        setAndStartBots(allIds, BotTypeManager.BotType.SOCIAL_BOT);
        debugprint("Henesys Park filler bots complete.");
    }

    public static void spawnFillerBotsGameZone() {
        int map = HENESYS_GAME_ZONE;
        debugprint("Spawning filler bots in Henesys Game Zone...");
        List<Integer> allIds = new ArrayList<>();
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(1027, 394), new Point(1483, 394)));
        allIds.addAll(spawnFillerBots(randomizeCount(6), map, new Point(-83, 274), new Point(340, 274)));
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(263, 64), new Point(929, 64)));
        setAndStartBots(allIds, BotTypeManager.BotType.SOCIAL_BOT);
        debugprint("Henesys Game Zone filler bots complete.");
    }

    public static void spawnFillerBotsPotionShop() {
        int map = HENESYS_POTION_SHOP;
        debugprint("Spawning filler bots in Henesys Potion Shop...");
        List<Integer> allIds = new ArrayList<>();
        allIds.addAll(spawnFillerBotsLockedY(randomizeCount(3), map, new Point(-370, 182), new Point(175, 182)));
        allIds.addAll(spawnFillerBotsLockedY(randomizeCount(2), map, new Point(193, 182), new Point(370, 182)));
        allIds.addAll(spawnFillerBotsLockedY(randomizeCount(3), map, new Point(-112, -127), new Point(245, -127)));
        setAndStartBots(allIds, BotTypeManager.BotType.SOCIAL_BOT);
        debugprint("Henesys Potion Shop filler bots complete.");
    }

    public static void spawnDropGameBotPotionShop() {
        debugprint("Spawning Drop Game Bot in Henesys Potion Shop...");
        Point spawn = new Point(45, 182);
        BotExecutors.runAsync(() -> {
            Character fakechar = createBotWithRetry(spawn, HENESYS_POTION_SHOP, 5);
            if (fakechar != null) {
                BotExecutors.schedule(() -> {
                    setAndStartBots(List.of(fakechar.getId()), BotTypeManager.BotType.DROP_GAME_BOT);
                    debugprint("Drop Game Bot started in Henesys Potion Shop.");
                }, 5 * 1000L);
            } else {
                debugprint("Failed to spawn Drop Game Bot in Henesys Potion Shop.");
            }
        });
    }

    private static final int HHG1 = 104040000;

    private static final int W_SWORD = 1302012, W_SPEAR = 1432007, W_WAND = 1372015,
            W_BOW = 1452009, W_CROSSBOW = 1462009, W_CLAW = 1472026, W_DAGGER = 1332018;

    private record TierSlot(Point pos, int weaponId, int t1, int t2, int t3, int t4) {
        int jobForTier(int tier) {
            return switch (tier) {
                case 2 -> t2;
                case 3 -> t3;
                case 4 -> t4;
                default -> t1;
            };
        }
    }

    private static final List<TierSlot> ATTACK_TEST_SLOTS = List.of(
            new TierSlot(new Point(240, 215), W_SWORD, 100, 110, 111, 112),
            new TierSlot(new Point(1022, 215), W_SPEAR, 100, 130, 131, 132),
            new TierSlot(new Point(340, -85), W_WAND, 200, 210, 211, 212),
            new TierSlot(new Point(912, -85), W_WAND, 200, 220, 221, 222),
            new TierSlot(new Point(631, 215), W_WAND, 200, 230, 231, 232),
            new TierSlot(new Point(900, -325), W_BOW, 300, 310, 311, 312),
            new TierSlot(new Point(404, -325), W_CROSSBOW, 300, 320, 321, 322),
            new TierSlot(new Point(391, -565), W_CLAW, 400, 410, 411, 412),
            new TierSlot(new Point(866, -565), W_DAGGER, 400, 420, 421, 422)
    );

    public static void spawnAttackTestBots(int tierArg) {
        final int tier = (tierArg < 1 || tierArg > 4) ? 1 : tierArg;
        final int level = switch (tier) {
            case 2 -> 50;
            case 3 -> 100;
            case 4 -> 130;
            default -> 25;
        };
        debugprint(fmt("Spawning tier-{} attack test bots on Henesys Hunting Ground 1...", tier));
        for (TierSlot slot : ATTACK_TEST_SLOTS) {
            BotExecutors.runAsync(() -> {
                Character bot = createBotWithRetry(slot.pos(), HHG1, 5);
                if (bot == null) {
                    debugprint(fmt("Failed to spawn attack test bot at {}", slot.pos()));
                    return;
                }
                bot.setLevel(level);
                bot.setJob(Job.getById(slot.jobForTier(tier)));
                BotCustomization.EquipBot(bot, slot.weaponId());
                setAndStartBots(List.of(bot.getId()), BotTypeManager.BotType.TEST_ATTACK_BOT);
            });
        }
    }

    public static void spawnDropGameSpectatorsPotionShop() {
        debugprint("Spawning Drop Game Spectator bots in Henesys Potion Shop...");
        Point[] spots = {
                new Point(145, 145), new Point(76, 22), new Point(297, -28),
                new Point(-87, -25), new Point(-269, -27),
                new Point(-142, 31), new Point(-28, 103), new Point(151, -43),
                new Point(-142, 141)
        };
        List<Point> available = new ArrayList<>(List.of(spots));
        Collections.shuffle(available);
        int count = 3 + new Random().nextInt(4);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < count && i < available.size(); i++) {
            Character bot = createBotWithRetry(available.get(i), HENESYS_POTION_SHOP, 3);
            if (bot != null) {
                ids.add(bot.getId());
            }
        }
        if (!ids.isEmpty()) {
            setAndStartBots(ids, BotTypeManager.BotType.SOCIAL_BOT);
            debugprint("Spawned " + ids.size() + " Drop Game Spectator bots in Potion Shop.");
        }
    }

    public static void spawnTutorialBot() {
        debugprint("Spawning Tutorial Bot on Maple Island...");
        Point spawn = new Point(158, 485);
        Character fakechar = createBotWithRetry(spawn, MAPLE_ISLAND_TUTORIAL, 5);
        if (fakechar != null) {
            setAndStartBots(List.of(fakechar.getId()), BotTypeManager.BotType.TUTORIAL_BOT);
            debugprint("Tutorial Bot started on Maple Island.");
        } else {
            debugprint("Failed to spawn Tutorial Bot on Maple Island.");
        }
    }

    public static void spawnJQBotsPetPark() {
        debugprint("Spawning JQ Bots in Henesys Pet Park...");
        List<Integer> botIds = spawnBotsOnMapOnPlatform(15, HENESYS_PET_PARK, "m1");
        setAndStartBots(botIds, BotTypeManager.BotType.HENESYS_JQ_BOT);
        debugprint(fmt("Pet Park JQ bots spawned: {}", botIds.size()));
    }

    public static void spawnSocialBotsPetPark() {
        int map = HENESYS_PET_PARK;
        debugprint("Spawning social bots in Henesys Pet Park...");
        List<Integer> allIds = new ArrayList<>();
        allIds.addAll(spawnFillerBots(1, map, new Point(-194, 34), new Point(184, 34)));
        allIds.addAll(spawnFillerBots(2, map, new Point(-449, 154), new Point(369, 154)));
        allIds.addAll(spawnFillerBots(3, map, new Point(618, 154), new Point(1375, 154)));
        allIds.addAll(spawnFillerBots(1, map, new Point(841, -116), new Point(1125, -116)));
        allIds.addAll(spawnFillerBots(1, map, new Point(437, -326), new Point(810, -326)));
        allIds.addAll(spawnFillerBots(1, map, new Point(531, -626), new Point(731, -626)));
        allIds.addAll(spawnFillerBots(1, map, new Point(790, -506), new Point(993, -506)));
        allIds.addAll(spawnFillerBots(1, map, new Point(1072, -446), new Point(1274, -446)));
        allIds.addAll(spawnFillerBots(3, map, new Point(-1808, 274), new Point(-738, 274)));
        setAndStartBots(allIds, BotTypeManager.BotType.SOCIAL_BOT);
        debugprint(fmt("Pet Park social bots spawned: {}", allIds.size()));
    }

    public static void spawnGameZoneHostBots() {
        debugprint("Spawning Game Zone Host Bots...");
        Point[] spawns = {new Point(503, 250), new Point(716, 254)};
        List<Integer> botIds = new ArrayList<>();
        for (Point spawn : spawns) {
            Character fakechar = createBotWithRetry(spawn, HENESYS_GAME_ZONE, 5);
            if (fakechar != null) {
                botIds.add(fakechar.getId());
            } else {
                debugprint(fmt("Failed to spawn Game Zone Host Bot at {}", spawn));
            }
        }
        if (!botIds.isEmpty()) {
            setAndStartBots(botIds, BotTypeManager.BotType.GAME_ZONE_HOST_BOT);
            debugprint(fmt("Game Zone Host Bots started: {}", botIds.size()));
        }
    }

    public static void spawnBlackjackTables() {
        debugprint("Spawning Blackjack Tables in Game Zone...");
        spawnBlackjackTable(new Point(-947, 64), new Point(-169, 64), new Point(-920, 274), new Point(-169, 274));
        spawnBlackjackTable(new Point(-939, -296), new Point(-152, -296), new Point(-937, -116), new Point(-149, -116));
        spawnBlackjackTable(new Point(226, -296), new Point(937, -296), new Point(227, -116), new Point(940, -116));
        spawnBlackjackTable(new Point(-927, -656), new Point(-130, -656), new Point(-956, -476), new Point(-151, -476));
        spawnBlackjackTable(new Point(229, -656), new Point(924, -656), new Point(220, -476), new Point(943, -476));
        debugprint("All Blackjack Tables spawned.");
    }

    private static Point[] calculateTablePositions(Point topP1, Point topP2, Point botP1, Point botP2) {
        int topMinX = Math.min(topP1.x, topP2.x);
        int topMaxX = Math.max(topP1.x, topP2.x);
        int topY = topP1.y;
        int topThird = (topMaxX - topMinX) / 3;

        int botMinX = Math.min(botP1.x, botP2.x);
        int botMaxX = Math.max(botP1.x, botP2.x);
        int botY = botP1.y;
        int botThird = (botMaxX - botMinX) / 3;

        return new Point[]{
                new Point(topMinX + topThird + topThird / 2, topY),
                new Point(topMinX + topThird / 2 + jitter(), topY),
                new Point(topMinX + topThird * 2 + topThird / 2 + jitter(), topY),
                new Point(botMinX + botThird / 2 + jitter(), botY),
                new Point(botMinX + botThird + botThird / 2 + jitter(), botY),
                new Point(botMinX + botThird * 2 + botThird / 2 + jitter(), botY),
        };
    }

    private static int jitter() {
        return random.nextInt(125) - 50;
    }

    private static void spawnBlackjackTable(Point topP1, Point topP2, Point botP1, Point botP2) {
        Point[] seats = calculateTablePositions(topP1, topP2, botP1, botP2);
        int playerCount = 2 + random.nextInt(4);

        Character dealerChar = createBotWithRetry(seats[0], HENESYS_GAME_ZONE, 5);
        if (dealerChar == null) {
            debugprint("Failed to spawn Blackjack dealer bot");
            return;
        }
        setAndStartBots(List.of(dealerChar.getId()), BotTypeManager.BotType.BLACKJACK_DEALER);

        List<Integer> playerSeatIndices = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        Collections.shuffle(playerSeatIndices);
        List<Character> playerChars = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            int seatIdx = playerSeatIndices.get(i);
            Character playerChar = createBotWithRetry(seats[seatIdx], HENESYS_GAME_ZONE, 5);
            if (playerChar != null) {
                playerChars.add(playerChar);
            }
        }

        BotSM dealerBot = BotStorage.getBotById(dealerChar.getId());
        if (dealerBot instanceof BlackjackDealerBot bjDealer) {
            for (Character playerChar : playerChars) {
                bjDealer.getTable().addPlayer(playerChar);
                bjDealer.getInteractors().setRespondant(playerChar);
            }
            BotExecutors.schedule(() -> {
                for (Character playerChar : playerChars) {
                    botFaceTowardsPoint(playerChar, seats[0]);
                }
            }, BotGeneration.SPAWN_CHOREOGRAPHY_MAX_MS + 500);
            debugprint(fmt("Blackjack table spawned: dealer={}, players={}", dealerChar.getId(), playerChars.size()));
        } else {
            debugprint("Failed to retrieve BlackjackDealerBot from BotStorage");
        }
    }

    public static void convertRandomFillersToScrollBots() {
        debugprint("Converting random filler bots to Scroll Bots across Henesys maps...");
        int[] maps = {HENESYS, HENESYS_MARKET, HENESYS_PARK, HENESYS_POTION_SHOP, HENESYS_GAME_ZONE};
        for (int mapId : maps) {
            int count = 1 + random.nextInt(3);
            convertRandomIdleBotsToScrollBots(mapId, count);
        }
        debugprint("Scroll Bot conversion complete.");
    }

    public static void convertRandomIdleBotsToScrollBots(int mapId, int count) {
        List<Character> allChars = getAllCharsOnMap(mapId);
        List<Integer> idleBotIds = allChars.stream()
                .filter(chr -> {
                    if (!BotHelpers.isBot(chr)) {
                        return false;
                    }
                    BotSM bot = BotStorage.getBotById(chr.getId());
                    return bot != null && bot.isAvailableForAmbientActions();
                })
                .map(Character::getId)
                .collect(Collectors.toList());

        if (idleBotIds.isEmpty()) {
            debugprint(fmt("No idle bots found on map {} to convert", mapId));
            return;
        }
        Collections.shuffle(idleBotIds);
        int toConvert = Math.min(count, idleBotIds.size());
        List<Integer> selected = idleBotIds.subList(0, toConvert);
        debugprint(fmt("Converting {} idle bots to Scroll Bots on map {}: {}", toConvert, mapId, selected));
        setAndStartBots(selected, BotTypeManager.BotType.SCROLL_BOT);
    }

    public static void spawnOPQBotsInLobby() {
        int totalBots = 10 + random.nextInt(6);
        MapleMap map = getMapleMapById(OPQ_LOBBY);
        Point anchor = spawnPortal(map);
        if (map == null || anchor == null) {
            debugprint("No OPQ lobby map / spawn portal");
            return;
        }
        // TODO: 本方法暂未接平台（保持整图地面撒点现状）。
        List<Point> spots = BotHelpers.pickGroundSpots(map, anchor, totalBots);
        debugprint(fmt("Spawning {} OPQ bots in lobby...", totalBots));

        List<Integer> allBotIds = new ArrayList<>();
        for (Point spot : spots) {
            Character bot = createBotWithRetry(spot, OPQ_LOBBY, 5);
            if (bot != null) {
                allBotIds.add(bot.getId());
            }
        }
        if (!allBotIds.isEmpty()) {
            setBotsLevelRange(allBotIds, 50, 70);
            setAndStartBots(allBotIds, BotTypeManager.BotType.OPQ_BOT);
            debugprint(fmt("OPQ lobby bots spawned and started: {}", allBotIds.size()));
        }
    }

    public static void setBotsLevelRange(List<Integer> botIds, int minLevel, int maxLevel) {
        for (int botId : botIds) {
            Character bot = DefaultBotServerAccess.INSTANCE.getCharacterById(botId);
            if (bot != null) {
                bot.setLevel(minLevel + random.nextInt(maxLevel - minLevel + 1));
            }
        }
    }

    // ── 平台批量 spawn / 创建辅助（平台系统已移植：spawn 路径平台优先 + 地面回退） ──

    public static void setAndStartBots(List<Integer> botIds, BotTypeManager.BotType type) {
        for (int botId : botIds) {
            Character bot = DefaultBotServerAccess.INSTANCE.getCharacterById(botId);
            if (bot == null) {
                continue;
            }
            BotSM existing = BotStorage.getBotById(botId);
            if (existing == null) {
                type.createAndSetBot(bot);
                BotTypeManager.manuallyStartBot(bot);
                // 对齐 BotStartupManager.spawnOne 的做法：首次 enable 会启动 ObserverTracker 1s 观察轮询
                // （SocialBot 的 relocate 依赖 isMapObserved）并异步 warm 导航图。enable 幂等，批量调用安全。
                GCMovement.enable(bot);
            } else {
                BotTypeManager.convertBotType(bot, type);
            }
        }
    }

    public static void spawnNpcAtPlayer(Character player, int npcId) {
        if (player == null || player.getMap() == null) {
            return;
        }
        spawnNpc(npcId, player.getMapId(), player.getPosition().x, player.getPosition().y);
    }

    private static void spawnNpc(int npcId, int mapId, int x, int y) {
        MapleMap map = getMapleMapById(mapId);
        if (map == null) {
            return;
        }
        NPC npc = LifeFactory.getNPC(npcId);
        if (npc == null) {
            return;
        }
        Point pos = new Point(x, y);
        npc.setPosition(pos);
        npc.setCy(y);
        npc.setRx0(x + 50);
        npc.setRx1(x - 50);
        npc.setFh(map.getFootholds().findBelow(pos).getId());
        map.addMapObject(npc);
        map.broadcastMessage(PacketCreator.spawnNPC(npc));
    }

    // 平台批量 spawn：平台优先（PlatformParser 解析 CSV + PlatformSpawner 取未占用点，
    // 坐标经 groundSafePoint 安全校验），平台解析失败/无点时回退整图地面撒点。
    private static List<Integer> spawnBotsOnMapOnPlatform(int numBots, int mapId, String platformId) {
        MapleMap map = getMapleMapById(mapId);
        if (map == null) {
            debugprint(fmt("spawnBotsOnMapOnPlatform: no map for {} ({})", mapId, platformId));
            return List.of();
        }
        // 平台优先：解析平台 CSV 并逐个取未占用点 createBot。
        List<Point> platformSpots = platformSpawnPoints(numBots, mapId, platformId);
        if (!platformSpots.isEmpty()) {
            List<Integer> ids = new ArrayList<>();
            for (Point spot : platformSpots) {
                Character bot = createBotWithRetry(groundSafePoint(map, spot), mapId, 5);
                if (bot != null) {
                    ids.add(bot.getId());
                }
            }
            if (!ids.isEmpty()) {
                return ids;
            }
        }
        // 回退：平台解析失败/无点时，以出生 portal 为锚点用 BotHelpers.pickGroundSpots 整图撒点。
        Point anchor = spawnPortal(map);
        if (anchor == null) {
            debugprint(fmt("spawnBotsOnMapOnPlatform: no portal for {} ({})", mapId, platformId));
            return List.of();
        }
        List<Point> spots = BotHelpers.pickGroundSpots(map, anchor, numBots);
        List<Integer> ids = new ArrayList<>();
        for (Point spot : spots) {
            Character bot = createBotWithRetry(spot, mapId, 5);
            if (bot != null) {
                ids.add(bot.getId());
            }
        }
        return ids;
    }

    private static List<Integer> spawnBotsOnMapOnPlatformInRadius(int numBots, int mapId, String platformId, Point center, int radius) {
        MapleMap map = getMapleMapById(mapId);
        if (map == null) {
            return List.of();
        }
        // 平台优先：平台未占用点中取半径内候选，逐个 createBot。
        List<Point> platformSpots = platformSpawnPointsInRadius(numBots, mapId, platformId, center, radius);
        if (!platformSpots.isEmpty()) {
            List<Integer> ids = new ArrayList<>();
            for (Point spot : platformSpots) {
                Character bot = createBotWithRetry(groundSafePoint(map, spot), mapId, 5);
                if (bot != null) {
                    ids.add(bot.getId());
                }
            }
            if (!ids.isEmpty()) {
                return ids;
            }
        }
        // 回退：现有半径内地面选点逻辑。
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < numBots; i++) {
            Point target = pickPointInRadius(map, center, radius);
            Character bot = createBotWithRetry(target, mapId, 5);
            if (bot != null) {
                ids.add(bot.getId());
            }
        }
        return ids;
    }

    // 平台选点辅助：解析 CSV 平台；平台无效（解析失败/无记录点）时返回空列表。
    private static List<Point> platformSpawnPoints(int numBots, int mapId, String platformId) {
        Platform platform = PlatformParser.parsePlatform(mapId, platformId);
        if (platform == null || platform.getSortedPoints().isEmpty()) {
            return List.of();
        }
        List<Point> occupied = new ArrayList<>();
        List<Point> spots = new ArrayList<>();
        for (int i = 0; i < numBots; i++) {
            Point p = PlatformSpawner.findUnoccupiedPoint(platform, occupied);
            occupied.add(p);
            spots.add(p);
        }
        return spots;
    }

    // 平台选点辅助（半径版）：多次尝试取半径内未占用点，凑不满时返回已找到的部分。
    private static List<Point> platformSpawnPointsInRadius(int numBots, int mapId, String platformId, Point center, int radius) {
        Platform platform = PlatformParser.parsePlatform(mapId, platformId);
        if (platform == null || platform.getSortedPoints().isEmpty()) {
            return List.of();
        }
        List<Point> occupied = new ArrayList<>();
        List<Point> spots = new ArrayList<>();
        for (int i = 0; i < numBots; i++) {
            Point candidate = null;
            for (int attempt = 0; attempt < 100; attempt++) {
                Point p = PlatformSpawner.findUnoccupiedPoint(platform, occupied);
                if (Math.abs(p.x - center.x) <= radius && Math.abs(p.y - center.y) <= radius) {
                    candidate = p;
                    break;
                }
            }
            if (candidate == null) {
                break;
            }
            occupied.add(candidate);
            spots.add(candidate);
        }
        return spots;
    }

    // 坐标安全校验（审计修正 P2）：gms wz 数据集与源不同，平台点可能悬空/陷地。
    // 旧实现「getPointBelow 差>60px 替换」有两处缺陷：固定 60px 无法区分低平台与悬空
    // （40px 高的低平台点与地面差约 40px，会被地面点替换而丢失低平台语义），
    // 且 ground==null 时悬空点被直接保留。新实现先查正下方 foothold：平台点贴合
    // foothold 表面（y 落在其 y1/y2 范围 ±2px 内，低平台自身 foothold 差≈0）则保留
    // 平台点；否则仅在平台点与地面差 > GROUND_SAFE_DROP_PX 时用地面点替换，其余保留平台点。
    private static final int GROUND_SAFE_DROP_PX = 20;

    private static Point groundSafePoint(MapleMap map, Point platformPoint) {
        if (map.getFootholds() != null) {
            Foothold fh = map.getFootholds().findBelow(new Point(platformPoint.x, platformPoint.y));
            if (fh != null) {
                int fhMinY = Math.min(fh.getY1(), fh.getY2());
                int fhMaxY = Math.max(fh.getY1(), fh.getY2());
                if (platformPoint.y >= fhMinY - 2 && platformPoint.y <= fhMaxY + 2) {
                    return platformPoint; // 平台贴合：防止低平台语义丢失
                }
            }
        }
        Point ground = map.getPointBelow(new Point(platformPoint.x, platformPoint.y));
        if (ground != null && Math.abs(ground.y - platformPoint.y) > GROUND_SAFE_DROP_PX) {
            return ground; // 悬空/陷地：地面点替换
        }
        return platformPoint;
    }

    private static Point pickPointInRadius(MapleMap map, Point center, int radius) {
        for (int i = 0; i < 12; i++) {
            int x = center.x + random.nextInt(2 * radius + 1) - radius;
            Point ground = map.getPointBelow(new Point(x, center.y));
            if (ground != null && Math.abs(ground.x - center.x) <= radius) {
                return ground;
            }
        }
        return center;
    }

    public static List<Integer> spawnFillerBots(int numBots, int mapId, Point p1, Point p2) {
        MapleMap map = getMapleMapById(mapId);
        if (map == null) {
            return List.of();
        }
        int minX = Math.min(p1.x, p2.x);
        int maxX = Math.max(p1.x, p2.x);
        int baseY = p1.y;
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < numBots; i++) {
            int x = minX + (maxX > minX ? random.nextInt(maxX - minX + 1) : 0);
            Point ground = map.getPointBelow(new Point(x, baseY));
            Point spawn = ground != null ? ground : new Point(x, baseY);
            Character bot = createBotWithRetry(spawn, mapId, 5);
            if (bot != null) {
                ids.add(bot.getId());
                maybeScheduleChair(bot);
            }
        }
        return ids;
    }

    public static List<Integer> spawnFillerBotsLockedY(int numBots, int mapId, Point p1, Point p2) {
        MapleMap map = getMapleMapById(mapId);
        if (map == null) {
            return List.of();
        }
        int minX = Math.min(p1.x, p2.x);
        int maxX = Math.max(p1.x, p2.x);
        int baseY = p1.y;
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < numBots; i++) {
            int x = minX + (maxX > minX ? random.nextInt(maxX - minX + 1) : 0);
            Point spawn = new Point(x, baseY);
            Character bot = createBotWithRetry(spawn, mapId, 5);
            if (bot != null) {
                ids.add(bot.getId());
                maybeScheduleChair(bot);
            }
        }
        return ids;
    }

    private static void maybeScheduleChair(Character bot) {
        if (Math.random() < 0.20) {
            BotExecutors.schedule(() -> {
                if (bot != null && BotStorage.botLoggedIn(bot.getId()) && bot.getMap() != null) {
                    bot.sitChair(BotCustomization.getRandomChairId());
                }
            }, BotGeneration.SPAWN_CHOREOGRAPHY_MAX_MS + 500);
        }
    }

    // ── 创建 / 查询辅助 ─────────────────────────────────────────────────────

    private static int createBotId(Point pos, MapleMap map, int baseClass, int loLevel, int hiLevel) {
        // 装饰已并入 BotGeneration.createBot（baseClass>0 分支），此处直接委托，避免双重装饰。
        return BotGeneration.createBot(pos, map, baseClass, loLevel, hiLevel);
    }

    // 审计修正（LOW）：botId>0 但 getCharacterById 返回 null 时不再立即重新 createBot
    // （角色异步可见存在窗口期，重建会泄漏孤儿 bot），改为轮询同一 id（30 次 ×100ms）；
    // 轮询超时仍不可见才重置 botId，由下一轮 attempt 重新 createBot。
    private static final int CREATE_BOT_POLL_MAX = 30;
    private static final long CREATE_BOT_POLL_INTERVAL_MS = 100;

    private static Character createBotWithRetry(Point spawn, int mapId, int maxRetries) {
        MapleMap map = getMapleMapById(mapId);
        if (map == null) {
            return null;
        }
        int botId = 0;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (botId <= 0) {
                    botId = BotGeneration.createBot(spawn, map);
                }
                if (botId > 0) {
                    Character bot = DefaultBotServerAccess.INSTANCE.getCharacterById(botId);
                    if (bot != null) {
                        return bot;
                    }
                    for (int poll = 1; poll <= CREATE_BOT_POLL_MAX; poll++) {
                        blockingSleep(CREATE_BOT_POLL_INTERVAL_MS);
                        bot = DefaultBotServerAccess.INSTANCE.getCharacterById(botId);
                        if (bot != null) {
                            return bot;
                        }
                    }
                    debugprint(fmt("createBotWithRetry: botId {} not visible after {} polls, will recreate",
                            botId, CREATE_BOT_POLL_MAX));
                    botId = 0;
                }
                if (attempt < maxRetries) {
                    blockingSleep(200L * attempt);
                }
            } catch (Exception e) {
                debugprint(fmt("createBotWithRetry attempt {}/{} failed at {}: {}", attempt, maxRetries, spawn, e.getMessage()));
                botId = 0;
                if (attempt < maxRetries) {
                    blockingSleep(200L * attempt);
                }
            }
        }
        return null;
    }

    private static MapleMap getMapleMapById(int mapId) {
        return DefaultBotServerAccess.INSTANCE.getMap(
                DefaultBotServerAccess.resolveBotWorld(),
                DefaultBotServerAccess.resolveBotChannel(), mapId);
    }

    private static Point spawnPortal(MapleMap map) {
        if (map == null) {
            return null;
        }
        Portal p = map.getPortal(0);
        if (p == null && !map.getPortals().isEmpty()) {
            p = map.getPortals().iterator().next();
        }
        return p != null ? p.getPosition() : null;
    }

    private static List<Character> getAllCharsOnMap(int mapId) {
        MapleMap map = getMapleMapById(mapId);
        return map == null ? List.of() : new ArrayList<>(map.getAllPlayers());
    }

    private static void botFaceTowardsPoint(Character chr, Point target) {
        if (chr == null || target == null) {
            return;
        }
        boolean left = target.x < chr.getPosition().x;
        if (GCMovement.isEnabled(chr)) {
            GCMovement.face(chr, left);
        } else {
            chr.broadcastStance(left ? 1 : 0);
        }
    }

    private static void blockingSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void debugprint(Object... args) {
        StringBuilder sb = new StringBuilder("[EnvironmentManager]");
        for (Object a : args) {
            sb.append(' ').append(a);
        }
        log.debug("{}", sb);
    }

    private static String fmt(String template, Object... args) {
        String s = template;
        for (Object a : args) {
            // replacement 中的 $ 和 \ 会被 replaceFirst 当正则组引用解析，异常消息等任意文本
            // 一旦含 $ 就抛 Illegal group reference 并吞掉原始异常——必须 quoteReplacement。
            s = s.replaceFirst("\\{\\}", Matcher.quoteReplacement(a == null ? "null" : a.toString()));
        }
        return s;
    }
}
