package org.gms.client.command.commands.gm4;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.constants.id.NpcId;
import org.gms.server.bot.BotDebugHandler;
import org.gms.server.bot.BotExecutors;
import org.gms.server.bot.BotGeneration;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotSpotClaims;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.BotTickService;
import org.gms.server.bot.DefaultBotServerAccess;
import org.gms.server.bot.commands.SocialCommands;
import org.gms.server.bot.dialogue.BotChatter;
import org.gms.server.bot.dialogue.ConversationManager;
import org.gms.server.bot.dialogue.TownChatterLines;
import org.gms.server.bot.environment.EnvironmentManager;
import org.gms.server.bot.freemarket.ArtificialFreeMarket;
import org.gms.server.bot.freemarket.FMShopInfoManager;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.gcmove.LodCounts;
import org.gms.server.bot.grind.MapGrindProfile;
import org.gms.server.bot.grind.Spot;
import org.gms.server.bot.grind.SpotFinder;
import org.gms.server.bot.grind.SpotStack;
import org.gms.server.bot.grind.TrainingMapChooser;
import org.gms.server.bot.social.SocialHotPotatoManager;
import org.gms.server.bot.town.TownPinsStore;
import org.gms.server.bot.town.TownPresenceConfig;
import org.gms.server.bot.town.TownPresenceSampler;
import org.gms.server.bot.types.TrainingBot;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GM4 命令 {@code !env}：手动触发环境生成与各 spawn 子命令（对应 SoloMapling
 * EnvironmentCommand 的移植）。长任务（loadenv / spawn / scatter 等）走
 * {@link BotExecutors#runAsync}；诊断类子命令同步执行。
 */
public class EnvironmentCommand extends Command {
    private static final Logger log = LoggerFactory.getLogger(EnvironmentCommand.class);

    // 审计修正（P4）：fmshop/fmshoproom 幂等标记——记录已生成过完整摊位的 FM 房间 mapId
    // （fmshop 的 region 在此展开为房间列表），重复调用黄字提示并跳过，防止摊位叠加翻倍。
    // 注意：!env loadenv force 不检查本标记（其叠加风险见 force 分支注释）。
    private static final Set<Integer> FM_SHOP_ROOMS_GENERATED = ConcurrentHashMap.newKeySet();

    {
        setDescription("Environment Commands.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            player.yellowMessage("No Command Parameter Found. Try !env help");
            return;
        }

        // townpresence / chatter 有可变元子参数，先于固定参数分发处理。
        if (params[0].equalsIgnoreCase("townpresence") || params[0].equalsIgnoreCase("tp")) {
            BotExecutors.runAsync(() -> handleTownPresence(params, player));
            return;
        }
        if (params[0].equalsIgnoreCase("chatter")) {
            BotExecutors.runAsync(() -> handleChatter(params, player));
            return;
        }

        if (params.length == 1) {
            BotExecutors.runAsync(() -> handleSingleInputCommand(params[0], player));
            return;
        }
        if (params.length == 2) {
            BotExecutors.runAsync(() -> {
                if (isInteger(params[1])) {
                    handleStringIntCommand(params[0], Integer.parseInt(params[1]), player);
                } else {
                    handleStringStringCommand(params[0], params[1], player);
                }
            });
            return;
        }
        if (params.length == 3) {
            BotExecutors.runAsync(() -> {
                if (isInteger(params[1]) && isInteger(params[2])) {
                    handleStringIntIntCommand(params[0], Integer.parseInt(params[1]), Integer.parseInt(params[2]), player);
                } else if (isInteger(params[1])) {
                    handleStringIntStringCommand(params[0], Integer.parseInt(params[1]), params[2], player);
                } else {
                    player.yellowMessage("Second input not an integer");
                }
            });
        }
    }

    private static void handleTownPresence(String[] params, Character p) {
        String action = params.length >= 2 ? params[1].toLowerCase() : "help";
        switch (action) {
            case "reload" -> {
                var towns = TownPresenceConfig.reload();
                ConversationManager.getInstance().refreshMapScope();
                SocialHotPotatoManager.getInstance().refreshMapScope();
                int total = 0;
                for (var t : towns) {
                    for (var m : t.maps()) {
                        total += m.count();
                    }
                }
                p.dropMessage(6, "TownPresence: reloaded " + towns.size() + " towns, " + total + " bots planned.");
            }
            case "spawn" -> {
                if (params.length >= 4) {
                    int mapId = tpInt(params[2], -1);
                    int count = tpInt(params[3], 0);
                    int lo = params.length >= 5 ? tpInt(params[4], 20) : 20;
                    int hi = params.length >= 6 ? tpInt(params[5], lo) : Math.max(lo, 60);
                    int n = EnvironmentManager.spawnSocialCohort(mapId, count, lo, hi);
                    p.dropMessage(6, "TownPresence: spawned " + n + " social bots on map " + mapId
                            + " (lv " + lo + ".." + hi + ").");
                } else if (params.length >= 3 && params[2].equalsIgnoreCase("all")) {
                    p.dropMessage(6, "TownPresence: spawning ALL towns from YAML (stacks on top of any present)...");
                    EnvironmentManager.spawnTownPresence();
                    p.dropMessage(6, "TownPresence: done (per-town counts in console).");
                } else {
                    p.dropMessage(6, "TownPresence: 'spawn <map> <n> [lo hi]' for one map, or 'spawn all' for every town.");
                    p.dropMessage(6, "Note: spawns STACK (no auto-clear); wave 9 already populated towns at startup.");
                }
            }
            case "here" -> {
                int count = params.length >= 3 ? tpInt(params[2], 10) : 10;
                int n = EnvironmentManager.spawnSocialCohort(p.getMapId(), count, 20, 70);
                p.dropMessage(6, "TownPresence: spawned " + n + " social bots on your map " + p.getMapId() + ".");
            }
            case "wander" -> {
                if (params.length >= 4) {
                    int mapId = tpInt(params[2], -1);
                    int count = tpInt(params[3], 0);
                    int lo = params.length >= 5 ? tpInt(params[4], 20) : 20;
                    int hi = params.length >= 6 ? tpInt(params[5], lo) : Math.max(lo, 70);
                    int n = EnvironmentManager.spawnTownWanderers(mapId, count, lo, hi);
                    p.dropMessage(6, "TownPresence: spawned " + n + " wanderers on map " + mapId + ".");
                } else {
                    int count = params.length >= 3 ? tpInt(params[2], 5) : 5;
                    int n = EnvironmentManager.spawnTownWanderers(p.getMapId(), count, 20, 70);
                    p.dropMessage(6, "TownPresence: spawned " + n + " wanderers on your map " + p.getMapId() + ".");
                }
            }
            case "weights" -> {
                int topN = params.length >= 3 ? tpInt(params[2], 12) : 12;
                MapleMap map = p.getMap();
                Portal sp = map.getPortal(0);
                Point anchor = sp != null ? sp.getPosition() : p.getPosition();
                String dump = TownPresenceSampler.describe(map, anchor, topN,
                        TownPresenceConfig.overridesFor(map.getId()));
                for (String line : dump.split("\\r?\\n")) {
                    if (!line.isBlank()) {
                        p.dropMessage(6, line);
                    }
                }
                System.out.println(dump);
            }
            case "mark" -> {
                int mapId = params.length >= 3 ? tpInt(params[2], p.getMapId()) : p.getMapId();
                Point pos = p.getPosition();
                TownPinsStore.addPin(mapId, pos.x, pos.y);
                TownPresenceConfig.reload();
                p.dropMessage(6, "TownPresence: pinned (" + pos.x + "," + pos.y + ") on map " + mapId
                        + " -> TownPins.txt. Spawn there to see it placed.");
            }
            default -> p.dropMessage(6,
                    "!env townpresence reload | spawn [map n lo hi] | here [n] | wander [map n lo hi] | weights [topN] | mark [mapId]");
        }
    }

    private static int tpInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void handleChatter(String[] params, Character p) {
        String action = params.length >= 2 ? params[1].toLowerCase() : "readout";
        switch (action) {
            case "reload" -> {
                int n = TownChatterLines.reload().size();
                p.dropMessage(6, "chatter: reloaded TownChatterDialogue.yaml - " + n + " exchanges");
            }
            case "fire" -> {
                MapleMap map = p.getMap();
                int started = 0;
                for (Character chr : map.getAllPlayers()) {
                    if (chr == null || !BotHelpers.isBot(chr)) {
                        continue;
                    }
                    BotSM bot = BotStorage.getBotById(chr.getId());
                    if (bot != null && BotChatter.forceChatter(bot)) {
                        started++;
                    }
                }
                p.dropMessage(6, "chatter: forced " + started + " exchange(s) on this map "
                        + "(needs adjacent, available participant pairs within " + BotChatter.CHATTER_RADIUS + "px)");
            }
            default -> {
                p.dropMessage(6, String.format("chatter: chance=%.2f radius=%dpx maxMs=%d cooldown=%d..%dms",
                        BotChatter.CHATTER_CHANCE, BotChatter.CHATTER_RADIUS, BotChatter.MAX_CHATTER_MS,
                        BotChatter.CHATTER_COOLDOWN_MIN_MS, BotChatter.CHATTER_COOLDOWN_MAX_MS));
                p.dropMessage(6, "chatter: " + TownChatterLines.exchanges().size() + " exchanges loaded, "
                        + BotChatter.engagedCount() + " bot(s) mid-chat now");
                p.dropMessage(6, "chatter: reload | fire (force chats on this map)");
            }
        }
    }

    private static void handleSingleInputCommand(String input, Character p) {
        switch (input.toLowerCase()) {
            case "help" -> printHelp(p);
            case "loadenv" -> {
                BotExecutors.runAsync(() -> {
                    try {
                        boolean started = EnvironmentManager.environmentLoadStartup();
                        if (started) {
                            p.yellowMessage("!env loadenv - 环境生成已启动（后台执行，日志可见进度）");
                        } else {
                            p.yellowMessage("!env loadenv - 环境已加载，本次跳过（累计已生成 " + BotGeneration.getBotsCreatedCount()
                                    + " 个 bot；如需强制重跑用 !env loadenv force）");
                        }
                    } catch (Throwable t) {
                        log.warn("[EnvironmentCommand] loadenv 后台生成失败", t);
                    }
                });
            }
            case "spawnfmbots" -> EnvironmentManager.spawnBotsInFMEntrance();
            case "spawnmerchantbots", "spawnmerchbots" -> EnvironmentManager.spawnMerchBotsInFMEntrance();
            case "spawnhenesysbots" -> EnvironmentManager.spawnHenesysBots();
            case "spawngachabots" -> EnvironmentManager.spawnGachaBotsHenesys();
            case "getmap" -> p.yellowMessage("Current Map: " + p.getMapId());
            case "getportal" -> {
                Portal portal = p.getMap().findClosestPortal(p.getPosition());
                if (portal != null) {
                    p.dropMessage(6, "Closest portal: id: " + portal.getId() + " name: '" + portal.getName()
                            + "' Type: " + portal.getType() + " --> toMap: " + portal.getTargetMapId()
                            + "' state: " + (portal.getPortalState() ? 1 : 0) + ", Pos: " + portal.getPosition());
                } else {
                    p.dropMessage(6, "There is no portal on this map.");
                }
            }
            case "getplat", "getplatform", "getcurrentplat", "getcurrentplatform" ->
                    p.dropMessage(6, "getplatform: platform CSV system not ported in gms (TODO).");
            case "getallplatforms" ->
                    p.dropMessage(6, "getallplatforms: platform CSV system not ported in gms (TODO).");
            case "getallmainplatforms" ->
                    p.dropMessage(6, "getallmainplatforms: platform CSV system not ported in gms (TODO).");
            case "spawnhenefillers" -> {
                p.yellowMessage("Spawning Henesys filler bots...");
                EnvironmentManager.spawnFillerBotsHenesys();
                p.yellowMessage("Henesys filler bots done.");
            }
            case "spawnmarketfillers" -> {
                p.yellowMessage("Spawning Henesys Market filler bots...");
                EnvironmentManager.spawnFillerBotsHenesysMarket();
                p.yellowMessage("Henesys Market filler bots done.");
            }
            case "spawnparkfillers" -> {
                p.yellowMessage("Spawning Henesys Park filler bots...");
                EnvironmentManager.spawnFillerBotsHenesysPark();
                p.yellowMessage("Henesys Park filler bots done.");
            }
            case "spawngamezonefillers" -> {
                p.yellowMessage("Spawning Game Zone filler bots...");
                EnvironmentManager.spawnFillerBotsGameZone();
                p.yellowMessage("Game Zone filler bots done.");
            }
            case "spawnpotshopfillers" -> {
                p.yellowMessage("Spawning Potion Shop filler bots...");
                EnvironmentManager.spawnFillerBotsPotionShop();
                p.yellowMessage("Potion Shop filler bots done.");
            }
            case "spawnallfillers" -> {
                p.yellowMessage("Spawning all filler bots...");
                EnvironmentManager.spawnFillerBotsHenesys();
                EnvironmentManager.spawnFillerBotsHenesysMarket();
                EnvironmentManager.spawnFillerBotsHenesysPark();
                EnvironmentManager.spawnFillerBotsGameZone();
                EnvironmentManager.spawnFillerBotsPotionShop();
                p.yellowMessage("All filler bots done.");
            }
            case "convertscrollbots" -> {
                p.yellowMessage("Converting random fillers to Scroll Bots...");
                EnvironmentManager.convertRandomFillersToScrollBots();
                p.yellowMessage("Scroll Bot conversion done.");
            }
            case "spawnopqbots" -> {
                p.yellowMessage("Spawning OPQ bots in lobby...");
                EnvironmentManager.spawnOPQBotsInLobby();
                p.yellowMessage("OPQ lobby bots done.");
            }
            case "spawngzhbots" -> {
                p.yellowMessage("Spawning Game Zone Host Bots...");
                EnvironmentManager.spawnGameZoneHostBots();
                p.yellowMessage("Game Zone Host Bots done.");
            }
            case "spawnbjtables" -> {
                p.yellowMessage("Spawning Blackjack Tables...");
                EnvironmentManager.spawnBlackjackTables();
                p.yellowMessage("Blackjack Tables done.");
            }
            case "attacktest" -> {
                p.yellowMessage("Spawning tier-1 attack test bots on Henesys Hunting Ground 1 (use !env attacktest 2/3/4 for other tiers)...");
                EnvironmentManager.spawnAttackTestBots(1);
                p.yellowMessage("Attack test bots spawning. Stand on the map so mobs move into reach.");
            }
            case "scattertest", "trainscatter" -> spawnScatterTest(p, 30);
            case "starthotpotato" -> {
                SocialHotPotatoManager.getInstance().start();
                p.yellowMessage("Social Hot Potato started.");
            }
            case "stophotpotato" -> {
                SocialHotPotatoManager.getInstance().stop();
                p.yellowMessage("Social Hot Potato stopped.");
            }
            case "startconvo" -> {
                ConversationManager.getInstance().start();
                p.yellowMessage("Conversation Manager started.");
            }
            case "stopconvo" -> {
                ConversationManager.getInstance().stop();
                p.yellowMessage("Conversation Manager stopped.");
            }
            case "spawncasinonpc" -> EnvironmentManager.spawnNpcAtPlayer(p, EnvironmentManager.CASINO_NPC_ID);
            case "spawnrpsnpc" -> EnvironmentManager.spawnNpcAtPlayer(p, NpcId.RPS_ADMIN);
            case "spawncasinonpcs" -> {
                EnvironmentManager.spawnCasinoNpcs();
                p.yellowMessage("Casino NPCs spawned on map 100000203.");
            }
            case "grindprofile", "spotdump" -> dumpGrindProfile(p);
            case "perf" -> printPerfReport(p);
            case "navstatus" -> printNavStatus(p);
            case "status" -> printStatus(p);
            case "filelog" -> {
                boolean next = !BotDebugHandler.isFileLoggingEnabled();
                BotDebugHandler.setFileLoggingEnabled(next);
                p.yellowMessage("botlog.txt file logging: " + (next ? "ON" : "OFF")
                        + "（默认关闭以省磁盘 I/O；仅调试排障时开启）");
            }
            default -> p.yellowMessage("Invalid command - Direct Command");
        }
    }

    private static void handleStringIntCommand(String input, int input2, Character p) {
        switch (input.toLowerCase()) {
            case "attacktest" -> {
                p.yellowMessage("Spawning tier-" + input2 + " attack test bots on Henesys Hunting Ground 1...");
                EnvironmentManager.spawnAttackTestBots(input2);
                p.yellowMessage("Attack test bots spawning. Stand on the map so mobs move into reach.");
            }
            case "scattertest", "trainscatter" -> spawnScatterTest(p, input2);
            case "fillerbot" -> {
                Point p1 = new Point(-548, 154);
                Point p2 = new Point(568, 154);
                int mapId = p.getMapId();
                p.yellowMessage("Spawning " + input2 + " filler bots on map " + mapId);
                List<Integer> fillerIds = EnvironmentManager.spawnFillerBots(input2, mapId, p1, p2);
                EnvironmentManager.setAndStartBots(fillerIds, org.gms.server.bot.BotTypeManager.BotType.SOCIAL_BOT);
                p.yellowMessage("Filler bot spawn complete. " + fillerIds.size() + " SocialBots assigned.");
            }
            case "fillerboty" -> {
                Point p1a = new Point(-112, -127);
                Point p2a = new Point(245, -127);
                int mapIdy = p.getMapId();
                p.yellowMessage("Spawning " + input2 + " filler bots on map " + mapIdy);
                List<Integer> fillerIdsY = EnvironmentManager.spawnFillerBotsLockedY(input2, mapIdy, p1a, p2a);
                EnvironmentManager.setAndStartBots(fillerIdsY, org.gms.server.bot.BotTypeManager.BotType.SOCIAL_BOT);
                p.yellowMessage("Filler bot spawn complete. " + fillerIdsY.size() + " SocialBots assigned.");
            }
            case "fmshoproom" -> {
                // 审计修正（LOW-2）：先校验 mapId 是否属于 FM 房间（910000001-910000022），
                // 非法时黄字提示合法范围，避免向任意地图生成摊位。
                String roomRegion = FMShopInfoManager.getRegionByMapId(input2);
                if (roomRegion == null || roomRegion.equals("unknown")) {
                    p.yellowMessage("!env fmshoproom - 非法 mapId '" + input2 + "'（FM 房间合法范围：910000001-910000022）");
                } else if (!FM_SHOP_ROOMS_GENERATED.add(input2)) {
                    // 审计修正（P4）：per-mapId 一次性标记，重复调用跳过，防摊位叠加。
                    p.yellowMessage("!env fmshoproom - 房间 " + input2 + " 已生成过摊位（如确需叠加请使用 !env loadenv force 重跑环境）");
                } else {
                    p.yellowMessage("!env fmshoproom " + input2 + " - 房间完整摊位生成已启动（后台异步，日志可见进度）");
                    BotExecutors.runAsync(() -> {
                        try {
                            ArtificialFreeMarket.populateFreeMarketRoom(input2);
                        } catch (Throwable t) {
                            log.warn("[EnvironmentCommand] fmshoproom 摊位生成失败 mapId={}", input2, t);
                        }
                    });
                }
            }
            default -> {
            }
        }
    }

    private static void handleStringStringCommand(String input, String input2, Character p) {
        switch (input.toLowerCase()) {
            case "getallcharsonplatform", "getcharsonplatform" -> {
                p.yellowMessage("getcharsonplatform: platform CSV system not ported; listing all bots on your map instead.");
                listBotsOnMap(p);
            }
            case "loadenv" -> {
                if (input2.equalsIgnoreCase("force")) {
                    p.yellowMessage("!env loadenv force - 强制重跑环境生成（后台执行，日志可见进度）");
                    // 审计修正（m1）：force 与普通 loadenv 一样后台执行——9 波生成耗时数十秒，
                    // 不能在 GM 命令线程上同步阻塞。
                    // 审计修正（P4）：force 强制重跑完整环境生成，不检查 FM_SHOP_ROOMS_GENERATED
                    // 幂等标记——进程内已有摊位不会被清理，重跑会叠加（仅限确需叠加时使用）。
                    BotExecutors.runAsync(() -> {
                        try {
                            EnvironmentManager.forceEnvironmentLoad();
                        } catch (Throwable t) {
                            log.warn("[EnvironmentCommand] loadenv force 后台生成失败", t);
                        }
                    });
                } else {
                    p.yellowMessage("!env loadenv - 未知参数 '" + input2 + "'（仅支持 force）");
                }
            }
            case "fmshop" -> {
                String region = input2.toLowerCase();
                if (!List.of("henesys", "ludi", "perion", "elnath").contains(region)) {
                    p.yellowMessage("!env fmshop - 非法 region '" + input2 + "'（合法值：henesys/ludi/perion/elnath）");
                } else {
                    // 审计修正（P4）：region 展开为房间列表做 per-mapId 幂等检查，仅对未生成过的
                    // 房间调用生成，防止重复调用（或先 fmshoproom 单房间再 fmshop 整区）摊位叠加。
                    List<Integer> roomMapIds = ArtificialFreeMarket.fmInfo.getRegionFMMapId(region);
                    List<Integer> freshRooms = new ArrayList<>();
                    for (int roomMapId : roomMapIds) {
                        if (FM_SHOP_ROOMS_GENERATED.add(roomMapId)) {
                            freshRooms.add(roomMapId);
                        }
                    }
                    if (freshRooms.isEmpty()) {
                        p.yellowMessage("!env fmshop " + region + " - 该区域已生成过摊位（如确需叠加请使用 !env loadenv force 重跑环境）");
                    } else {
                        p.yellowMessage("!env fmshop " + region + " - 完整摊位生成已启动（后台异步，日志可见进度；"
                                + freshRooms.size() + "/" + roomMapIds.size() + " 个房间待生成）");
                        BotExecutors.runAsync(() -> {
                            try {
                                for (int roomMapId : freshRooms) {
                                    ArtificialFreeMarket.populateFreeMarketRoom(roomMapId);
                                }
                            } catch (Throwable t) {
                                log.warn("[EnvironmentCommand] fmshop 摊位生成失败 region={}", region, t);
                            }
                        });
                    }
                }
            }
            default -> p.yellowMessage("Invalid command - handleStringStringCommand");
        }
    }

    private static void handleStringIntIntCommand(String input, int input2, int input3, Character p) {
        // 源实现为空壳（仅取 bot 判空）；gms 保留占位。
        p.yellowMessage("Invalid command - handleStringIntIntCommand");
    }

    private static void handleStringIntStringCommand(String input, int input2, String str, Character p) {
        Character bot = DefaultBotServerAccess.INSTANCE.getCharacterById(input2);
        if (bot == null) {
            p.yellowMessage("Bot null");
            return;
        }
        switch (input.toLowerCase()) {
            case "chat" -> SocialCommands.BotSpeak(bot, str);
            case "platformshuffle" -> moveBotToRandomSpot(bot);
            case "platformshufflerandom" -> moveBotToRandomSpot(bot);
            default -> p.yellowMessage("Invalid command Two Object");
        }
    }

    private static void moveBotToRandomSpot(Character bot) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        List<Point> spots = BotHelpers.pickGroundSpots(bot.getMap(), bot.getPosition(), 3);
        if (spots.isEmpty()) {
            return;
        }
        Point target = spots.get(new Random().nextInt(spots.size()));
        if (GCMovement.isEnabled(bot)) {
            GCMovement.move(bot, target.x, target.y);
        } else {
            bot.setPosition(target);
            bot.broadcastStance();
        }
    }

    private static void listBotsOnMap(Character p) {
        MapleMap map = p.getMap();
        if (map == null) {
            return;
        }
        for (Character chr : map.getAllPlayers()) {
            if (BotHelpers.isBot(chr)) {
                p.yellowMessage("bot " + chr.getId() + " " + chr.getName() + " at " + chr.getPosition());
            }
        }
    }

    private static void printPerfReport(Character p) {
        p.yellowMessage(String.format("bots: %d active   dynamic movement: %d   grinders: %d",
                BotStorage.getActiveBotCount(), LodCounts.dynamicBots(), TrainingBot.activeGrinderCount()));
        p.yellowMessage(String.format("observed maps: full=%d halo=%d active=%d",
                LodCounts.fullMaps(), LodCounts.haloMaps(), LodCounts.activeMaps()));
    }

    private static void printStatus(Character p) {
        p.yellowMessage("---- !env status ----");
        p.yellowMessage("environment loaded: " + EnvironmentManager.isEnvironmentLoaded());
        p.yellowMessage("bots created (cumulative): " + BotGeneration.getBotsCreatedCount());
        p.yellowMessage("active bots (BotStorage): " + BotStorage.getAllBots().size());
        p.yellowMessage("tick service size: " + BotTickService.size());
        p.yellowMessage("movement engine states: " + GCMovement.enabledCount());
        int cores = Runtime.getRuntime().availableProcessors();
        p.yellowMessage(String.format("cpu cores: %d   env scale: %.2f", cores, EnvironmentManager.environmentScale()));
    }

    private static void printNavStatus(Character p) {
        // 世界图（可步行传送门连通图）就绪状态与已索引地图数；不触发构建。
        p.yellowMessage(String.format("world graph: ready=%s maps=%d",
                GCMovement.worldGraphReady(), GCMovement.worldGraphMapCount()));

        // 当前 GM 所在图的导航图报告。getLastBuildReport / peekBestGraph 均为包私有，
        // 这里改用公开的 GCMovement.bakeReport(bot)（按图内任一 bot 的移动画像强制重建并返回摘要）。
        MapleMap map = p.getMap();
        if (map == null) {
            p.yellowMessage("nav graph: no map (player map null).");
        } else {
            Character bot = firstBotOnMap(map);
            if (bot == null) {
                p.yellowMessage("nav graph map " + map.getId() + ": 图内无 bot，无法取报告");
            } else {
                p.yellowMessage("nav graph map " + map.getId() + ": " + GCMovement.bakeReport(bot));
            }
        }

        // 移动引擎与观察追踪器计数（经 LodCounts 公开桥接读取包私有状态）。
        p.yellowMessage(String.format("movement engine: dynamic bots=%d  observer started=%s full=%d halo=%d active=%d",
                LodCounts.dynamicBots(), LodCounts.trackerRunning(),
                LodCounts.fullMaps(), LodCounts.haloMaps(), LodCounts.activeMaps()));

        p.yellowMessage("active bots: " + BotStorage.getActiveBotCount());
    }

    private static Character firstBotOnMap(MapleMap map) {
        if (map == null) {
            return null;
        }
        for (Character chr : map.getAllPlayers()) {
            if (chr != null && BotHelpers.isBot(chr)) {
                return chr;
            }
        }
        return null;
    }

    private static void printHelp(Character p) {
        p.yellowMessage("---- Environment Commands (!env) ----");
        p.yellowMessage("-- Diagnostics --");
        p.yellowMessage("!env perf                        - bot perf: counts, LOD, tiers");
        p.yellowMessage("!env navstatus                   - world/nav graph readiness + movement/observer/bot counts");
        p.yellowMessage("!env status                      - env loaded flag, bot counts, tick/movement sizes, cores & scale");
        p.yellowMessage("!env filelog                     - toggle botlog.txt file logging (off by default)");
        p.yellowMessage("-- World Startup --");
        p.yellowMessage("!env loadenv                     - run full environment startup (skipped if already loaded)");
        p.yellowMessage("!env loadenv force               - force full environment startup, ignoring the loaded guard");
        p.yellowMessage("-- FM Spawning --");
        p.yellowMessage("!env spawnfmbots                 - spawn FM entrance bots");
        p.yellowMessage("!env spawnmerchbots              - spawn merchant bots in FM entrance");
        p.yellowMessage("!env fmshop <region>             - 完整摊位生成管线（henesys/ludi/perion/elnath，后台异步）");
        p.yellowMessage("!env fmshoproom <mapId>          - 指定 FM 房间完整摊位生成（后台异步）");
        p.yellowMessage("-- Henesys Spawning --");
        p.yellowMessage("!env spawnhenesysbots            - spawn Henesys wanderer bots");
        p.yellowMessage("!env spawngachabots              - spawn gacha bots in Henesys");
        p.yellowMessage("-- Filler Bots --");
        p.yellowMessage("!env spawnhenefillers            - spawn Henesys filler bots");
        p.yellowMessage("!env spawnmarketfillers          - spawn Henesys Market fillers");
        p.yellowMessage("!env spawnparkfillers            - spawn Henesys Park fillers");
        p.yellowMessage("!env spawngamezonefillers        - spawn Game Zone fillers");
        p.yellowMessage("!env spawnpotshopfillers         - spawn Potion Shop fillers");
        p.yellowMessage("!env spawnallfillers             - spawn all filler bots");
        p.yellowMessage("!env fillerbot <count>           - spawn N fillers at fixed X coords");
        p.yellowMessage("!env fillerboty <count>          - spawn N fillers at fixed Y coords");
        p.yellowMessage("!env convertscrollbots           - convert random fillers to scroll bots");
        p.yellowMessage("-- Special Spawns --");
        p.yellowMessage("!env spawnopqbots                - spawn OPQ lobby bots");
        p.yellowMessage("!env spawngzhbots                - spawn Game Zone Host bots");
        p.yellowMessage("!env spawnbjtables               - spawn Blackjack tables");
        p.yellowMessage("!env attacktest                  - spawn per-class attack test bots on Henesys Hunting Ground 1");
        p.yellowMessage("!env scattertest [count]         - scatter N training bots (def 30) on current map ground spots");
        p.yellowMessage("!env spawncasinonpc              - spawn casino NPC at player");
        p.yellowMessage("!env spawncasinonpcs             - spawn all casino NPCs on map");
        p.yellowMessage("!env spawnrpsnpc                 - spawn RPS NPC");
        p.yellowMessage("-- Social Systems --");
        p.yellowMessage("!env starthotpotato              - start Social Hot Potato manager");
        p.yellowMessage("!env stophotpotato               - stop Social Hot Potato manager");
        p.yellowMessage("!env startconvo                  - start Conversation Manager");
        p.yellowMessage("!env stopconvo                   - stop Conversation Manager");
        p.yellowMessage("-- Map Inspection --");
        p.yellowMessage("!env getmap                      - get current map ID");
        p.yellowMessage("!env getportal                   - get closest portal info");
        p.yellowMessage("!env getplatform                 - get current platform (TODO: not ported)");
        p.yellowMessage("!env getallplatforms             - get all available platforms (TODO: not ported)");
        p.yellowMessage("!env getallmainplatforms         - get all main platforms (TODO: not ported)");
        p.yellowMessage("!env getcharsonplatform <platId> - get chars on a platform (TODO: not ported)");
        p.yellowMessage("!env grindprofile                - dump this map's grind spot profile (calibrate spot tuning)");
        p.yellowMessage("-- Town Presence --");
        p.yellowMessage("!env townpresence reload         - re-read TownPresence.yaml (no restart)");
        p.yellowMessage("!env townpresence spawn [map n lo hi] - spawn a town's social cohort (no args = all towns)");
        p.yellowMessage("!env townpresence here [count]   - spawn stationed social bots on your current map (dry-run)");
        p.yellowMessage("!env townpresence wander [map n lo hi] - spawn roaming wanderers (no map = your current map)");
        p.yellowMessage("!env townpresence weights [topN] - dump anchor-weighted ledge weights for this map");
        p.yellowMessage("!env townpresence mark [mapId]   - pin your current spot (stand where you want a bot) -> TownPins.txt");
        p.yellowMessage("-- Bot Chatter --");
        p.yellowMessage("!env chatter                     - readout: knobs, exchanges loaded, bots mid-chat");
        p.yellowMessage("!env chatter reload              - re-read TownChatterDialogue.yaml (no restart)");
        p.yellowMessage("!env chatter fire                - force eligible bot pairs on this map to chat now");
        p.yellowMessage("-- Bot Control --");
        p.yellowMessage("!env chat <cid> <message>        - bot speaks in chat");
        p.yellowMessage("!env platformshuffle <cid> <id>  - move bot to a random ground spot (platform CSV not ported)");
        p.yellowMessage("!env platformshufflerandom <cid> - move bot to a random ground spot");
    }

    private static void spawnScatterTest(Character p, int count) {
        MapleMap map = p.getMap();
        Point from = p.getPosition();
        p.yellowMessage("Scattering " + count + " training bots across map " + p.getMapId()
                + " (anchored at your spot)...");
        List<Integer> ids = EnvironmentManager.spawnScatteredTrainingBots(map, from, count, 10, 55);
        p.yellowMessage("Scatter spawn complete. " + ids.size() + " training bots placed"
                + (ids.size() < count ? " (some fell back to your spot - nav graph not baked?)" : "") + ".");
    }

    private static void dumpGrindProfile(Character p) {
        MapleMap map = p.getMap();
        MapGrindProfile prof = SpotFinder.profile(map);
        if (prof == null) {
            p.yellowMessage("No grind profile (map null).");
            return;
        }
        p.yellowMessage(String.format("=== grind profile: map %d (%s) ===", prof.mapId(), prof.regime()));
        p.yellowMessage(String.format("walkable span: %d..%d (%dpx)  spawnPoints: %d  density: %.5f spawns/px (%.2f / screen)",
                prof.walkableMinX(), prof.walkableMaxX(), prof.walkableSpanX(), prof.spawnPointCount(),
                prof.spawnDensity(), prof.spawnDensity() * 1000.0));
        p.yellowMessage(String.format("clusters/spots: %d  meanInterSpotGap: %dpx",
                prof.clusterCount(), prof.meanInterSpotGapX()));
        int bestSameLedge = 0;
        for (Spot s : prof.spots()) {
            bestSameLedge = Math.max(bestSameLedge, s.sameLedgeSpawnCount());
        }
        p.yellowMessage(String.format("mode: %s  (best sameLedge spawns %d — roam when below the campable floor)",
                prof.roam() ? "ROAM (no campable ledge)" : "CAMP", bestSameLedge));
        if (!prof.stacks().isEmpty()) {
            int bestFeed = 0;
            int hop = 0;
            for (SpotStack st : prof.stacks()) {
                bestFeed = Math.max(bestFeed, st.totalFeed());
                if (st.hopTraversable()) {
                    hop++;
                }
            }
            p.yellowMessage(String.format("stacks: %d (best feed %d, hop-traversable %d) — STACK archetype candidates",
                    prof.stacks().size(), bestFeed, hop));
        }
        p.yellowMessage(String.format("occupancy: %d bots targeting this map / capacity %d",
                TrainingMapChooser.botsTargeting(prof.mapId()), TrainingMapChooser.mapCapacity(prof.mapId())));
        if (prof.spots().isEmpty()) {
            p.yellowMessage("  (no spots — nav graph unbaked or map has no spawn points)");
            return;
        }
        int totalHolders = 0;
        for (int i = 0; i < prof.spots().size(); i++) {
            Spot s = prof.spots().get(i);
            int live = SpotFinder.liveHostilesWithin(map, s);
            int holders = BotSpotClaims.holders(prof.mapId(), i);
            totalHolders += holders;
            p.yellowMessage(String.format("  [%d] anchor (%d,%d) region %d  radius %d  spawnPts %d (sameLedge %d)  ledgeSpan %dpx  liveMobs %d  holders %d/%d",
                    i, s.anchor().x, s.anchor().y, s.regionId(), s.radius(), s.spawnCount(),
                    s.sameLedgeSpawnCount(), s.ledgeSpanPx(), live, holders, s.shareCap()));
        }
        p.yellowMessage(String.format("claim total: %d holders across %d spots", totalHolders, prof.spots().size()));
    }

    private static boolean isInteger(String s) {
        try {
            Integer.parseInt(s.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
