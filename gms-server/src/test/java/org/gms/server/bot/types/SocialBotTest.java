package org.gms.server.bot.types;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.EventType;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.awt.Point;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * SocialBot：观察门槛（无人看不动）、被观察后主动动作（台词/表情广播）、
 * CHAT 事件应答与冷却、事件过滤（不同世界/地图不入队）。
 */
class SocialBotTest {

    private static final int BOT_ID = 20101;
    private static final int MAP_ID = 100000000;

    private Character chr;
    private Client client;
    private MapleMap map;
    private SocialBot bot;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        chr = Mockito.mock(Character.class);
        Mockito.when(chr.getId()).thenReturn(BOT_ID);
        Mockito.when(chr.getName()).thenReturn("SocialBot");
        Mockito.when(chr.getWorld()).thenReturn(0);
        Mockito.when(chr.getPosition()).thenReturn(new Point(0, 0));
        Mockito.when(chr.getStance()).thenReturn(0);
        Mockito.when(chr.getWhiteChat()).thenReturn(false);
        Mockito.when(chr.getMapId()).thenReturn(MAP_ID);

        client = Mockito.mock(Client.class);
        Mockito.when(client.getChannel()).thenReturn(1);
        Mockito.when(chr.getClient()).thenReturn(client);

        map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getId()).thenReturn(MAP_ID);
        Mockito.when(map.getCharacters()).thenReturn(Collections.emptyList());
        Mockito.when(chr.getMap()).thenReturn(map);

        bot = new SocialBot(chr);
        BotStorage.addActiveBot(BOT_ID, bot);
        bot.setRunning(true);
    }

    @AfterEach
    void tearDown() {
        bot.setRunning(false);
        bot.stopScheduledTask(); // 退订事件 + 取消轮盘
        BotStorage.removeActiveBot(BOT_ID);
        BotEventBus.getInstance().reset();
    }

    @Test
    void unobservedBotDoesNotAct() {
        bot.updateState();
        bot.updateState();

        verify(map, never()).broadcastMessage(any());
    }

    @Test
    void observedBotActsOncePerGap() {
        Character player = Mockito.mock(Character.class);
        Mockito.when(player.getId()).thenReturn(5); // 真实玩家
        Mockito.when(map.getCharacters()).thenReturn(List.of(player));

        bot.updateState(); // 首次：间隔门控通过（lastAction=0）→ 台词或表情广播一次
        verify(map, times(1)).broadcastMessage(any());

        bot.updateState(); // 间隔未到：不再动作
        verify(map, times(1)).broadcastMessage(any());
    }

    @Test
    void chatEventTriggersReplyOncePerCooldown() {
        // 发布同图真实玩家聊天 → 事件入队 → tick 内应答（冷却窗口内仅一次）。
        // 注意：地图无真实玩家（getCharacters 为空），主动动作被观察门槛挡住，
        // 因此这里的 broadcast 全部来自应答——动作间隔门控在本用例中不参与。
        BotEventBus.getInstance().publish(GameEvent.chat(0, 1, MAP_ID, 5, "你好"));

        bot.updateState();
        verify(map, atLeast(1)).broadcastMessage(any());

        BotEventBus.getInstance().publish(GameEvent.chat(0, 1, MAP_ID, 5, "还在吗？"));
        bot.updateState();

        // 第二次消息在 20s 冷却内：不应新增广播
        verify(map, times(1)).broadcastMessage(any());
    }

    @Test
    void selfChatDoesNotTriggerReply() {
        BotEventBus.getInstance().publish(GameEvent.chat(0, 1, MAP_ID, BOT_ID, "自言自语"));

        bot.updateState();

        verify(map, never()).broadcastMessage(any());
    }

    @Test
    void pausedBotDoesNotActOrReply() {
        // 空图 + PAUSE：基类保持 PAUSE（无人进图不恢复），子类不应答不动作
        bot.setState(BotSM.BotState.PAUSE);
        BotEventBus.getInstance().publish(GameEvent.chat(0, 1, MAP_ID, 5, "你好"));

        bot.updateState();

        assertEquals(BotSM.BotState.PAUSE, bot.getState());
        verify(map, never()).broadcastMessage(any());
    }

    @Test
    void stopThenStartKeepsReplying() {
        // stopScheduledTask 退订、onScheduledStart 重订阅的回归防线
        bot.stopScheduledTask();
        assertEquals(0, BotEventBus.getInstance().subscriberCount(EventType.CHAT),
                "stopping must unsubscribe");

        bot.startScheduledTask(60_000);
        assertEquals(1, BotEventBus.getInstance().subscriberCount(EventType.CHAT),
                "re-starting must re-subscribe the CHAT listener");

        BotEventBus.getInstance().publish(GameEvent.chat(0, 1, MAP_ID, 5, "你好"));
        bot.updateState();
        verify(map, atLeast(1)).broadcastMessage(any());
    }

    @Test
    void chatEventFromOtherWorldIsFiltered() {
        BotEventBus.getInstance().publish(GameEvent.chat(1, 1, MAP_ID, 5, "你好"));

        bot.updateState();

        verify(map, never()).broadcastMessage(any());
    }

    @Test
    void chatEventFromOtherMapIsFiltered() {
        BotEventBus.getInstance().publish(GameEvent.chat(0, 1, 100000001, 5, "你好"));

        bot.updateState();

        verify(map, never()).broadcastMessage(any());
    }

    @Test
    void subscribedToChatEvents() {
        // 相对断言：不依赖全 JVM 只有本 bot 一个订阅者（防并行测试/其他订阅者误报）
        int baseline = BotEventBus.getInstance().subscriberCount(EventType.CHAT);
        assertTrue(baseline >= 1, "setUp must have subscribed this bot");

        bot.stopScheduledTask();
        assertEquals(baseline - 1, BotEventBus.getInstance().subscriberCount(EventType.CHAT),
                "stopScheduledTask must unsubscribe the bot from the event bus");
    }
}
