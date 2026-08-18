package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.server.bot.gcmove.LodCounts;
import org.gms.server.bot.types.IdleBot;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BotMapEntryResponder 方向 B（onBotArrivedObserved）单测：
 * bot 到达被真人观察的地图时，宏观脑 nudgeSoon（150-700ms 抖动）的触发与门控语义。
 * <p>
 * 时序断言与 BotSMTest 同手法：注册时用 60s 初始延迟（测试窗口内不会被真实轮盘
 * 驱动），经 {@link BotTickService#nextDueMs} 观察下一次应触发时刻是否被拉前。
 */
class BotMapEntryResponderArriveNudgeTest {

    private static final int BASE_ID = 9_700_000;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(BASE_ID);

    private final int botId = NEXT_ID.incrementAndGet();

    private Character chr;
    private Client client;
    private MapleMap map;
    private IdleBot bot;
    private MockedStatic<LodCounts> lodCountsMock;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        chr = Mockito.mock(Character.class);
        Mockito.when(chr.getId()).thenReturn(botId);
        Mockito.when(chr.getName()).thenReturn("BotX" + botId);
        Mockito.when(chr.getWorld()).thenReturn(0);

        client = Mockito.mock(Client.class);
        Mockito.when(client.getChannel()).thenReturn(1);
        Mockito.when(chr.getClient()).thenReturn(client);

        map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getId()).thenReturn(100000000);
        Mockito.when(map.getCharacters()).thenReturn(Collections.emptyList());
        Mockito.when(chr.getMap()).thenReturn(map);

        // 与 BotSMTest 一致：钉死「观察轮未运行」，避免同 fork 其他测试先启动
        // ObserverTracker 造成顺序污染（本类用例不依赖观察轮状态）。
        lodCountsMock = Mockito.mockStatic(LodCounts.class);
        lodCountsMock.when(LodCounts::trackerRunning).thenReturn(false);

        bot = new IdleBot(chr);
    }

    @AfterEach
    void tearDown() {
        if (lodCountsMock != null) {
            lodCountsMock.close();
        }
        bot.setRunning(false);
        bot.stopScheduledTask();
        BotStorage.removeActiveBot(botId);
        BotTickService.unregister(botId);
    }

    @Test
    void onBotArrivedObservedPullsNextMacroTickForward() {
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        bot.startScheduledTask(60_000);
        Long before = BotTickService.nextDueMs(botId);
        assertNotNull(before);
        long now = System.currentTimeMillis();
        assertTrue(before >= now + 55_000, "expected far-future due, got " + (before - now) + "ms away");

        BotMapEntryResponder.onBotArrivedObserved(chr);

        Long after = BotTickService.nextDueMs(botId);
        assertNotNull(after);
        // 抖动窗口 150-700ms；留足轮盘 driver 100ms 扫描的余量，断言拉前到 ~1.2s 内
        assertTrue(after <= now + 1_200, "nudge must pull next due forward to ~now+[150,700], got "
                + (after - now) + "ms away");
        assertTrue(after < before, "nudge must move the next due earlier");
    }

    @Test
    void onBotArrivedObservedIgnoresNotRunningBot() {
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(false);
        bot.startScheduledTask(60_000);
        Long before = BotTickService.nextDueMs(botId);
        assertNotNull(before);

        BotMapEntryResponder.onBotArrivedObserved(chr);

        assertEquals(before, BotTickService.nextDueMs(botId), "not-running bot must not be nudged");
    }

    @Test
    void onBotArrivedObservedIgnoresBotNotInRegistry() {
        // 注册表中无此 bot（已销毁/从未注册）：静默忽略，不抛异常
        BotMapEntryResponder.onBotArrivedObserved(chr);
    }

    @Test
    void onBotArrivedObservedIgnoresNullBot() {
        BotMapEntryResponder.onBotArrivedObserved(null);
    }

    @Test
    void onBotArrivedObservedRespectsDebounceWindow() {
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        bot.startScheduledTask(60_000);

        BotMapEntryResponder.onBotArrivedObserved(chr);
        Long first = BotTickService.nextDueMs(botId);
        assertNotNull(first);

        // 去抖窗口（BotSM.NUDGE_DEBOUNCE_MS=1500）内：第二次 nudge 被拒，不再挪动 due
        BotMapEntryResponder.onBotArrivedObserved(chr);
        assertEquals(first, BotTickService.nextDueMs(botId),
                "debounced nudge must not move the next due again");
    }
}
