package org.gms.server.bot.gcmove;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.server.bot.BotMapEntryResponder;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.awt.Point;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * GCMovementDriver.onMapChange 的「bot 到达被观察地图」分支单测：
 * {@code ObserverTracker.hasRealPlayerNow(map)} 为真（图上已有真人）时，
 * 必须在 markObservedNow 之后调用 {@link BotMapEntryResponder#onBotArrivedObserved(Character)}
 * 唤醒到达 bot 的宏观脑；无观察者时不得调用。
 * <p>
 * 静态状态用 mockStatic 钉死（ObserverTracker 分支、BotMapEntryResponder 调用记录），
 * 其余 onMapChange 逻辑（fhIndex 重建/落点/传送/warm）跑在真实合成平地上。
 */
class GCMovementDriverArriveNudgeTest {

    private static final int MAP_ID = 910000002;

    private MockedStatic<ObserverTracker> observerMock;
    private MockedStatic<BotMapEntryResponder> responderMock;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        observerMock = Mockito.mockStatic(ObserverTracker.class);
        responderMock = Mockito.mockStatic(BotMapEntryResponder.class);
    }

    @AfterEach
    void tearDown() {
        if (observerMock != null) {
            observerMock.close();
        }
        if (responderMock != null) {
            responderMock.close();
        }
    }

    private MapleMap makeFlatMap() {
        MapleMap map = new MapleMap(MAP_ID, 0, 1, 100000000, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        tree.insert(new Foothold(new Point(-1500, 100), new Point(1500, 100), 1));
        map.setFootholds(tree);
        return map;
    }

    @Test
    void onMapChangeNudgesMacroBrainWhenRealPlayerPresent() {
        MapleMap map = makeFlatMap();
        Character bot = Character.getDefault(new BotClient(0, 1));
        bot.setId(2_000_000_700);
        bot.setMap(map);
        bot.setPosition(new Point(0, 100));

        // 图上已有真人：进入 hasRealPlayerNow 分支；isActiveMap 钉 false 跳过广播段
        observerMock.when(() -> ObserverTracker.hasRealPlayerNow(any(MapleMap.class))).thenReturn(true);
        observerMock.when(() -> ObserverTracker.isActiveMap(anyInt())).thenReturn(false);

        GCMovementDriver.onMapChange(new BotMovementState(bot, null), bot);

        responderMock.verify(() -> BotMapEntryResponder.onBotArrivedObserved(bot), times(1));
    }

    @Test
    void onMapChangeSkipsNudgeWhenMapUnobserved() {
        MapleMap map = makeFlatMap();
        Character bot = Character.getDefault(new BotClient(0, 1));
        bot.setId(2_000_000_701);
        bot.setMap(map);
        bot.setPosition(new Point(0, 100));

        // 图上无真人：不进分支；isActiveMap 钉 false 跳过广播段
        observerMock.when(() -> ObserverTracker.hasRealPlayerNow(any(MapleMap.class))).thenReturn(false);
        observerMock.when(() -> ObserverTracker.isActiveMap(anyInt())).thenReturn(false);

        GCMovementDriver.onMapChange(new BotMovementState(bot, null), bot);

        responderMock.verify(() -> BotMapEntryResponder.onBotArrivedObserved(any(Character.class)), never());
    }
}
