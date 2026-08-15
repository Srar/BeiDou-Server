package org.gms.server.bot.replay;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.server.bot.BotGeneration;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.commands.MapleMessengerCommands;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;

import java.awt.Point;
import java.util.List;

import static org.gms.server.bot.commands.WarpCommands.botEnterFMRoom;
import static org.gms.server.bot.commands.WarpCommands.botExitFMRoom;
import static org.gms.server.bot.replay.InPacketReader.getMovementRecording;
import static org.gms.server.bot.replay.MovementCommands.BotMoveStreamHelper;
import static org.gms.server.bot.replay.MovementCommands.BotMoveStreamOffset;
import static org.gms.server.bot.replay.MovementCommands.BotMoveStreamUntilStopPoint;
import static org.gms.server.bot.replay.navigation.FMMovementCommands.moveToFMDoor;
import static org.gms.server.bot.replay.navigation.PathFinder.coordPathBuilder;
import static org.gms.server.bot.replay.navigation.PathFinder.coordPathSplitter;

/**
 * SoloMapling {@code ArtificialPlayer.TestMethods} 的逐行移植：GM 移动/录制回放的
 * 组合测试入口。MMC 主类暂未移植（gms 已有 botlog.txt 文件日志与粉笔黑板两条
 * 等效调试渠道），{@link #addMMC} 仅恢复「Console 假人加入 messenger」语义。
 */
public class TestMethods {

    private TestMethods() {
    }

    public static void testBotMoveCSV(Character fakechar, String recName) {
        // 原实现注释掉 CSV 流式回放（gms 同样保留为空）。
    }

    public static void testBotMove(Character fakechar, String recName) {
        MovementRecording mvr = getMovementRecording(fakechar.getMapId(), recName);
        BotMoveStreamHelper(mvr, fakechar, false, null);
    }

    public static void testBotMoveInjectedIntoStream(Character fakechar, String recName) {
        // 原实现注释掉注入式回放（gms 同样保留为空）。
    }

    public static void pathTest(Character fakechar) {
        // 原实现仅保留注释掉的终点/录像名组合，无实际动作。
    }

    /** Room-walker 路径（带停顿）。 */
    public static void fmRoomPathWithStops(Character fakechar, Point endPt) {
        List<TimeCoordinatePair> tcpSplit = coordPathSplitter(coordPathBuilder(fakechar, endPt), 10);
        for (TimeCoordinatePair cp : tcpSplit) {
            MovementCommands.pathFinderBeta(fakechar, cp.getPoints().getLast());
            BotHelpers.blockingSleep(2500);
        }
    }

    public static void botmovetest(Character fakechar, String recName) {
        testMovingBetweenDoors(fakechar);
    }

    public static void testMovingBetweenDoors(Character fakechar) {
        for (int s = 1; s <= 6; s++) {
            for (int e = 7; e <= 12; e++) {
                if (s == e) {
                    continue;
                }
                System.out.println(s + " -> " + e);
                moveToFMDoor(fakechar, e);
                BotHelpers.blockingSleep(1000);

                System.out.println(e + " -> " + s);
                moveToFMDoor(fakechar, s);
                BotHelpers.blockingSleep(1000);
            }
            BotHelpers.blockingSleep(1000);
        }
        System.out.println("end");
    }

    public static void testBotMoveMod(Character fakechar, String recName) {
        MovementRecording mvr = getMovementRecording(0, recName);
        BotMoveStreamOffset(mvr, fakechar);
    }

    public static void testBotMoveStopPoint(Character fakechar, String recName, Point stopPoint) {
        MovementRecording mvr = getMovementRecording(0, recName);
        BotMoveStreamUntilStopPoint(mvr, fakechar, stopPoint);
    }

    public static void testBotExitAllRooms(Character fakechar) {
        for (int i = 1; i < 23; i++) {
            botExitFMRoom(fakechar, i);
            BotHelpers.blockingSleep(500);
        }
    }

    public static void testBotEnterAllRoms(Character fakechar) {
        for (int i = 1; i < 23; i++) {
            botEnterFMRoom(fakechar, i);
            BotHelpers.blockingSleep(7000);
        }
    }

    public static void testBotEnterExitAllRooms(Character fakechar) {
        for (int i = 1; i < 23; i++) {
            botEnterFMRoom(fakechar, i);
            BotHelpers.blockingSleep(4000);
            botExitFMRoom(fakechar, i);
            BotHelpers.blockingSleep(4000);
        }
    }

    public static void findClosestTPPortal(Client c) {
        MapleMap mymap = c.getPlayer().getMap();
        Point mypos = c.getPlayer().getPosition();
        DebugUtilities.debugprint("mypos: " + mypos);
        Portal closestTpPortal = mymap.findClosestTeleportPortal(mypos);
        System.out.println("Closest tp portal: " + closestTpPortal.getName() + ", "
                + closestTpPortal.getId() + ", " + closestTpPortal.getPosition() + ", " + closestTpPortal.getTargetMapId());
    }

    /*
     * Test Dev Commands
     */

    /**
     * 把懒加载的调试傀儡「Console」拉进调试者 GM 的 messenger（SoloMapling addMMC 移植）。
     * <p>
     * MMC 主类（SoloMapling MapleMessengerConsole 及其交互命令）暂未移植：gms 已有
     * botlog.txt 文件日志与粉笔黑板两条等效调试渠道（见 BotDebugHandler），messenger
     * 对话式调试无消费者；故本方法仅恢复「Console 假人加入 messenger」语义，
     * 后续如需 messenger 调试再补主类。
     */
    public static void addMMC(Client c) {
        if (c == null || c.getPlayer() == null) {
            return;
        }
        // GM 尚未加入任何 messenger 时无调试通道可用；addBotToMessenger 内部直接
        // 解引用 mainChar.getMessenger()（getLowestPosition），不判空会 NPE。
        if (c.getPlayer().getMessenger() == null) {
            return;
        }
        Character consoleBot = BotGeneration.getConsoleBot();
        if (consoleBot != null) {
            MapleMessengerCommands.addBotToMessenger(c.getPlayer(), consoleBot);
        }
    }
}
