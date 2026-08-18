package org.gms.server.bot.grind;

import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// SpotFinder.mapBotCapacity 背后的 WZ 位置聚类估计器的无头测试 —— DECIDE 使用的承载量数字必须
// 与 bot 实际能认领的挂机点数量一致。移植自 SoloMapling 的 SpotEstimateTest（纯内存构造
// MapMobIndex.SpawnPos，不读磁盘 WZ），断言语义保持不变。
class SpotEstimateTest {

    @BeforeAll
    static void initSupport() {
        // 必须最先调用：测试构造的 MapMobIndex.SpawnPos 所在类的加载链可能触碰 Spring 静态耦合，
        // 先注入 mock 应用上下文（幂等）。
        BotTestSupport.initialize();
    }

    // 一行等距 spawn 点：y 固定，x 从 fromX 到 toX、步长 step。
    private static List<MapMobIndex.SpawnPos> row(int y, int fromX, int toX, int step) {
        List<MapMobIndex.SpawnPos> pts = new ArrayList<>();
        for (int x = fromX; x <= toX; x += step) {
            pts.add(new MapMobIndex.SpawnPos(x, y, -1));
        }
        return pts;
    }

    @Test
    void emptyPositionsEstimateZero() {
        assertEquals(0, SpotFinder.estimateSpotCount(List.of()));
        assertEquals(0, SpotFinder.estimateSpotCount(null));
    }

    @Test
    void oneTightClusterIsOneSpot() {
        assertEquals(1, SpotFinder.estimateSpotCount(row(100, 0, 300, 30)));
    }

    @Test
    void distantClustersCountSeparately() {
        List<MapMobIndex.SpawnPos> pts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pts.add(new MapMobIndex.SpawnPos(i * 40, 0, -1));
            pts.add(new MapMobIndex.SpawnPos(2000 + i * 40, 0, -1));
        }
        assertEquals(2, SpotFinder.estimateSpotCount(pts));
    }

    @Test
    void verticallyStackedGroupsSplit() {
        // 合并度量里 dy 加权 x2.5：200px 的层叠读作 500 > 350 的合并阈值，因此上下两层各自成点。
        List<MapMobIndex.SpawnPos> pts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            pts.add(new MapMobIndex.SpawnPos(i * 50, 0, -1));
            pts.add(new MapMobIndex.SpawnPos(i * 50, 200, -1));
        }
        assertEquals(2, SpotFinder.estimateSpotCount(pts));
    }

    @Test
    void wideChainedRowTilesIntoMultipleSpots() {
        // 一条长链（间距 < 合并阈值）横跨 3000px，按 1000px 宽切成瓦片。
        assertEquals(3, SpotFinder.estimateSpotCount(row(-50, 0, 3000, 200)));
    }

    @Test
    void shareCapacityScalesWithWidthAndNeverDropsBelowSpotCount() {
        List<MapMobIndex.SpawnPos> tight = row(100, 0, 300, 30);
        assertTrue(SpotFinder.estimateShareCapacity(tight) >= SpotFinder.estimateSpotCount(tight));

        List<MapMobIndex.SpawnPos> wide = row(-50, 0, 3000, 200);
        int wideSpots = SpotFinder.estimateSpotCount(wide);
        int wideCap = SpotFinder.estimateShareCapacity(wide);
        assertTrue(wideCap > wideSpots);
        assertTrue(wideCap <= wideSpots * 4); // 每个点的容量受 SHARE_CAP_MAX 约束
    }

    // Monkey Swamp III（107000403）——暴露「被吞掉的平台」bug 的地图：44 个真实 WZ spawn 点，每个
    // 都带上它的 WZ foothold 分组。各向异性合并把两组层叠平台对（分组 33+34 与 26+29——后者距阈值
    // 仅 7px）链成一个簇；没有按岩架拆分时，每对平台只产生一个点：两个各有 6 个 spawn 的平台完全
    // 无法被认领，同时还冒出一个从双岩架簇横向切瓦切出来的 1-spawn 废点。每条为 {x, cy, fhGroup}。
    private static final int[][] MONKEY_SWAMP_3 = {
            // 底层地面链：分组 12（1 个游离点，折叠进主导组）、10（8 个）、11（4 个）
            {-1003, 125, 12},
            {-918, 120, 10}, {-900, 119, 10}, {-812, 119, 10}, {-777, 121, 10},
            {-651, 121, 10}, {-645, 122, 10}, {-563, 121, 10}, {-486, 121, 10},
            {-403, 111, 11}, {-227, 92, 11}, {-98, 94, 11}, {164, 127, 11},
            // 孤立中层平台：分组 22（6 个）
            {-989, -242, 22}, {-922, -240, 22}, {-822, -238, 22},
            {-759, -235, 22}, {-604, -237, 22}, {-595, -238, 22},
            // 合并进一个簇的层叠对：分组 34（6 个）+ 33（6 个）
            {-972, -839, 34}, {-867, -837, 34}, {-806, -840, 34},
            {-698, -838, 34}, {-616, -796, 34}, {-531, -781, 34},
            {-332, -717, 33}, {-243, -718, 33}, {-182, -721, 33},
            {-122, -717, 33}, {-62, -717, 33}, {47, -721, 33},
            // 合并进一个簇的层叠对：分组 29（6 个）+ 26（7 个）
            {-986, -540, 29}, {-920, -540, 29}, {-867, -537, 29},
            {-787, -538, 29}, {-691, -537, 29}, {-555, -538, 29},
            {-371, -422, 26}, {-324, -420, 26}, {-243, -417, 26},
            {-124, -418, 26}, {-46, -416, 26}, {-31, -417, 26}, {75, -418, 26},
    };

    private static List<MapMobIndex.SpawnPos> monkeySwamp(boolean withLedges) {
        List<MapMobIndex.SpawnPos> pts = new ArrayList<>();
        for (int[] p : MONKEY_SWAMP_3) {
            pts.add(new MapMobIndex.SpawnPos(p[0], p[1], withLedges ? p[2] : -1));
        }
        return pts;
    }

    @Test
    void monkeySwampWithoutLedgeDataReproducesTheOldMiss() {
        // 岩架盲 = P6 之前的行为：5 个点，两个 spawn 密集的平台不可见。
        assertEquals(5, SpotFinder.estimateSpotCount(monkeySwamp(false)));
    }

    @Test
    void monkeySwampLedgeSplitSurfacesTheSwallowedPlatforms() {
        // 按岩架拆分后：底层地面 2 个（游离点并入 8-spawn 长条）、孤立平台 1 个、
        // 两组层叠对各 2 个——且 1-spawn 废点消失（它回归分组 26）。
        assertEquals(7, SpotFinder.estimateSpotCount(monkeySwamp(true)));
    }
}
