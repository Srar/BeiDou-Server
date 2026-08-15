package org.gms.client.command.commands.gm4;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.server.bot.BotExecutors;
import org.gms.server.bot.DefaultBotServerAccess;
import org.gms.server.bot.replay.InPacketReader;
import org.gms.server.bot.replay.MovementCommands;
import org.gms.server.bot.replay.TestMethods;
import org.gms.server.bot.wander.BotWanderSystem;
import org.gms.server.maps.MapleMap;

import java.awt.Point;

import static org.gms.server.bot.commands.WarpCommands.botMoveMap;
import static org.gms.server.bot.replay.DebugUtilities.debugprint;
import static org.gms.server.bot.replay.InPacketReader.setMoveDataRecording;
import static org.gms.server.bot.replay.InPacketReader.setMovementDataRecordingMapId;
import static org.gms.server.bot.replay.MovementCommands.BotIdleStandingUpdate;
import static org.gms.server.bot.replay.MovementCommands.botCancelChair;
import static org.gms.server.bot.replay.MovementCommands.botFallDownPacket;
import static org.gms.server.bot.replay.MovementCommands.botSitChair;
import static org.gms.server.bot.replay.MovementCommands.findFootHoldId;
import static org.gms.server.bot.replay.MovementCommands.interruptBotMovement;
import static org.gms.server.bot.replay.MovementCommands.testInterruptPathfinder;
import static org.gms.server.bot.replay.TestMethods.botmovetest;

/**
 * GM4 命令 !move：Bot 移动/路径/回放测试。子命令逐语义对齐 SoloMapling BotMoveCommand。
 */
