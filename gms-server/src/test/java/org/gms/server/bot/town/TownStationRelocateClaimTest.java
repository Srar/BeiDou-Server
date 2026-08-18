package org.gms.server.bot.town;

import org.gms.client.Character;
import org.gms.server.bot.gcmove.GCMovement;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TownStation.relocate 失败兜底：移动 abandon（无进展/不可达）时 onAbandon 回调必须
 * 兜底 claimSpot，避免 onArrival 被 GCMovement.abandonMove 丢弃后 ledge 空置/bot 堆叠。
 * <p>
 * GCMovement/TownPresenceSampler 用静态 mock 拦截：捕获 move 的两个回调并手动触发，
 * claimSpot 的真实注册（BotSpotClaims）验证兜底 claim 落地。
 */
class TownStationRelocateClaimTest {

    private static final int BOT_ID = 778_000_001;
    private static final int MAP_ID = 100000000;

    private Character bot;
    private MapleMap map;
    private MockedStatic<GCMovement> gcmovementMock;
    private MockedStatic<TownPresenceSampler> samplerMock;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        bot = Mockito.mock(Character.class);
        Mockito.when(bot.getId()).thenReturn(BOT_ID);
        Mockito.when(bot.getMapId()).thenReturn(MAP_ID);
        Mockito.when(bot.getPosition()).thenReturn(new Point(0, 100));

        map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getId()).thenReturn(MAP_ID);
        Mockito.when(bot.getMap()).thenReturn(map);

        gcmovementMock = Mockito.mockStatic(GCMovement.class);
        // claimSpot 依赖 regionIdAt ≥ 0：mock 默认返回 0（视为合成 ledge 0）
        samplerMock = Mockito.mockStatic(TownPresenceSampler.class);
        samplerMock.when(() -> TownPresenceSampler.sample(
                Mockito.any(MapleMap.class), Mockito.any(Point.class), Mockito.anyInt(), Mockito.any()))
                .thenReturn(List.of(new Point(500, 100)));
    }

    @AfterEach
    void tearDown() {
        TownStation.releaseSpot(bot);
        samplerMock.close();
        gcmovementMock.close();
    }

    @Test
    void relocatePassesArrivalAndAbandonClaimCallbacks() {
        boolean relocated = TownStation.relocate(bot, new Point(0, 0));

        assertTrue(relocated, "采样非空时应发出移动");
        ArgumentCaptor<Runnable> onArrival = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> onAbandon = ArgumentCaptor.forClass(Runnable.class);
        gcmovementMock.verify(() -> GCMovement.move(Mockito.eq(bot), Mockito.eq(500), Mockito.eq(100),
                onArrival.capture(), onAbandon.capture()));
        assertNotNull(onArrival.getValue(), "onArrival 回调必须传递");
        assertNotNull(onAbandon.getValue(), "onAbandon 兜底回调必须传递");
        assertFalse(TownStation.isStationed(bot), "移动未完成前不应提前 claim");
    }

    @Test
    void abandonCallbackFallsBackToClaimCurrentLedge() {
        TownStation.relocate(bot, new Point(0, 0));

        ArgumentCaptor<Runnable> onAbandon = ArgumentCaptor.forClass(Runnable.class);
        gcmovementMock.verify(() -> GCMovement.move(Mockito.eq(bot), Mockito.eq(500), Mockito.eq(100),
                Mockito.any(), onAbandon.capture()));

        onAbandon.getValue().run(); // 模拟驱动层 abandon（不可达/无进展）

        assertTrue(TownStation.isStationed(bot), "移动失败后 onAbandon 应兜底 claim 当前 ledge");
    }

    @Test
    void arrivalCallbackClaimsDestinationLedge() {
        TownStation.relocate(bot, new Point(0, 0));

        ArgumentCaptor<Runnable> onArrival = ArgumentCaptor.forClass(Runnable.class);
        gcmovementMock.verify(() -> GCMovement.move(Mockito.eq(bot), Mockito.eq(500), Mockito.eq(100),
                onArrival.capture(), Mockito.any()));

        onArrival.getValue().run(); // 模拟到达目标点

        assertTrue(TownStation.isStationed(bot), "移动到达后 onArrival 应 claim 目标 ledge");
    }
}
