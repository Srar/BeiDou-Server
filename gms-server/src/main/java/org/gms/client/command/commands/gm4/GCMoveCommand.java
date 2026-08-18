package org.gms.client.command.commands.gm4;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotTiming;
import org.gms.server.bot.DefaultBotServerAccess;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.types.TrainingBot;
import org.gms.util.I18nUtil;

import java.awt.Point;

/**
 * GM4 命令 !gcmove：GreenCat 动态（计算式）移动测试/控制，逐子命令对照
 * SoloMapling GCMoveCommand 移植。bot 目标子命令以显式 bot id（bot 角色 id）定位，
 * 与 ArtificialPlayerCommand 一致。
 * <p>
 * 子命令：bake / ropecheck / lod [stats|load n|unload|train] / move / here / come /
 * follow / travel / route / jump / fj / turn / duck / fidget [off] / stop / off / status。
 */
@Slf4j
public class GCMoveCommand extends Command {
    {
        setDescription("GreenCat dynamic movement (GCMoveSystem) test/control.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0 || params[0].equalsIgnoreCase("help")) {
            help(player);
            return;
        }

        String sub = params[0].toLowerCase();

        // 'bake' 作用于 GM 自己所在的地图，无需 bot。
        if (sub.equals("bake")) {
            player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.bake", player.getMapId()));
            player.dropMessage(GCMovement.bakeReport(player));
            return;
        }

        // 'ropecheck' 用顶端出口探针检查 GM 所在图的绳——无需 bot。
        if (sub.equals("ropecheck")) {
            GCMovement.ropeCheckReport(player).forEach(player::dropMessage);
            return;
        }

        // 'lod' 是 LOD 测量工具（M0）——load/unload/stats，无需指定 bot。
        if (sub.equals("lod")) {
            handleLod(player, params);
            return;
        }

        // 其余子命令都针对具体 bot：!gcmove <sub> <botId> [args]
        if (params.length < 2) {
            player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.usage", sub));
            return;
        }
        Character bot = resolveBot(player, params[1]);
        if (bot == null) {
            return; // resolveBot 已提示失败原因
        }

