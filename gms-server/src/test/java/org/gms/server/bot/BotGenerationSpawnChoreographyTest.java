package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.server.bot.commands.WarpCommands;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.replay.MovementCommands;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * 出生到场编排分支选择单测（录制引擎接线 P5-H2）：
 * {@code playSpawnChoreography} 必须严格按「portal 落下回放 → 可选微转身向左」编排——
 * 转身掷骰为 true 时两步都执行，为 false 时只落下不转身；bot 已销毁/未落图时整体跳过。
 * <p>
 * 通过包私有注入点（dropDelayRoller/turnDelayRoller/turnAroundRoller）固定随机量，
 * 并用 MockedStatic 隔离 WarpCommands/MovementCommands/GCMovement/BotStorage 的静态副作用；
 * blockingSleep 使用注入的 1ms 延迟，测试毫秒级完成。
 */
class BotGenerationSpawnChoreographyTest {

    private static final int BOT_ID = 2_000_002_100;

    private LongSupplier defaultDropDelayRoller;
    private LongSupplier defaultTurnDelayRoller;
    private BooleanSupplier defaultTurnAroundRoller;

    private Character bot;
    private MockedStatic<BotStorage> botStorageStatic;
    private MockedStatic<GCMovement> gcMovementStatic;
    private MockedStatic<WarpCommands> warpCommandsStatic;
    private MockedStatic<MovementCommands> movementCommandsStatic;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        defaultDropDelayRoller = BotGeneration.dropDelayRoller;
        defaultTurnDelayRoller = BotGeneration.turnDelayRoller;
        defaultTurnAroundRoller = BotGeneration.turnAroundRoller;
        BotGeneration.dropDelayRoller = () -> 1L;
        BotGeneration.turnDelayRoller = () -> 1L;

        bot = Mockito.mock(Character.class);
        Mockito.when(bot.getId()).thenReturn(BOT_ID);
        Mockito.when(bot.getMap()).thenReturn(Mockito.mock(MapleMap.class));

        botStorageStatic = Mockito.mockStatic(BotStorage.class);
        botStorageStatic.when(() -> BotStorage.botLoggedIn(BOT_ID)).thenReturn(true);
        gcMovementStatic = Mockito.mockStatic(GCMovement.class);
        warpCommandsStatic = Mockito.mockStatic(WarpCommands.class);
        movementCommandsStatic = Mockito.mockStatic(MovementCommands.class);
    }

    @AfterEach
    void tearDown() {
        BotGeneration.dropDelayRoller = defaultDropDelayRoller;
        BotGeneration.turnDelayRoller = defaultTurnDelayRoller;
        BotGeneration.turnAroundRoller = defaultTurnAroundRoller;
        movementCommandsStatic.close();
        warpCommandsStatic.close();
        gcMovementStatic.close();
        botStorageStatic.close();
    }

    private void runChoreography() throws Exception {
        Method method = BotGeneration.class.getDeclaredMethod("playSpawnChoreography", Character.class);
        method.setAccessible(true);
        method.invoke(null, bot);
    }

    @Test
    void turnAroundBranchTruePlaysDropDownThenTurnAround() throws Exception {
        BotGeneration.turnAroundRoller = () -> true;

        runChoreography();

        warpCommandsStatic.verify(() -> WarpCommands.botEnterPortalDropDown(bot), times(1));
        movementCommandsStatic.verify(() -> MovementCommands.microTurnAroundToLeft(bot), times(1));
    }

    @Test
    void turnAroundBranchFalsePlaysDropDownOnly() throws Exception {
        BotGeneration.turnAroundRoller = () -> false;

        runChoreography();

        warpCommandsStatic.verify(() -> WarpCommands.botEnterPortalDropDown(bot), times(1));
        movementCommandsStatic.verify(() -> MovementCommands.microTurnAroundToLeft(bot), never());
    }

    @Test
    void destroyedBotSkipsWholeChoreography() throws Exception {
        botStorageStatic.when(() -> BotStorage.botLoggedIn(BOT_ID)).thenReturn(false);
        BotGeneration.turnAroundRoller = () -> true;

        runChoreography();

        warpCommandsStatic.verify(() -> WarpCommands.botEnterPortalDropDown(bot), never());
        movementCommandsStatic.verify(() -> MovementCommands.microTurnAroundToLeft(bot), never());
    }
}
