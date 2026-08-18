package org.gms.client.command.commands.gm4;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.server.bot.BotGeneration;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.BotTypeManager;
import org.gms.server.bot.DefaultBotServerAccess;
import org.gms.server.bot.environment.platform.PlatformPlacement;
import org.gms.server.bot.types.opq.OPQBot;
import org.gms.server.bot.types.opq.OPQBot.OPQBotState;
import org.gms.server.bot.types.opq.OPQConstants;
import org.gms.server.bot.types.opq.OPQOrchestrator;
import org.gms.server.bot.types.opq.OPQSharedContext;
import org.gms.server.bot.types.opq.OPQSharedContext.OPQPhase;
import org.gms.server.maps.MapleMap;
import org.gms.util.I18nUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GM4 命令 !opq：OPQ bot 开发命令，逐段测试 OPQ bot 状态机。
 * 逐子命令对照 SoloMapling OPQCommands 移植（20 个子命令，含 help 分发）：
 * <p>
 * help / status / start / reset / list / stage1done / stage2done / killall /
 * spawn &lt;n&gt; / dump &lt;cid&gt; / complete &lt;cid&gt; / kill &lt;cid&gt; /
 * phase &lt;PHASE&gt; / warp &lt;lobby|s1|tower|s2|exit&gt; / forcestateall &lt;STATE&gt; /
 * assign &lt;cid&gt; &lt;cloud|box&gt; / forcestate &lt;cid&gt; &lt;STATE&gt; / move &lt;cid&gt; &lt;platformId&gt;
 * <p>
 * 与源实现的差异：源把每个分支包进 ExecutorService 异步执行，gms 侧沿用本包
 * gm4 命令惯例同步分发（后端均为内存级快操作，spawn 走 BotGeneration 内部异步编排）。
 */
