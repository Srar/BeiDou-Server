package org.gms.server.bot.console;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.server.bot.BotDebugHandler;
import org.gms.server.bot.BotGeneration;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.decorate.BotDecorateNX;
import org.gms.server.bot.decorate.BotDecorationQueue;
import org.gms.server.bot.messaging.MapleMessengerConsole;
import org.gms.server.bot.types.IdleBot;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * MapleMessengerConsole：executeCommand 命令表注册、连接态门控、botlog 集合、
 * decoqueue/decoratenx 静态开关，以及 BotDebugHandler → sendMMCLogToConnected
 * 转发链路。
 * <p>
 * 信使消息语法与源一致：「任意前缀冒号 + 命令文本」，如 {@code mmc:mmc connect}、
 * {@code mmc:decoqueue status}——冒号后第一个词是命令名，其余是参数。
 * getConsoleBot 用 mockStatic 置 null，避免单元测试环境创建真实 Console 傀儡角色
 *（需要频道注册与装饰链）。
 */
class MapleMessengerConsoleTest {

    private static final int GM_ID = 1_000_000;
    private static final int BOT_ID = 2_100_000_003;

    private MockedStatic<Server> serverMock;
    private MockedStatic<BotGeneration> botGenerationMock;
    private Character gm;
    private Client client;
    private Character botChar;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        serverMock = Mockito.mockStatic(Server.class);
        Server server = Mockito.mock(Server.class);
        Channel channel = Mockito.mock(Channel.class);
        PlayerStorage storage = new PlayerStorage();
        Mockito.when(Server.getInstance()).thenReturn(server);
        Mockito.when(server.getChannel(0, 1)).thenReturn(channel);
        Mockito.when(channel.getPlayerStorage()).thenReturn(storage);

        // 单元测试环境不创建真实 Console 傀儡（装饰/频道注册依赖过重）。
        botGenerationMock = Mockito.mockStatic(BotGeneration.class);
        botGenerationMock.when(BotGeneration::getConsoleBot).thenReturn(null);

        gm = Mockito.mock(Character.class);
        Mockito.when(gm.getId()).thenReturn(GM_ID);
        Mockito.when(gm.getName()).thenReturn("GmTester");
        client = Mockito.mock(Client.class);
        Mockito.when(gm.getClient()).thenReturn(client);
        Mockito.when(client.getPlayer()).thenReturn(gm);
        storage.addPlayer(gm);