        switch (sub) {
            case "move" -> {
                if (params.length < 4) {
                    player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.moveUsage"));
                    return;
                }
                try {
                    int x = Integer.parseInt(params[2]);
                    int y = Integer.parseInt(params[3]);
                    GCMovement.move(bot, x, y, () ->
                            player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.arrived", bot.getName(), x, y)));
                    player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.moving", bot.getName(), x, y));
                } catch (NumberFormatException e) {
                    player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.moveUsage"));
                }
            }
            case "here" -> {
                Point p = player.getPosition();
                GCMovement.move(bot, p.x, p.y, () ->
                        player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.reached", bot.getName())));
                player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.movingToYou", bot.getName(), p.x, p.y));
            }
            case "come" -> {
                Point p = player.getPosition();
                int mapId = player.getMapId();
                GCMovement.travelTo(bot, mapId, p.x, p.y, ok -> player.dropMessage(I18nUtil.getMessage(
                        ok ? "BotCommand.gcmove.comeOk" : "BotCommand.gcmove.comeFail", bot.getName())));
                player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.coming", bot.getName(), mapId, p.x, p.y));
            }
            case "follow" -> {
                GCMovement.follow(bot, player);
                player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.following", bot.getName()));
            }
            case "stop" -> {
                GCMovement.stop(bot);
                player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.stopped", bot.getName()));
            }
            case "jump" -> {
                GCMovement.jumpInPlace(bot);
                player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.jumps", bot.getName()));
            }
            case "fj" -> {
                // 校准钩子：以精确 scale 打一发二段跳（绕过 tier 上限 + 平台适配），
                // 并回报实测水平位移——数字回填 GCMovementSkills.FJ_TRAVEL_PX。
                float scale = 1f;
                if (params.length >= 3) {
                    try {
                        scale = Float.parseFloat(params[2]);
                    } catch (NumberFormatException e) {
                        player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.fjBadScale"));
                    }
                }
                Point start = bot.getPosition();
                boolean facingLeft = Boolean.TRUE.equals(GCMovement.isFacingLeft(bot));
                int dir = facingLeft ? -1 : 1;
                if (start == null || !GCMovement.debugFlashJump(bot, dir, scale)) {
                    player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.fjFail", bot.getName()));
                    return;
                }
                final float usedScale = scale;
                player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.fjGo", bot.getName(),
                        usedScale, dir > 0 ? "right" : "left"));
                BotTiming.after(1_600, () -> { // 弧线远小于一秒；等稳定后再测量
                    Point end = bot.getPosition();
                    if (end != null) {
                        player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.fjResult",
                                usedScale, Math.abs(end.x - start.x), end.y - start.y));
                    }
                });
            }
            case "turn" -> {
                GCMovement.turnAround(bot);
                player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.turns", bot.getName()));
            }
            case "duck" -> {
                GCMovement.duck(bot, 1500);
                player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.ducks", bot.getName()));
            }
            case "fidget" -> {
                boolean on = params.length < 3 || !params[2].equalsIgnoreCase("off");
                GCMovement.setFidget(bot, on);
                player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.fidget", bot.getName(),
                        on ? "ON" : "OFF"));
            }
            case "travel" -> {
                if (params.length < 3) {
                    player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.travelUsage"));
                    return;
                }
                try {
                    int mapId = Integer.parseInt(params[2]);
                    GCMovement.travel(bot, mapId, ok -> player.dropMessage(I18nUtil.getMessage(
                            ok ? "BotCommand.gcmove.travelOk" : "BotCommand.gcmove.travelFail",
                            bot.getName(), mapId)));
                    player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.traveling", bot.getName(), mapId));
                } catch (NumberFormatException e) {
                    player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.travelUsage"));
                }
            }
            case "route" -> {
                if (params.length < 3) {
                    player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.routeUsage"));
                    return;
                }
                try {
                    player.dropMessage(GCMovement.routeReport(bot, Integer.parseInt(params[2])));
                } catch (NumberFormatException e) {
                    player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.routeUsage"));
                }
            }
            case "off" -> {
                GCMovement.disable(bot);
                player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.off", bot.getName()));
            }
            case "status" -> player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.status",
                    bot.getName(), GCMovement.isEnabled(bot), GCMovement.isMoving(bot),
                    GCMovement.isTraveling(bot), GCMovement.isFollowing(bot)));
            default -> help(player);
        }
    }

    // !gcmove lod [stats|load <n>|unload|train] — LOD 测量工具（M0）。
    private void handleLod(Character player, String[] params) {
        String op = params.length >= 2 ? params[1].toLowerCase() : "stats";
        switch (op) {
            case "load" -> {
                int n = 0;
                if (params.length >= 3) {
                    try {
                        n = Integer.parseInt(params[2]);
                    } catch (NumberFormatException ignored) {
                        // 回落默认负载规模
                    }
                }
                int enabled = GCMovement.lodLoad(n);
                player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.lodLoad", enabled));
            }
            case "unload" -> {
                int released = GCMovement.lodUnload();
                player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.lodUnload", released));
            }
            case "train" -> TrainingBot.lodDiagnostic().forEach(player::dropMessage);
            default -> GCMovement.lodStats().forEach(player::dropMessage);
        }
    }

    // 把 idArg（bot 角色 id）解析为在线的 Character，失败时提示并返回 null。
    private Character resolveBot(Character player, String idArg) {
        int botId;
        try {
            botId = Integer.parseInt(idArg);
        } catch (NumberFormatException e) {
            player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.invalidBotId", idArg));
            return null;
        }
        Character bot = DefaultBotServerAccess.INSTANCE.getCharacterById(botId);
        if (bot == null || !BotHelpers.isBot(bot)) {
            player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.noBot", botId));
            return null;
        }
        return bot;
    }

    private void help(Character player) {
        player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.header"));
        for (int i = 1; i <= 20; i++) {
            player.dropMessage(I18nUtil.getMessage("BotCommand.gcmove.help" + i));
        }
    }
}