@Slf4j
public class OPQCommands extends Command {
    {
        setDescription("OPQ bot dev commands. Try: !opq help");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.noparam"));
            return;
        }
        if (params.length == 1) {
            handleDirectCommand(params[0], c);
            return;
        }
        if (params.length == 2) {
            if (isInteger(params[1])) {
                handleNumberedCommand(params[0], Integer.parseInt(params[1]), c);
            } else if (!isInteger(params[0])) {
                handleTwoStringCommand(params[0], params[1], c);
            } else {
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.notInt"));
            }
            return;
        }
        if (params.length == 3) {
            if (isInteger(params[1])) {
                handleStringIntStringCommand(params[0], Integer.parseInt(params[1]), params[2], c);
            } else {
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.notInt"));
            }
        }
    }

    // =========================================================================
    // 1-arg
    // =========================================================================

    /** 单参数分发（help/status/start/reset/list/stage1done/stage2done/killall）。 */
    void handleDirectCommand(String input, Client c) {
        Character player = c.getPlayer();
        switch (input.toLowerCase()) {
            case "help":
                printHelp(player);
                break;
            case "status":
                printStatus(player);
                break;
            case "start":
                OPQOrchestrator.getInstance().resetForNewRun();
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.start"));
                break;
            case "reset":
                OPQOrchestrator.getInstance().shutdownRun();
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.reset"));
                break;
            case "list":
                printList(player);
                break;
            case "stage1done":
                markAllTaskComplete();
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.stage1done"));
                break;
            case "stage2done":
                markAllTaskComplete();
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.stage2done"));
                break;
            case "killall": {
                int killed = killAllOPQBots();
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.killed", killed));
                break;
            }
            default:
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.invalid"));
                break;
        }
    }

    // =========================================================================
    // 2-arg: string + int
    // =========================================================================

    /** 字符串 + 整数分发（spawn 无需先查 bot，其余按 cid 查 OPQBot）。 */
    void handleNumberedCommand(String input, int input2, Client c) {
        Character player = c.getPlayer();

        // spawn 是无 bot 前置命令，先于 bot 查找处理。
        if (input.equalsIgnoreCase("spawn")) {
            spawnBots(input2, c);
            return;
        }

        Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(input2);
        if (fakechar == null) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.botNull"));
            return;
        }
        OPQBot bot = findOPQBotForChar(fakechar);
        if (bot == null) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.notOpq", fakechar.getName()));
            return;
        }

        switch (input.toLowerCase()) {
            case "dump":
                printDump(bot, player);
                break;
            case "complete":
                OPQOrchestrator.getInstance().getSharedContext().markTaskComplete(bot.getChr().getId());
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.complete", bot.getChr().getName()));
                break;
            case "kill":
                BotTypeManager.manuallyStopBot(bot.getChr());
                OPQOrchestrator.getInstance().unregisterBot(bot);
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.kill", bot.getChr().getName()));
                break;
            default:
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.invalid"));
                break;
        }
    }

    // =========================================================================
    // 2-arg: string + string
    // =========================================================================

    /** 双字符串分发（phase/warp/forcestateall）。 */
    void handleTwoStringCommand(String input, String input2, Client c) {
        Character player = c.getPlayer();
        switch (input.toLowerCase()) {
            case "phase":
                try {
                    OPQPhase p = OPQPhase.valueOf(input2.toUpperCase());
                    OPQOrchestrator.getInstance().mirrorPhase(p);
                    player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.phaseForced", p));
                } catch (IllegalArgumentException e) {
                    player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.unknownPhase", input2));
                }
                break;
            case "warp":
                warpSelf(input2, c);
                break;
            case "forcestateall":
                forceStateAll(input2, player);
                break;
            default:
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.invalid"));
                break;
        }
    }

    // =========================================================================
    // 3-arg: string + int + string
    // =========================================================================

    /** 字符串 + 整数 + 字符串分发（assign/forcestate/move）。 */
    void handleStringIntStringCommand(String input, int input2, String input3, Client c) {
        Character player = c.getPlayer();
        Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(input2);
        if (fakechar == null) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.botNull"));
            return;
        }
        OPQBot bot = findOPQBotForChar(fakechar);
        if (bot == null) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.notOpq", fakechar.getName()));
            return;
        }

        switch (input.toLowerCase()) {
            case "assign":
                assignTarget(bot, input3, player);
                break;
            case "forcestate":
                try {
                    OPQBotState state = OPQBotState.valueOf(input3.toUpperCase());
                    bot.setStateForDebug(state);
                    player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.stateForced", bot.getChr().getName(), state));
                } catch (IllegalArgumentException e) {
                    player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.unknownState", input3));
                }
                break;
            case "move":
                PlatformPlacement.botMoveToPlatformAnyUnoccupiedSpot(bot.getChr(), input3);
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.moved", bot.getChr().getName(), input3));
                break;
            default:
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.invalid"));
                break;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void printHelp(Character player) {
        player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.header"));
        for (int i = 1; i <= 15; i++) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.help" + i));
        }
    }

    private void printStatus(Character player) {
        OPQSharedContext ctx = OPQOrchestrator.getInstance().getSharedContext();
        player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.status",
                ctx.getCurrentPhase(), ctx.isPqActive(), ctx.isStage1Complete(), ctx.isStage2Complete()));
        int count = snapshotOPQBots().size();
        player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.registered", count));
    }

    private void printList(Character player) {
        player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.listHeader"));
        OPQSharedContext ctx = OPQOrchestrator.getInstance().getSharedContext();
        for (OPQBot bot : snapshotOPQBots()) {
            int id = bot.getChr().getId();
            Integer cloud = ctx.getMyCloudAssignment(id);
            String box = ctx.getMyPlatformAssignment(id);
            player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.botLine",
                    bot.getChr().getName(), id, bot.getOPQBotState(), bot.getChr().getMapId(),
                    cloud, box, ctx.isMyTaskComplete(id)));
        }
    }

    private void printDump(OPQBot bot, Character player) {
        Character chr = bot.getChr();
        OPQSharedContext ctx = OPQOrchestrator.getInstance().getSharedContext();
        player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.dumpHeader", chr.getName(), chr.getId()));
        player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.dumpLine1",
                chr.getMapId(), chr.getPosition(), chr.getParty() != null));
        player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.dumpLine2",
                ctx.getMyCloudAssignment(chr.getId()), ctx.getMyPlatformAssignment(chr.getId()),
                ctx.isMyTaskComplete(chr.getId())));
        player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.dumpLine3",
                bot.getOPQBotState(), bot.getState(), PlatformPlacement.getCurrentPlatform(chr)));
    }

    private void spawnBots(int n, Client c) {
        Character self = c.getPlayer();
        // bot 落在 bot 频道下 GM 当前地图的等价实例（GM 与 bot 跨频道地图错位防护）。
        MapleMap map = DefaultBotServerAccess.INSTANCE.getMap(
                DefaultBotServerAccess.resolveBotWorld(),
                DefaultBotServerAccess.resolveBotChannel(), self.getMapId());
        if (map == null) {
            self.yellowMessage(I18nUtil.getMessage("BotCommand.opq.mapNotFound", self.getMapId()));
            return;
        }
        int spawned = 0;
        for (int i = 0; i < n; i++) {
            try {
                // gms 移植：源 createBotPollReadiness 返回 Character；gms createBot 返回 id 再回查。
                int botId = BotGeneration.createBot(self.getPosition(), map);
                Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(botId);
                if (fakechar == null) {
                    continue;
                }
                OPQBot bot = new OPQBot(fakechar);
                BotStorage.addActiveBot(fakechar.getId(), bot);
                BotTypeManager.manuallyStartBot(fakechar);
                spawned++;
            } catch (Exception e) {
                self.yellowMessage(I18nUtil.getMessage("BotCommand.opq.spawnFail", i, e.getMessage()));
                log.warn("OPQ spawn {} 失败", i, e);
            }
        }
        self.yellowMessage(I18nUtil.getMessage("BotCommand.opq.spawned", spawned, n));
    }

    private int killAllOPQBots() {
        OPQOrchestrator orch = OPQOrchestrator.getInstance();
        int killed = 0;
        for (OPQBot bot : snapshotOPQBots()) {
            BotTypeManager.manuallyStopBot(bot.getChr());
            orch.unregisterBot(bot);
            killed++;
        }
        return killed;
    }

    private void markAllTaskComplete() {
        OPQSharedContext ctx = OPQOrchestrator.getInstance().getSharedContext();
        for (OPQBot bot : snapshotOPQBots()) {
            ctx.markTaskComplete(bot.getChr().getId());
        }
    }

    private void warpSelf(String target, Client c) {
        Character self = c.getPlayer();
        int mapId = switch (target.toLowerCase()) {
            case "lobby" -> OPQConstants.OPQ_LOBBY;
            case "s1" -> OPQConstants.OPQ_STAGE_1;
            case "tower" -> OPQConstants.OPQ_TOWER;
            case "s2" -> OPQConstants.OPQ_STAGE_2;
            case "exit" -> OPQConstants.OPQ_EXIT_LOBBY;
            default -> -1;
        };
        if (mapId < 0) {
            self.yellowMessage(I18nUtil.getMessage("BotCommand.opq.warpUnknown", target));
            return;
        }
        MapleMap map = c.getChannelServer().getMapFactory().getMap(mapId);
        if (map == null) {
            self.yellowMessage(I18nUtil.getMessage("BotCommand.opq.mapNotFound", mapId));
            return;
        }
        self.changeMap(map, 0);
        self.yellowMessage(I18nUtil.getMessage("BotCommand.opq.warped", target, mapId));
    }

    private void forceStateAll(String stateName, Character player) {
        OPQBotState state;
        try {
            state = OPQBotState.valueOf(stateName.toUpperCase());
        } catch (IllegalArgumentException e) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.unknownState", stateName));
            return;
        }
        int hit = 0;
        for (OPQBot bot : snapshotOPQBots()) {
            bot.setStateForDebug(state);
            hit++;
        }
        player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.forcedAll", hit, state));
    }

    private void assignTarget(OPQBot bot, String kind, Character player) {
        OPQOrchestrator orch = OPQOrchestrator.getInstance();
        // 先注销再注册清掉既有分配，随后自动挑选新目标。
        orch.unregisterBot(bot);
        orch.registerBot(bot);
        switch (kind.toLowerCase()) {
            case "cloud": {
                Integer pickedReactor = orch.assignCloudReactor(bot);
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.cloudAssigned",
                        bot.getChr().getName(), pickedReactor));
                break;
            }
            case "box": {
                String pickedBox = orch.assignPlatformTarget(bot);
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.boxAssigned",
                        bot.getChr().getName(), pickedBox));
                break;
            }
            default:
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.assignKind"));
                break;
        }
    }

    private List<OPQBot> snapshotOPQBots() {
        List<OPQBot> out = new ArrayList<>();
        for (Map.Entry<Integer, BotSM> e : BotStorage.getAllBots().entrySet()) {
            if (e.getValue() instanceof OPQBot opq) {
                out.add(opq);
            }
        }
        return out;
    }

    private static OPQBot findOPQBotForChar(Character chr) {
        BotSM sm = BotStorage.getAllBots().get(chr.getId());
        return (sm instanceof OPQBot opq) ? opq : null;
    }

    private static boolean isInteger(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
