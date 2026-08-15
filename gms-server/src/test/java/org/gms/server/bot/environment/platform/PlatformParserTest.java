package org.gms.server.bot.environment.platform;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlatformParser / Platform 回归防线（不依赖 DB / 服务器）。
 * <p>
 * 解析测试用仓库内真实录制数据 {@code movementDataPackets/map910000000/m1.csv}
 * （测试进程 CWD = gms-server 模块根，即 surefire 默认工作目录）。PlatformParser
 * 的 BASE_PATH 是相对 CWD 的固定路径、无可注入 Path 的重载，故不构造临时 fixture，
 * 直接对真实数据做不变量断言（类型、点数、X 范围），避免依赖具体坐标值。
 */
class PlatformParserTest {

    /** 真实数据文件：模块根下 movementDataPackets，或从仓库根调用 mvn 时的 gms-server/ 前缀。 */
    private static Path resolveDataFile() {
        for (String base : new String[]{"movementDataPackets", "gms-server/movementDataPackets"}) {
            Path candidate = Paths.get(base, "map910000000", "m1.csv");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return Paths.get("movementDataPackets", "map910000000", "m1.csv");
    }

    @Test
    void parsePlatformFromRealRecordingHasSaneInvariants() {
        Path dataFile = resolveDataFile();
        assertTrue(Files.isRegularFile(dataFile),
                "真实数据文件缺失（测试期望 CWD 下存在 movementDataPackets/map910000000/m1.csv）：" + dataFile);

        Platform platform = PlatformParser.parsePlatform(910000000, "m1");

        assertNotNull(platform, "真实数据解析应产出非 null 平台");
        assertTrue(platform.getType() == Platform.Type.FLAT || platform.getType() == Platform.Type.SLOPED,
                "平台类型应为 FLAT 或 SLOPED，实际：" + platform.getType());

        List<Point> points = platform.getSortedPoints();
        assertTrue(points.size() > 0, "真实数据应解析出至少 1 个坐标点");

        int minX = platform.getMinX();
        int maxX = platform.getMaxX();
        assertTrue(minX <= maxX, "minX 应不大于 maxX");
        for (Point p : points) {
            assertTrue(p.x >= minX && p.x <= maxX,
                    "所有坐标点 X 应落在 [minX,maxX]，违规点：" + p);
        }
    }

    @Test
    void organizeFlatPointsDetectsFlatAndUsesModeY() {
        // Y 方差 ≤ SLOPE_THRESHOLD(50) → FLAT；baseY = 众数（出现最多的 y=10）
        List<Point> points = Arrays.asList(
                new Point(30, 10),
                new Point(10, 12),
                new Point(20, 10),
                new Point(40, 11),
                new Point(20, 10)); // 与 (20,10) 重复，应去重

        Platform platform = PlatformParser.organizePlatform(points);

        assertEquals(Platform.Type.FLAT, platform.getType());
        assertEquals(10, platform.getMinX());
        assertEquals(40, platform.getMaxX());
        assertEquals(10, platform.getBaseY(), "FLAT baseY 应为 Y 众数");
        assertEquals(4, platform.getSortedPoints().size(), "重复点应去重");
        // 排序断言：X 升序
        List<Point> sorted = platform.getSortedPoints();
        for (int i = 1; i < sorted.size(); i++) {
            assertTrue(sorted.get(i - 1).x <= sorted.get(i).x, "sortedPoints 应按 X 升序");
        }
    }

    @Test
    void organizeSlopedPointsDetectsSlopedWhenYRangeExceedsThreshold() {
        // Y 极差 100 > SLOPE_THRESHOLD(50) → SLOPED
        List<Point> points = Arrays.asList(
                new Point(0, 0),
                new Point(50, 100),
                new Point(100, 50));

        Platform platform = PlatformParser.organizePlatform(points);

        assertEquals(Platform.Type.SLOPED, platform.getType());
        assertEquals(0, platform.getMinX());
        assertEquals(100, platform.getMaxX());
        assertEquals(50, platform.getBaseY(), "SLOPED baseY 为平均 Y（仅参考值）");
    }

    @Test
    void getYAtXOnFlatAlwaysReturnsBaseY() {
        Platform flat = new Platform(0, 100, 42,
                Arrays.asList(new Point(0, 42), new Point(100, 42)), Platform.Type.FLAT);

        assertEquals(42, flat.getYAtX(-999), "FLAT 越界左仍返回 baseY");
        assertEquals(42, flat.getYAtX(0));
        assertEquals(42, flat.getYAtX(57));
        assertEquals(42, flat.getYAtX(999), "FLAT 越界右仍返回 baseY");
    }

    @Test
    void getYAtXOnSlopedInterpolatesAndClampsToBounds() {
        Platform sloped = new Platform(0, 100, 50,
                Arrays.asList(new Point(0, 0), new Point(100, 100)), Platform.Type.SLOPED);

        assertEquals(50, sloped.getYAtX(50), "中点应线性插值");
        assertEquals(0, sloped.getYAtX(0), "左端点取端点值");
        assertEquals(100, sloped.getYAtX(100), "右端点取端点值");
        assertEquals(0, sloped.getYAtX(-500), "越界左应 clamp 到 minX 的 Y");
        assertEquals(100, sloped.getYAtX(500), "越界右应 clamp 到 maxX 的 Y");
    }

    @Test
    void getYAtXOnSlopedWithEmptyPointsFallsBackToBaseY() {
        Platform slopedEmpty = new Platform(0, 100, 7, Collections.emptyList(), Platform.Type.SLOPED);

        assertEquals(7, slopedEmpty.getYAtX(50), "空点列表应回退 baseY");
    }
}