        botChar = Mockito.mock(Character.class);
        Mockito.when(botChar.getId()).thenReturn(BOT_ID);
        Mockito.when(botChar.getName()).thenReturn("ConsoleBot1");
        Mockito.when(botChar.getMap()).thenReturn(null);
        storage.addPlayer(botChar);
        // 粉笔黑板命令需要注册表命中的真实 BotSM（IdleBot 占位，不启动）。
        BotStorage.addActiveBot(BOT_ID, new IdleBot(botChar));
    }

    @AfterEach
    void tearDown() {
        if (MapleMessengerConsole.isUserConnected(GM_ID)) {
            if (MapleMessengerConsole.isLoggingBot(BOT_ID)) {
                MapleMessengerConsole.executeCommand(gm, "mmc:botunlog " + BOT_ID);
            }
            MapleMessengerConsole.disconnectUser(gm);
        }
        BotStorage.removeActiveBot(BOT_ID);
        // 恢复装饰开关默认值
        BotDecorationQueue.ENABLED = true;
        BotDecorateNX.ENABLED = true;
        botGenerationMock.close();
        serverMock.close();
    }

    private void connectGm() {
        MapleMessengerConsole.executeCommand(gm, "mmc:mmc connect");
    }

    // =========================================================================
    // 命令表注册与消息入口
    // =========================================================================

    @Test
    void executeCommandParsesColonFormatAndRunsHelp() {
        MapleMessengerConsole.executeCommand(gm, "mmc:help");
        verify(gm, atLeastOnce()).sendPacket(any()); // help 文本经 sendConsoleMessage 发出
    }

    @Test
    void noColonIsIgnored() {
        MapleMessengerConsole.executeCommand(gm, "no colon here");
        verify(gm, never()).sendPacket(any());
    }

    @Test
    void allSubcommandsAreRegistered() {
        List<String> subcommands = List.of(
                "help", "mmc", "botlog", "botunlog", "chalkboard", "setallchalk",
                "removeallchalk", "resetbotlog", "cmd", "decoqueue", "decoratenx");
        for (String name : subcommands) {
            // 未连接时已注册命令会回「需要先连接」（mmc 回 usage、help 回列表）；
            // 未知命令则静默——用发包次数区分注册与否。
            clearInvocations(gm);
            MapleMessengerConsole.executeCommand(gm, "mmc:" + name);
            verify(gm, times(1)).sendPacket(any());
        }
    }

    @Test
    void unknownCommandSilentWhenDisconnected() {
        MapleMessengerConsole.executeCommand(gm, "mmc:bogus");
        verify(gm, never()).sendPacket(any());
    }

    @Test
    void unknownCommandEchoedWhenConnected() {
        connectGm();
        clearInvocations(gm);
        MapleMessengerConsole.executeCommand(gm, "mmc:bogus");
        verify(gm, times(1)).sendPacket(any());
    }

    // =========================================================================
    // 连接态门控
    // =========================================================================

    @Test
    void connectAndDisconnectToggleConnectionState() {
        assertFalse(MapleMessengerConsole.isUserConnected(GM_ID));
        connectGm();
        assertTrue(MapleMessengerConsole.isUserConnected(GM_ID));
        MapleMessengerConsole.executeCommand(gm, "mmc:mmc disconnect");
        assertFalse(MapleMessengerConsole.isUserConnected(GM_ID));
    }

    @Test
    void gatedCommandRejectedBeforeConnect() {
        MapleMessengerConsole.executeCommand(gm, "mmc:decoqueue status");
        // 未连接 → 仅回一条「需要先连接」
        verify(gm, times(1)).sendPacket(any());
    }

    @Test
    void gatedCommandRunsAfterConnect() {
        connectGm();
        clearInvocations(gm);
        MapleMessengerConsole.executeCommand(gm, "mmc:decoqueue status");
        verify(gm, times(1)).sendPacket(any()); // status 消息
    }

    // =========================================================================
    // botlog / 日志转发链路
    // =========================================================================

    @Test
    void botlogAndBotunlogToggleLoggingSet() {
        connectGm();
        MapleMessengerConsole.executeCommand(gm, "mmc:botlog " + BOT_ID);
        assertTrue(MapleMessengerConsole.isLoggingBot(BOT_ID));
        MapleMessengerConsole.executeCommand(gm, "mmc:botunlog " + BOT_ID);
        assertFalse(MapleMessengerConsole.isLoggingBot(BOT_ID));
    }

    @Test
    void botlogByNameResolvesCharacter() {
        connectGm();
        MapleMessengerConsole.executeCommand(gm, "mmc:botlog ConsoleBot1");
        assertTrue(MapleMessengerConsole.isLoggingBot(BOT_ID));
        MapleMessengerConsole.executeCommand(gm, "mmc:botunlog " + BOT_ID);
    }

    @Test
    void debugHandlerForwardsLogsToConnectedConsole() {
        connectGm();
        MapleMessengerConsole.executeCommand(gm, "mmc:botlog " + BOT_ID);
        assertTrue(MapleMessengerConsole.isLoggingBot(BOT_ID));

        clearInvocations(gm);
        new BotDebugHandler(botChar).debugLoggingFull("test-log-line");
        // 转发链路：isLoggingBot(botId) → sendMMCLogToConnected → GM 收彩字消息
        verify(gm, atLeastOnce()).sendPacket(any());
    }

    @Test
    void debugHandlerSilentWhenBotNotLogged() {
        connectGm();
        clearInvocations(gm);
        new BotDebugHandler(botChar).debugLoggingFull("test-log-line");
        verify(gm, never()).sendPacket(any());
    }

    // =========================================================================
    // decoqueue / decoratenx 静态开关
    // =========================================================================

    @Test
    void decoqueueToggleAndStatus() {
        connectGm();
        MapleMessengerConsole.executeCommand(gm, "mmc:decoqueue disable");
        assertFalse(BotDecorationQueue.ENABLED, "disable 后开关应为 false");

        // 禁用态 start 被拒绝
        clearInvocations(gm);
        MapleMessengerConsole.executeCommand(gm, "mmc:decoqueue start");
        verify(gm, times(1)).sendPacket(any());

        MapleMessengerConsole.executeCommand(gm, "mmc:decoqueue enable");
        assertTrue(BotDecorationQueue.ENABLED, "enable 后开关应为 true");

        MapleMessengerConsole.executeCommand(gm, "mmc:decoqueue stop");
        MapleMessengerConsole.executeCommand(gm, "mmc:decoqueue status");
        verify(gm, atLeastOnce()).sendPacket(any());
    }

    @Test
    void decoratenxToggleStatusAndCache() {
        connectGm();
        MapleMessengerConsole.executeCommand(gm, "mmc:decoratenx disable");
        assertFalse(BotDecorateNX.ENABLED, "disable 后开关应为 false");
        MapleMessengerConsole.executeCommand(gm, "mmc:decoratenx enable");
        assertTrue(BotDecorateNX.ENABLED, "enable 后开关应为 true");
        MapleMessengerConsole.executeCommand(gm, "mmc:decoratenx status");
        // 单元测试环境 EquipMetadataCache 未初始化 → cache 子命令回「未初始化」
        MapleMessengerConsole.executeCommand(gm, "mmc:decoratenx cache");
        verify(gm, atLeastOnce()).sendPacket(any());
    }

    // =========================================================================
    // cmd 转发
    // =========================================================================

    @Test
    void cmdDispatchesToCommandsExecutor() {
        connectGm();
        // 未加载命令表的 CommandsExecutor 对 !bot 回 unknown-command 提示，不抛异常即通过
        MapleMessengerConsole.executeCommand(gm, "mmc:cmd !bot list");
        verify(gm, atLeastOnce()).dropMessage(Mockito.anyInt(), ArgumentMatchers.anyString());
    }
}
