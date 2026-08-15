package org.gms.net.server.channel.handlers;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.autoban.AutobanManager;
import org.gms.net.packet.InPacket;
import org.gms.net.server.Server;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.EventSubscriber;
import org.gms.server.bot.event.EventType;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.bot.messaging.ChatMessage;
import org.gms.server.bot.messaging.MessageQueue;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * GeneralChatHandler 的 Bot 集成点：非命令聊天发布 CHAT 事件（携带发送者与地图定位）、
 * 命令路径不发布、禁言地图不发布。
 * <p>
 * 经 BotTestSupport 注入 mock ApplicationContext（CommandsExecutor/I18nUtil/ChatLogger→GameConfig
 * 的静态初始化都依赖它），并 mockStatic Server.getInstance() 以绕开重量级单例。
 */
class GeneralChatHandlerBotTest {

    private static final int SENDER_ID = 4242;
    private static final int MAP_ID = 100000000;

    private final GeneralChatHandler handler = new GeneralChatHandler();

    private Client client;
    private Character chr;
    private MapleMap map;
    private InPacket inPacket;
    private MockedStatic<Server> serverStatic;

    private static final class ChatCollector implements EventSubscriber {
        final List<GameEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(GameEvent event) {
            events.add(event);
        }

        @Override
        public boolean matchesFilter(GameEvent event) {
            return event.getType() == EventType.CHAT;
        }
    }

    private ChatCollector collector;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        serverStatic = Mockito.mockStatic(Server.class);
        Server serverMock = Mockito.mock(Server.class);
        when(serverMock.getCurrentTime()).thenReturn(System.currentTimeMillis());
        serverStatic.when(Server::getInstance).thenReturn(serverMock);

        client = Mockito.mock(Client.class);
        chr = Mockito.mock(Character.class);
        map = Mockito.mock(MapleMap.class);
        inPacket = Mockito.mock(InPacket.class);

        when(client.getPlayer()).thenReturn(chr);
        when(client.getChannel()).thenReturn(1);
        when(chr.getClient()).thenReturn(client);
        when(chr.getId()).thenReturn(SENDER_ID);
        when(chr.getWorld()).thenReturn(0);
        when(chr.getMapId()).thenReturn(MAP_ID);
        when(chr.getMap()).thenReturn(map);
        when(chr.isGM()).thenReturn(false);
        when(chr.isHidden()).thenReturn(false);
        when(chr.getWhiteChat()).thenReturn(false);

        AutobanManager autobanManager = Mockito.mock(AutobanManager.class);
        // 拉黑窗口判定（getLastSpam(7) + 200 > currentServerTime()）恒为 false → 放行
        when(autobanManager.getLastSpam(anyInt())).thenReturn((long) (Integer.MIN_VALUE / 2));
        when(chr.getAutoBanManager()).thenReturn(autobanManager);

        when(map.isMuted()).thenReturn(false);
        when(inPacket.readByte()).thenReturn((byte) 0);

