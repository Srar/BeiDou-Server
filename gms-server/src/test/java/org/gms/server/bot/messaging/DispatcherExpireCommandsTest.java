package org.gms.server.bot.messaging;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.server.TimerManager;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.commands.SocialCommands;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Dispatcher 无 bot 名消息路径：respondant 消息在入 secondary 队列前必须
 * expirePlayerChatCommands 清除编号菜单气泡（对齐 SoloMapling Dispatcher:161-163）。
 */
class DispatcherExpireCommandsTest {

    private static final int MAP_ID = 100000000;

    private MockedStatic<SocialCommands> socialCommandsMock;
    private MessageQueue mq;
    private Dispatcher dispatcher;
    private Character sender;
    private MapleMap map;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
        // Dispatcher 构造器经 BotExecutors.ensureStarted 起 TimerManager/ThreadManager；
        // MessageQueue 静态块会构造全局 Dispatcher 并注册调度任务，故 TimerManager 必须先启动。
        TimerManager.getInstance().start();
    }

    @BeforeEach
    void setUp() {
        socialCommandsMock = Mockito.mockStatic(SocialCommands.class);
        mq = Mockito.mock(MessageQueue.class);
        dispatcher = new Dispatcher(mq);

        sender = Mockito.mock(Character.class);
        Mockito.when(sender.getId()).thenReturn(778_000_001);
        Mockito.when(sender.getName()).thenReturn("RealPlayer");
        Client client = Mockito.mock(Client.class);
        Mockito.when(sender.getClient()).thenReturn(client);

        map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getId()).thenReturn(MAP_ID);
        Mockito.when(map.getCharacters()).thenReturn(java.util.List.of()); // 无 bot 名 → 走无 bot 名分支
        Mockito.when(sender.getMap()).thenReturn(map);

        BotStorage.addPlayer(sender); // 注册为 respondant
    }

    @AfterEach
    void tearDown() {
        BotStorage.removePlayer(sender);
        dispatcher.shutdown();
        socialCommandsMock.close();
    }

    @Test
    void respondantMessageExpiresChatCommandsBeforeSecondaryEnqueue() {
        ChatMessage msg = new ChatMessage(sender, "继续对话");
        // 第一拍返回消息，第二拍返回 null 结束 drain 循环
        Mockito.when(mq.getMessageNonBlocking("primary")).thenReturn(msg, (ChatMessage) null);

        dispatcher.run();

        // 气泡清理先于入队（代码顺序：expire 后 addMessage），此处验证两者都发生
        socialCommandsMock.verify(() -> SocialCommands.expirePlayerChatCommands(sender));
        verify(mq).addMessage(msg); // 默认 secondary 队列
        verify(mq, never()).addMessage(eq("tertiary"), any());
    }

    @Test
    void inquirerMessageGoesToTertiaryWithoutExpiring() {
        BotStorage.removePlayer(sender);
        BotStorage.addInquirer(sender);
        ChatMessage msg = new ChatMessage(sender, "菜单选项");

        Mockito.when(mq.getMessageNonBlocking("primary")).thenReturn(msg, (ChatMessage) null);
        dispatcher.run();

        verify(mq).addMessage(eq("tertiary"), eq(msg));
        socialCommandsMock.verifyNoInteractions();
        BotStorage.removeInquirer(sender);
    }
}
