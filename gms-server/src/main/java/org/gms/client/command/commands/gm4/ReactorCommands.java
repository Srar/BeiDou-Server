package org.gms.client.command.commands.gm4;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.server.bot.BotExecutors;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.DefaultBotServerAccess;
import org.gms.server.bot.replay.MovementCommands;
import org.gms.server.bot.types.opq.OPQConstants;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Reactor;

import java.awt.Point;
import java.util.List;

import static org.gms.server.bot.commands.BotAttack.basicSwing;
import static org.gms.server.bot.gacha.CustomReactor.deleteReactor;
import static org.gms.server.bot.gacha.CustomReactor.getAllReactorsData;
import static org.gms.server.bot.gacha.CustomReactor.getNearestReactor;
import static org.gms.server.bot.gacha.CustomReactor.hitReactor;

/**
 * GM4 命令 !reactor：Reactor 检查与 bot 攻击测试。逐语义对齐 SoloMapling ReactorCommands。
 */
public class ReactorCommands extends Command {
    {
        setDescription("Reactor inspection and bot-attack test commands.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            player.yellowMessage("No Command Parameter Found. Try !reactor help");
            return;
        }
        if (params.length == 1) {
            handleDirectCommand(params[0], c);
            return;
        }
        if (params.length == 2) {
            if (isInteger(params[1])) {
                int commandNum = Integer.parseInt(params[1]);
                handleNumberedCommand(params[0], commandNum, c);
            } else {
                player.yellowMessage("Second input not an integer");
            }
            return;
        }
        if (params.length == 3) {
            if (isInteger(params[1]) && isInteger(params[2])) {
                int commandNum = Integer.parseInt(params[1]);
                int commandNum2 = Integer.parseInt(params[2]);
                handleTwoNumberedCommand(params[0], commandNum, commandNum2, c);
            } else {
                player.yellowMessage("Second and third inputs must both be integers");
            }
        }
    }

    private void handleDirectCommand(String input, Client c) {
        Character player = c.getPlayer();
        switch (input.toLowerCase()) {
            case "help":
                printHelp(player);
                break;
            case "list":
                listReactors(c.getPlayer(), player);
                break;
            case "near":
                int nearOid = getNearestReactor(c.getPlayer());
                player.yellowMessage("Nearest reactor oid=" + nearOid);
                break;
            case "hitnear":
                int hitOid = getNearestReactor(c.getPlayer());
                if (hitOid == 0) {
                    player.yellowMessage("No reactor near you");
                    break;
                }
                hitReactor(c.getPlayer().getMap(), hitOid);
                player.yellowMessage("Hit reactor oid=" + hitOid);
                break;
            case "breaknear":
                int breakOid = getNearestReactor(c.getPlayer());
                if (breakOid == 0) {
                    player.yellowMessage("No reactor near you");
                    break;
                }
                for (int i = 0; i < OPQConstants.MAX_REACTOR_HITS; i++) {
                    hitReactor(c.getPlayer().getMap(), breakOid);
                }
                player.yellowMessage("Broke reactor oid=" + breakOid + " (" + OPQConstants.MAX_REACTOR_HITS + " hits)");
                break;
            case "dump":
                getAllReactorsData(c.getPlayer());
                player.yellowMessage("Dumped reactors to debug log");
                break;
            default:
                player.yellowMessage("Invalid command - Direct Command");
                break;
        }
    }

    private void handleNumberedCommand(String input, int input2, Client c) {
        Character player = c.getPlayer();
        Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null");
            return;
        }

        switch (input.toLowerCase()) {
            case "botattack":
                basicSwing(fakechar);
                player.yellowMessage("Bot " + fakechar.getName() + " played basic swing animation");
                break;
            case "botlistreactors":
                listReactors(fakechar, player);
                break;
            default:
                player.yellowMessage("Invalid command - NumberedCommand");
                break;
        }
    }

    private void handleTwoNumberedCommand(String input, int input2, int input3, Client c) {
        Character player = c.getPlayer();
        Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null");
            return;
        }

        switch (input.toLowerCase()) {
            case "hit":
                hitReactor(fakechar.getMap(), input3);
                player.yellowMessage("Hit reactor oid=" + input3 + " on " + fakechar.getName() + "'s map");
                break;
            case "break":
                for (int i = 0; i < OPQConstants.MAX_REACTOR_HITS; i++) {
                    hitReactor(fakechar.getMap(), input3);
                }
                player.yellowMessage("Broke reactor oid=" + input3 + " (" + OPQConstants.MAX_REACTOR_HITS + " hits)");
                break;
            case "destroy":
                deleteReactor(fakechar.getMap(), input3);
                player.yellowMessage("Destroyed reactor oid=" + input3);
                break;
            case "botbreak":
                BotExecutors.runAsync(() -> botBreakReactor(fakechar, input3, c.getPlayer()));
                break;
            default:
                player.yellowMessage("Invalid command - TwoNumberedCommand");
                break;
        }
    }

    private void printHelp(Character player) {
        player.yellowMessage("---- Reactor Commands (!reactor) ----");
        player.yellowMessage("!reactor list                    - list alive reactors on your map");
        player.yellowMessage("!reactor near                    - nearest reactor oid");
        player.yellowMessage("!reactor hitnear                 - hit nearest reactor (1 hit)");
        player.yellowMessage("!reactor breaknear               - break nearest reactor (4 hits)");
        player.yellowMessage("!reactor dump                    - dump all reactors to debug log");
        player.yellowMessage("!reactor botattack <cid>         - bot plays basic swing animation");
        player.yellowMessage("!reactor botlistreactors <cid>   - list reactors on bot's map");
        player.yellowMessage("!reactor hit <cid> <oid>         - hit reactor by oid");
        player.yellowMessage("!reactor break <cid> <oid>       - break reactor (4 hits)");
        player.yellowMessage("!reactor destroy <cid> <oid>     - delete reactor by oid");
        player.yellowMessage("!reactor botbreak <cid> <oid>    - bot walks to reactor, swings + hits");
    }

    private void listReactors(Character viewer, Character player) {
        MapleMap map = viewer.getMap();
        List<Reactor> reactors = map.getAllReactors();
        long aliveCount = reactors.stream().filter(Reactor::isAlive).count();
        player.yellowMessage("---- Reactors on map " + map.getId() + " (alive=" + aliveCount + ") ----");
        for (Reactor r : reactors) {
            if (!r.isAlive()) {
                continue;
            }
            player.yellowMessage("oid=" + r.getObjectId()
                    + " id=" + r.getId()
                    + " state=" + r.getState()
                    + " pos=" + r.getPosition());
        }
    }

    private void botBreakReactor(Character fakechar, int oid, Character requester) {
        Reactor r = fakechar.getMap().getReactorByOid(oid);
        if (r == null) {
            requester.yellowMessage("No reactor oid=" + oid + " on bot's map");
            return;
        }

        Point reactorPos = r.getPosition();
        requester.yellowMessage("Sending " + fakechar.getName() + " to reactor oid=" + oid + " at " + reactorPos);
        try {
            MovementCommands.pathFinderBetaAerial(fakechar, reactorPos);
            BotHelpers.blockingSleep(3000);
            for (int i = 0; i < OPQConstants.MAX_REACTOR_HITS; i++) {
                basicSwing(fakechar);
                hitReactor(fakechar.getMap(), oid);
                BotHelpers.blockingSleep(OPQConstants.SWING_INTERVAL_MS);
            }
            requester.yellowMessage("botbreak done.");
        } catch (Exception e) {
            requester.yellowMessage("botbreak failed: " + e.getMessage());
            e.printStackTrace();
        }
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