        collector = new ChatCollector();
        BotEventBus.getInstance().subscribe(EventType.CHAT, collector);
    }

    @AfterEach
    void tearDown() {
        BotEventBus.getInstance().reset();
        serverStatic.close();
    }

    @Test
    void normalChatPublishesChatEvent() {
        when(inPacket.readString()).thenReturn("大家好呀");

        handler.handlePacket(inPacket, client);

        assertEquals(1, collector.events.size(), "normal chat must publish exactly one CHAT event");
        GameEvent event = collector.events.get(0);
        assertEquals(EventType.CHAT, event.getType());
        assertEquals(0, event.getWorld());
        assertEquals(1, event.getChannel());
        assertEquals(MAP_ID, event.getMapId());
        assertEquals(SENDER_ID, event.getSourceCharacterId());
        assertEquals("大家好呀", event.getText());
    }

    @Test
    void commandMessageDoesNotPublishChatEvent() {
        when(inPacket.readString()).thenReturn("@help");
        // tryacquireClient 默认 false → 命令处理直接走 dropMessage 分支，不进聊天广播

        handler.handlePacket(inPacket, client);

        assertTrue(collector.events.isEmpty(), "command messages must not publish CHAT events");
    }

    @Test
    void mutedMapDoesNotPublishChatEvent() {
        when(inPacket.readString()).thenReturn("有人吗");
        when(map.isMuted()).thenReturn(true);

        handler.handlePacket(inPacket, client);

        assertTrue(collector.events.isEmpty(), "muted maps must not publish CHAT events");
    }

    @Test
    void hiddenGmChatDoesNotPublishChatEvent() {
        when(inPacket.readString()).thenReturn("隐身巡查中");
        when(chr.isHidden()).thenReturn(true);

        handler.handlePacket(inPacket, client);

        // 隐身 GM 的发言只对 GM 可见：进 bot 事件流会让 bot 公开应答，暴露 GM 隐身
        assertTrue(collector.events.isEmpty(), "hidden GM chat must not publish CHAT events");
    }

    // ── 聊天点名链路接线（primary 队列转发） ─────────────────────────────────
    // 修复回归防线：此前 primary 队列无生产者、CHAT 事件无订阅者，聊天点名交互
    //（SocialBot 菜单 / FollowerBot "train here" / 黑杰克 join）整体断线。
    // mockStatic(MessageQueue) 绕开真实单例（其 static 块会注册每 2s 轮询的 Dispatcher 线程，
    // 若直接断言真实队列内容会产生 2s 窗口竞态 flaky）。

    @Test
    void normalPlayerChatEnqueuesPrimaryQueue() {
        when(inPacket.readString()).thenReturn("云朵 train here");

        try (MockedStatic<MessageQueue> mq = Mockito.mockStatic(MessageQueue.class)) {
            MessageQueue queueMock = Mockito.mock(MessageQueue.class);
            mq.when(MessageQueue::getInstance).thenReturn(queueMock);

            handler.handlePacket(inPacket, client);

            // 捕获真实入队消息：断言 sender 为发送者本人、content 为原始聊天文本，
            // 而非仅验证「调过任意 ChatMessage 入队」这种过宽断言。
            ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
            Mockito.verify(queueMock, Mockito.times(1))
                    .addMessage(Mockito.eq("primary"), captor.capture());
            ChatMessage captured = captor.getValue();
            assertEquals(chr, captured.getSender(), "primary message sender must be the chatting player");
            assertEquals("云朵 train here", captured.getContent(), "primary message content must be the chat text");
        }
    }

    @Test
    void botChatNotEnqueuedToPrimaryQueue() {
        when(inPacket.readString()).thenReturn("大家好呀");
        // bot 区段 id + 注册进 BotStorage → BotHelpers.isBot 双判据命中
        int botId = BotHelpers.BOT_BASE_ID + 1000;
        when(chr.getId()).thenReturn(botId);

        try {
            // add 与 finally 的 remove 严格配对：注册失败不会泄漏到其他测试的 BotStorage 状态
            BotStorage.addActiveBot(botId, Mockito.mock(BotSM.class));
            try (MockedStatic<MessageQueue> mq = Mockito.mockStatic(MessageQueue.class)) {
                MessageQueue queueMock = Mockito.mock(MessageQueue.class);
                mq.when(MessageQueue::getInstance).thenReturn(queueMock);

                handler.handlePacket(inPacket, client);

                // bot 自己的聊天不回喂：防 bot 互相点名循环
                Mockito.verify(queueMock, Mockito.never())
                        .addMessage(Mockito.anyString(), Mockito.any(ChatMessage.class));
            }
        } finally {
            BotStorage.removeActiveBot(botId);
        }
    }

    @Test
    void hiddenGmChatNotEnqueuedToPrimaryQueue() {
        when(inPacket.readString()).thenReturn("隐身巡查中");
        when(chr.isHidden()).thenReturn(true);

        try (MockedStatic<MessageQueue> mq = Mockito.mockStatic(MessageQueue.class)) {
            MessageQueue queueMock = Mockito.mock(MessageQueue.class);
            mq.when(MessageQueue::getInstance).thenReturn(queueMock);

            handler.handlePacket(inPacket, client);

            Mockito.verify(queueMock, Mockito.never())
                    .addMessage(Mockito.anyString(), Mockito.any(ChatMessage.class));
        }
    }
}
