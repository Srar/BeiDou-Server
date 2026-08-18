package org.gms.server.bot.types.opq;

import org.gms.client.Character;
import org.gms.server.bot.replay.MovementCommands;
import org.gms.server.bot.replay.navigation.PathFinder;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.awt.Point;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPQ 双引擎混用死锁回归护栏（C1）。
 * <p>
 * 背景：handleStage1Return / handleStage2Return / moveToPortal / tower 步行曾走
 * GCMovement.move（enable 会话级永久持锁），而 handleStage2Navigate 走
 * pathFinderBetaAerial（调用级拿锁、被占静默返回 null）→ Stage2 永久死锁、第二轮起
 * Stage1 也死锁。修复后 OPQBot 全生命周期只走录制引擎（调用级拿锁）。
 * <p>
 * 断言分两层：
 * <ul>
 *   <li>源码护栏：OPQBot 源文件不得再引用 GCMovement（防回归）；</li>
 *   <li>锁语义：模拟 FSM 顺序——STAGE_1_RETURN 的录制移动（调用级拿锁）结束后锁必须
 *       空闲，使 STAGE_2_NAVIGATE 的 pathFinderBetaAerial 能拿到锁；会话级永久持锁
 *       则会令其拿锁失败（原死锁机理）。</li>
 * </ul>
 */
public class OPQLockRegressionTest {

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @Test
    void opqBotSourceNoLongerReferencesGCMovement() throws IOException {
        // 运行 CWD 可能是仓库根或 gms-server 目录，两个候选路径取存在的那个。
        Path source = null;
        for (String candidate : new String[]{
                "src/main/java/org/gms/server/bot/types/opq/OPQBot.java",
                "gms-server/src/main/java/org/gms/server/bot/types/opq/OPQBot.java"}) {
            Path p = Path.of(candidate);
            if (Files.exists(p)) {
                source = p;
                break;
            }
        }
        assertTrue(source != null, "OPQBot.java 源文件未找到（检查 CWD）");
        String content = Files.readString(source, StandardCharsets.UTF_8);
        assertFalse(content.contains("GCMovement"),
                "OPQBot 不得再引用 GCMovement：会话级永久持锁会让 pathFinderBetaAerial 静默失败导致 Stage2 死锁");
    }

    @Test
    void recordedEngineLockIsFreeAfterStage1ReturnStyleMove() {
        Character bot = newBot(2_000_003_101);

        // STAGE_1_RETURN 语义：pathFinderBeta 调用级拿锁——进入时持锁
        assertTrue(MovementCommands.tryAcquireMovementLock(bot), "STAGE_1_RETURN 录制移动拿锁应成功");
        // …（录制移动执行中）…
        // 调用级锁在移动结束时必须释放（pathFinderBeta 的 try/finally）
        MovementCommands.releaseMovementLock(bot);
        assertFalse(MovementCommands.isBotMoving(bot), "STAGE_1_RETURN 结束后锁必须空闲");

        // STAGE_2_NAVIGATE 语义：pathFinderBetaAerial 此时必须能拿到锁
        assertTrue(MovementCommands.tryAcquireMovementLock(bot),
                "STAGE_1_RETURN 后 STAGE_2_NAVIGATE 必须能获得锁（会话级永久持锁时此处失败→Stage2 死锁）");
        MovementCommands.releaseMovementLock(bot);
    }

    @Test
    void moveToPortalNoOpsSafelyWhenPortalMissingAndHoldsNoLock() {
        Character bot = newBot(2_000_003_102);
        MapleMap map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getPortal(4)).thenReturn(null); // portal 缺失（OPQ 场景防御）
        Mockito.when(bot.getMap()).thenReturn(map);

        // moveToPortal 对缺失 portal 静默返回，且不得持有锁（调用级锁协议）
        MovementCommands.moveToPortal(bot, 4);
        assertFalse(MovementCommands.isBotMoving(bot), "portal 缺失时 moveToPortal 不得遗留锁");
    }

    @Test
    void moveToPortalAcquiresAndReleasesLock() {
        Character bot = newBot(2_000_003_103);
        MapleMap map = Mockito.mock(MapleMap.class);
        Portal portal = Mockito.mock(Portal.class);
        Mockito.when(portal.getPosition()).thenReturn(new Point(100, 100));
        Mockito.when(map.getPortal(4)).thenReturn(portal);
        Mockito.when(bot.getMap()).thenReturn(map);
        Mockito.when(bot.isFacingLeft()).thenReturn(false);

        // 钉死导航数据读取：mapId=0 无 MapGraph 数据，createPath/getNavElements 返回空
        // 路径，executePath 空 elements 直接 return——本测试只关心锁协议（try/finally）。
        try (MockedStatic<PathFinder> pf = Mockito.mockStatic(PathFinder.class)) {
            pf.when(() -> PathFinder.createPath(Mockito.anyInt(), Mockito.any(Point.class),
                            Mockito.any(Point.class), Mockito.any(PathFinder.PathType.class)))
                    .thenReturn(List.of());
            pf.when(() -> PathFinder.getNavElements(Mockito.anyInt(), Mockito.anyList()))
                    .thenReturn(List.of());

            MovementCommands.moveToPortal(bot, 4);
        }
        assertFalse(MovementCommands.isBotMoving(bot), "moveToPortal 结束后锁必须释放（try/finally）");
    }

    private static Character newBot(int id) {
        Character bot = Mockito.mock(Character.class);
        Mockito.when(bot.getId()).thenReturn(id);
        Mockito.when(bot.getName()).thenReturn("TestOPQBot");
        Mockito.when(bot.getPosition()).thenReturn(new Point(0, 0));
        Mockito.when(bot.getMapId()).thenReturn(0);
        Mockito.when(bot.getStance()).thenReturn(0);
        return bot;
    }
}
