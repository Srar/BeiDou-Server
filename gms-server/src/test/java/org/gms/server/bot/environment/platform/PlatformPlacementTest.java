package org.gms.server.bot.environment.platform;

import org.gms.client.Character;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.DefaultBotServerAccess;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.replay.MovementCommands;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.awt.Point;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PlatformPlacement 换位接线回归防线（bot 占位感知换位 / 防重叠 nudge / 平台语义名解析）。
 * <p>
 * 场景构造：
 * <ul>
 *   <li>换位测试把 {@link DefaultBotServerAccess#INSTANCE} 经反射替换为 mock，令
 *       {@code getAllCharsOnMap} 走 mock 地图返回"多 bot 占同一平台"的角色表，
 *       再 {@code mockStatic(GCMovement)} 捕获换位目标点；</li>
 *   <li>nudge 测试把另一个 bot 注册进 {@link BotStorage}（{@code BotHelpers.isBot}
 *       双判据需要）并与目标 bot 摆成重叠站位；</li>
 *   <li>语义名测试直接解析真实录制数据（Free Market 910000000 的 m1/m2/m5，
 *       宠物公园 100000202 的 m1）。PlatformParser 先读 classpath 资源，
 *       故测试不依赖进程 CWD。</li>
 * </ul>
 */
class PlatformPlacementTest {

    /** 自由市场地图（商人 bot 活动图）。 */
    private static final int FM_MAP_ID = 910000000;
    /** 宠物公园（HenesysBot PET_PARK，可游走平台语义仅 m1）。 */
    private static final int PET_PARK_MAP_ID = 100000202;

    /** findUnoccupiedPoint 在 gap 内至少保留的边距（MIN_SPACING=30 → padding≥7）。 */
    private static final int MIN_GAP_PADDING = 7;

    private DefaultBotServerAccess originalServerAccess;

    @BeforeAll
    static void setUp() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void rememberServerAccess() {
        originalServerAccess = DefaultBotServerAccess.INSTANCE;
    }

    @AfterEach
    void restoreServerAccess() throws Exception {
        Field instance = DefaultBotServerAccess.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, originalServerAccess);
    }

    // =========================================================================
    // 平台语义名 → 坐标解析
    // =========================================================================

    /**
     * 商人 bot 换位用的语义平台名（m1/m2/m5）必须能解析出真实坐标：
     * Free Market 的 m1/m2/m5 与宠物公园的 m1（HenesysBot 唯一可游走平台）。
     */
    @Test
    void semanticPlatformNamesResolveToCoordinates() {
        assertDataFileExists("map" + FM_MAP_ID, "m1.csv");

        for (String platformId : List.of("m1", "m2", "m5")) {
            Platform platform = PlatformParser.parsePlatform(FM_MAP_ID, platformId);
            assertNotNull(platform, platformId + " 应解析出平台");
            assertFalse(platform.getSortedPoints().isEmpty(),
                    platformId + " 应解析出至少 1 个坐标点");
            assertEquals(Platform.Type.FLAT, platform.getType(),
                    "FM " + platformId + " 应为 FLAT 平台");
            assertTrue(platform.getMinX() <= platform.getMaxX(),
                    platformId + " minX 应不大于 maxX");
        }

        // HenesysBot 的 PET_PARK → "m1" 语义前提：宠物公园必须存在 m1 平台。
        Platform petParkM1 = PlatformParser.parsePlatform(PET_PARK_MAP_ID, "m1");
        assertFalse(petParkM1.getSortedPoints().isEmpty(), "宠物公园 m1 应解析出坐标点");
        assertTrue(PlatformPlacement.getMainPlatformIds(PET_PARK_MAP_ID).contains("m1"),
                "宠物公园主平台列表应包含 m1");
    }

    // =========================================================================
    // 占位冲突时换位到空位（botMoveToPlatformAnyUnoccupiedSpotDynamic）
    // =========================================================================

    /**
     * 多个 bot 挤占同一平台（含换位者自身）时，Dynamic 换位应挑一个平台内的
     * 空位：X 落在平台范围内、Y 等于平台 baseY，且与每个占位点保持最小间距。
     */
    @Test
    void dynamicRelocationPicksUnoccupiedSpotWhenPlatformContested() {
        Platform m1 = PlatformParser.parsePlatform(FM_MAP_ID, "m1");
        assertFalse(m1.getSortedPoints().isEmpty(), "FM m1 应解析出坐标点");
        int midX = (m1.getMinX() + m1.getMaxX()) / 2;
        Point occupiedA = new Point(midX, m1.getBaseY());
        Point occupiedB = new Point(midX + 20, m1.getBaseY());

        Character occupantA = mock(Character.class);
        when(occupantA.getPosition()).thenReturn(occupiedA);
        Character occupantB = mock(Character.class);
        when(occupantB.getPosition()).thenReturn(occupiedB);

        // 换位者自己也站在平台上（与 occupantA 重叠，代表"堆叠"现场）。
        Character mover = mock(Character.class);
        when(mover.getMapId()).thenReturn(FM_MAP_ID);
        when(mover.getPosition()).thenReturn(new Point(occupiedA));

        MapleMap mockMap = mock(MapleMap.class);
        when(mockMap.getAllPlayers()).thenReturn(List.of(occupantA, occupantB, mover));

        DefaultBotServerAccess mockAccess = mock(DefaultBotServerAccess.class);
        when(mockAccess.getMap(anyInt(), anyInt(), eq(FM_MAP_ID))).thenReturn(mockMap);
        swapServerAccess(mockAccess);

        List<Point> occupied = List.of(occupiedA, occupiedB, new Point(occupiedA));
        try (MockedStatic<GCMovement> gcmove = Mockito.mockStatic(GCMovement.class)) {
            gcmove.when(() -> GCMovement.isMoving(mover)).thenReturn(false);

            PlatformPlacement.botMoveToPlatformAnyUnoccupiedSpotDynamic(mover, "m1");

            // 捕获换位目标点并断言"空位"语义。
            Point target = captureMoveTarget(gcmove, mover);
            assertNotNull(target, "换位应产生目标点");
            assertEquals(m1.getBaseY(), target.y, "换位目标 Y 应等于平台 baseY");
            assertTrue(target.x >= m1.getMinX() && target.x <= m1.getMaxX(),
                    "换位目标 X 应落在平台范围内：" + target.x
                            + " ∉ [" + m1.getMinX() + "," + m1.getMaxX() + "]");
            for (Point occ : occupied) {
                assertTrue(Math.abs(target.x - occ.x) >= MIN_GAP_PADDING,
                        "换位目标应避开占位点（距 " + occ + " 至少 " + MIN_GAP_PADDING
                                + "px，实际目标 " + target + "）");
            }
        }
    }

    /**
     * 互斥语义：GCMovement 正在驱动该角色时不得并发换位（跳过 move）。
     */
    @Test
    void dynamicRelocationSkipsWhileDynamicWalkInProgress() {
        Character mover = mock(Character.class);
        when(mover.getMapId()).thenReturn(FM_MAP_ID);

        DefaultBotServerAccess mockAccess = mock(DefaultBotServerAccess.class);
        MapleMap mockMap = mock(MapleMap.class);
        when(mockMap.getAllPlayers()).thenReturn(List.of());
        when(mockAccess.getMap(anyInt(), anyInt(), eq(FM_MAP_ID))).thenReturn(mockMap);
        swapServerAccess(mockAccess);

        try (MockedStatic<GCMovement> gcmove = Mockito.mockStatic(GCMovement.class)) {
            gcmove.when(() -> GCMovement.isMoving(mover)).thenReturn(true);

            PlatformPlacement.botMoveToPlatformAnyUnoccupiedSpotDynamic(mover, "m1");

            gcmove.verify(() -> GCMovement.move(any(Character.class), anyInt(), anyInt()),
                    Mockito.never());
        }
    }

    /**
     * null 安全：空角色 / 空平台名直接返回，不抛异常。
     */
    @Test
    void dynamicRelocationIgnoresNullArguments() {
        PlatformPlacement.botMoveToPlatformAnyUnoccupiedSpotDynamic(null, "m1");
        PlatformPlacement.botMoveToPlatformAnyUnoccupiedSpotDynamic(mock(Character.class), null);
    }

    // =========================================================================
    // 防重叠 nudge（MovementCommands.nudgeAwayFromOverlap）
    // =========================================================================

    /**
     * 两个 bot 站位重叠（|dx|&lt;40 且 |dy|&lt;30）时 nudge 应触发并返回 true。
     */
    @Test
    void nudgeTriggersWhenBotsOverlap() {
        int moverId = BotHelpers.BOT_BASE_ID + 1001;
        int otherId = BotHelpers.BOT_BASE_ID + 1002;

        // isBot 双判据：id 落在 bot 区段且注册进 BotStorage。
        BotStorage.addActiveBot(otherId, mock(BotSM.class));
        try {
            Character mover = mock(Character.class);
            when(mover.getId()).thenReturn(moverId);
            when(mover.getChair()).thenReturn(0);
            when(mover.getPosition()).thenReturn(new Point(100, 50));

            MapleMap map = mock(MapleMap.class);
            when(mover.getMap()).thenReturn(map);

            Character other = mock(Character.class);
            when(other.getId()).thenReturn(otherId);
            when(other.getPosition()).thenReturn(new Point(110, 55));

            when(map.getAllPlayers()).thenReturn(List.of(mover, other));

            assertTrue(MovementCommands.nudgeAwayFromOverlap(mover),
                    "重叠站位应触发 nudge");
        } finally {
            BotStorage.removeActiveBot(otherId);
        }
    }

    /**
     * 站位相距较远时 nudge 不应触发。
     */
    @Test
    void nudgeSkipsWhenBotsFarApart() {
        int moverId = BotHelpers.BOT_BASE_ID + 2001;
        int otherId = BotHelpers.BOT_BASE_ID + 2002;

        BotStorage.addActiveBot(otherId, mock(BotSM.class));
        try {
            Character mover = mock(Character.class);
            when(mover.getId()).thenReturn(moverId);
            when(mover.getChair()).thenReturn(0);
            when(mover.getPosition()).thenReturn(new Point(100, 50));

            MapleMap map = mock(MapleMap.class);
            when(mover.getMap()).thenReturn(map);

            Character other = mock(Character.class);
            when(other.getId()).thenReturn(otherId);
            when(other.getPosition()).thenReturn(new Point(500, 50));

            when(map.getAllPlayers()).thenReturn(List.of(mover, other));

            assertFalse(MovementCommands.nudgeAwayFromOverlap(mover),
                    "相距较远的站位不应触发 nudge");
        } finally {
            BotStorage.removeActiveBot(otherId);
        }
    }

    // =========================================================================
    // 测试辅助
    // =========================================================================

    private static void swapServerAccess(DefaultBotServerAccess replacement) {
        try {
            Field instance = DefaultBotServerAccess.class.getDeclaredField("INSTANCE");
            instance.setAccessible(true);
            instance.set(null, replacement);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法替换 DefaultBotServerAccess.INSTANCE", e);
        }
    }

    private static Point captureMoveTarget(MockedStatic<GCMovement> gcmove, Character mover) {
        ArgumentCaptor<Integer> xCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> yCap = ArgumentCaptor.forClass(Integer.class);
        gcmove.verify(() -> GCMovement.move(eq(mover), xCap.capture(), yCap.capture()));
        return new Point(xCap.getValue(), yCap.getValue());
    }

    private static void assertDataFileExists(String mapDir, String fileName) {
        for (String base : new String[]{"movementDataPackets", "gms-server/movementDataPackets"}) {
            Path candidate = Paths.get(base, mapDir, fileName);
            if (Files.isRegularFile(candidate)) {
                return;
            }
        }
        throw new AssertionError("真实数据文件缺失：" + mapDir + "/" + fileName);
    }
}