public class BotMoveCommand extends Command {
    {
        setDescription("Bot Movement Commands Test.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            player.yellowMessage("Please input an integer for cid. Try !move help");
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
            } else if (!isInteger(params[0]) && !isInteger(params[1])) {
                handleTwoStringCommand(params[0], params[1], c);
            } else {
                player.yellowMessage("Second input not an integer");
            }
            return;
        }
        if (params.length == 3) {
            if (isInteger(params[1])) {
                int commandNum = Integer.parseInt(params[1]);
                handleBotStringCommand(params[0], commandNum, params[2], c);
            } else {
                player.yellowMessage("Second input not an integer");
            }
            return;
        }
        if (params.length == 5) {
            if (isInteger(params[1])) {
                int commandNum = Integer.parseInt(params[1]);
                int xpos = Integer.parseInt(params[3]);
                int ypos = Integer.parseInt(params[4]);
                Point point = new Point(xpos, ypos);
                handleBotPointCommand(params[0], commandNum, params[2], point, c);
            } else {
                player.yellowMessage("Second input not an integer");
            }
        }
    }

    private void handleDirectCommand(String input, Client c) {
        Character player = c.getPlayer();
        switch (input.toLowerCase()) {
            case "help":
                printHelp(player);
                break;
            case "stoprecording":
                setMoveDataRecording(false);
                setMovementDataRecordingMapId(0);
                break;
            case "getfhy":
                int fh = c.getPlayer().getFh();
                debugprint(fh);
                break;
            case "whereami":
            case "getpos":
            case "getposition":
            case "getmypos":
            case "findfh":
                int mfh = findFootHoldId(c.getPlayer());
                Point pt = c.getPlayer().getPosition();
                MapleMap myMap = c.getPlayer().getMap();
                player.yellowMessage("Foothold: " + mfh + ", Point: " + pt.x + ", " + pt.y);
                debugprint(mfh, pt);
                break;
            case "testdc":
                // 原实现注释掉 disconnectFirstClient(c)。
                break;
            case "closesttpportal":
                TestMethods.findClosestTPPortal(c);
                break;
            default:
                player.yellowMessage("Invalid command - Direct Command");
                break;
        }
    }

    private void handleBotPointCommand(String input, int input2, String input3, Point point, Client c) {
        Character player = c.getPlayer();
        Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null");
            return;
        }

        player.yellowMessage("Command: " + input2 + ", arg: " + input3 + ", Point: " + point);
        switch (input.toLowerCase()) {
            case "botstop":
            case "botmovestop":
                BotExecutors.runAsync(() -> TestMethods.testBotMoveStopPoint(fakechar, input3, point));
                break;
            case "pathfinderaerial":
            case "aerialpathfinder":
                MovementCommands.testAerialPathFinder(fakechar, point);
                break;
            default:
                player.yellowMessage("Invalid command - Two Number");
                break;
        }
    }

    private void handleBotStringCommand(String input, int input2, String input3, Client c) {
        Character player = c.getPlayer();
        Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null");
            return;
        }

        player.yellowMessage("Command: " + input2 + ", arg: " + input3);
        switch (input.toLowerCase()) {
            case "bot":
            case "botmove":
                BotExecutors.runAsync(() -> TestMethods.testBotMove(fakechar, input3));
                break;
            case "botmod":
            case "botmovemod":
                BotExecutors.runAsync(() -> TestMethods.testBotMoveMod(fakechar, input3));
                break;
            case "botinject":
            case "botmoveinject":
                // 原实现注释掉注入式回放。
                break;
            case "botmovecsv":
                BotExecutors.runAsync(() -> TestMethods.testBotMoveCSV(fakechar, input3));
                break;
            case "botpath":
                BotExecutors.runAsync(() -> TestMethods.pathTest(fakechar));
                break;
            case "botmovetest":
                BotExecutors.runAsync(() -> botmovetest(fakechar, input3));
                break;
            case "movetoportal":
                if (isInteger(input3)) {
                    MovementCommands.moveToPortal(fakechar, Integer.parseInt(input3));
                } else {
                    player.yellowMessage("Portal id must be an integer");
                }
                break;
            case "movetoportalenter":
                if (isInteger(input3)) {
                    MovementCommands.moveToPortalAndEnter(fakechar, Integer.parseInt(input3));
                } else {
                    player.yellowMessage("Portal id must be an integer");
                }
                break;
            default:
                player.yellowMessage("Invalid command - Two Number");
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
            case "idle":
                BotIdleStandingUpdate(fakechar);
                break;
            case "fall":
                botFallDownPacket(fakechar);
                break;
            case "map":
                int mapId = 910000010;
                botMoveMap(fakechar, mapId);
                break;
            case "testallexitdoors":
                BotExecutors.runAsync(() -> TestMethods.testBotExitAllRooms(fakechar));
                break;
            case "testalldoors":
                BotExecutors.runAsync(() -> TestMethods.testBotEnterExitAllRooms(fakechar));
                break;
            case "pathfinder":
                BotExecutors.runAsync(() -> MovementCommands.pathFinderBeta(fakechar, c.getPlayer().getPosition()));
                break;
            case "pathaware":
                BotExecutors.runAsync(() -> {
                    MovementCommands.pathFinderAware(fakechar, new Point(1937, 334));
                    player.yellowMessage("Aware pathfinder started for: " + fakechar.getName());
                });
                break;
            case "pathaware2":
                MovementCommands.pathFinderAware(fakechar, new Point(3889, 454));
                player.yellowMessage("Aware pathfinder ended for: " + fakechar.getName());
                break;
            case "pathfinderaerial":
            case "aerialpathfinder":
                MovementCommands.testAerialPathFinder(fakechar, c.getPlayer().getPosition());
                break;
            case "pathpoints":
                BotExecutors.runAsync(() -> TestMethods.fmRoomPathWithStops(fakechar, c.getPlayer().getPosition()));
                break;
            case "sitchair":
                Integer chairId2 = 3010071;
                botSitChair(fakechar, chairId2);
                break;
            case "unsitchair":
            case "cancelchair":
                botCancelChair(fakechar);
                break;
            case "getbotpos":
                Point pos = fakechar.getPosition();
                int botFh = findFootHoldId(fakechar);
                int botMapId = fakechar.getMapId();
                player.yellowMessage("Bot " + fakechar.getId() + " — Pos: (" + pos.x + ", " + pos.y + "), FH: " + botFh + ", Map: " + botMapId);
                debugprint("Bot " + fakechar.getId() + " — Pos: " + pos + ", FH: " + botFh + ", Map: " + botMapId);
                break;
            case "interrupt":
            case "stop":
                interruptBotMovement(fakechar);
                player.yellowMessage("Interrupted movement for: " + fakechar.getName());
                break;
            case "testinterrupt":
                BotExecutors.runAsync(() -> {
                    testInterruptPathfinder(fakechar, c.getPlayer().getPosition(), 2000);
                    player.yellowMessage("Test interrupt pathfinder done for: " + fakechar.getName());
                });
                break;
            case "faceme":
                MovementCommands.botFaceTowardsPoint(fakechar, c.getPlayer().getPosition());
                player.yellowMessage("Bot " + fakechar.getId() + " facing towards you");
                break;
            case "wander":
                BotWanderSystem.start(fakechar);
                player.yellowMessage("Bot " + fakechar.getId() + " wandering this map");
                break;
            case "wandernear":
                BotWanderSystem.start(fakechar, c.getPlayer().getPosition().x, 150);
                player.yellowMessage("Bot " + fakechar.getId() + " wandering near you (r=150)");
                break;
            case "wanderstop":
                BotWanderSystem.stop(fakechar);
                player.yellowMessage("Bot " + fakechar.getId() + " stopped wandering");
                break;
            default:
                player.yellowMessage("Invalid command - NumberedCommand");
                break;
        }
    }

    private void printHelp(Character player) {
        player.yellowMessage("---- Bot Movement Commands (!move) ----");
        player.yellowMessage("-- Player Position --");
        player.yellowMessage("!move whereami                   - get your position & foothold");
        player.yellowMessage("!move closesttpportal            - find closest TP portal");
        player.yellowMessage("-- Recording --");
        player.yellowMessage("!move startrecording <name>      - start recording movement data");
        player.yellowMessage("!move stoprecording              - stop movement recording");
        player.yellowMessage("-- Bot Info --");
        player.yellowMessage("!move getbotpos <cid>            - get bot position, foothold, map");
        player.yellowMessage("!move faceme <cid>               - bot faces towards you");
        player.yellowMessage("-- Flavor Wander --");
        player.yellowMessage("!move wander <cid>               - stroll random legal spots (whole map)");
        player.yellowMessage("!move wandernear <cid>           - loiter near you (radius 150)");
        player.yellowMessage("!move wanderstop <cid>           - stop wandering");
        player.yellowMessage("-- Basic Movement --");
        player.yellowMessage("!move idle <cid>                 - make bot idle standing");
        player.yellowMessage("!move fall <cid>                 - make bot fall down");
        player.yellowMessage("!move map <cid>                  - move bot to map 910000010");
        player.yellowMessage("!move interrupt/stop <cid>       - stop bot movement");
        player.yellowMessage("!move testinterrupt <cid>        - interrupt after 2s delay");
        player.yellowMessage("-- Pathfinding --");
        player.yellowMessage("!move pathfinder <cid>           - pathfind to your position");
        player.yellowMessage("!move pathaware <cid>            - aware pathfind (hardcoded point)");
        player.yellowMessage("!move pathfinderaerial <cid>     - aerial pathfind to you");
        player.yellowMessage("!move pathfinderaerial <cid> <s> <x> <y> - aerial to point");
        player.yellowMessage("-- Replay --");
        player.yellowMessage("!move botmove <cid> <name>       - play movement recording");
        player.yellowMessage("!move botmovecsv <cid> <name>    - play CSV recording");
        player.yellowMessage("!move botmovetest <cid> <name>   - movement test");
        player.yellowMessage("-- Portal Navigation --");
        player.yellowMessage("!move movetoportal <cid> <id>    - move bot to portal");
        player.yellowMessage("!move movetoportalenter <cid> <id> - move to portal and enter");
        player.yellowMessage("-- Chair --");
        player.yellowMessage("!move sitchair <cid>             - bot sits on chair");
        player.yellowMessage("!move cancelchair <cid>          - bot cancels sit");
        player.yellowMessage("-- FM Room Testing --");
        player.yellowMessage("!move testallexitdoors <cid>     - test all room exits");
        player.yellowMessage("!move testalldoors <cid>         - test all room entries/exits");
        player.yellowMessage("!move pathpoints <cid>           - FM room path with stops");
    }

    private void handleTwoStringCommand(String input, String input2, Client c) {
        Character player = c.getPlayer();
        switch (input.toLowerCase()) {
            case "startrecording":
                String recMovementDataName = (input2);
                player.yellowMessage("Start movement Data recording: " + recMovementDataName);
                InPacketReader.setMovementDataRecordingName(recMovementDataName);
                setMovementDataRecordingMapId(c.getPlayer().getMapId());
                setMoveDataRecording(true);
                break;
            default:
                player.yellowMessage("Invalid command Two Object");
                break;
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
