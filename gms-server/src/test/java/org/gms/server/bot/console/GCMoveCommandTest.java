package org.gms.server.bot.console;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.commands.gm4.GCMoveCommand;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.types.IdleBot;
import org.gms.test.BotTestSupport;
import org.gms.util.I18nUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * GCMoveCommand 子命令分发测试：usage 分支、bot 解析失败分支、以及无 map 依赖
 * 的安全后端调用（status/off/stop/turn/duck/jump/fidget/lod unload/route）。
 * bake/ropecheck/move/come 等需要真实地图几何，由 gcmove 包的引擎级测试覆盖，
 * 命令层只验证分发正确。
 */
class GCMoveCommandTest {

    private static final int BOT_ID = 2_100_000_002;
    private static final int GM_ID = 1_000_000;

    private MockedStatic<Server> serverMock;
    private Character player;
    private Client client;
    private Character botChar;
    private GCMoveCommand command;

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

        player = Mockito.mock(Character.class);
        Mockito.when(player.getId()).thenReturn(GM_ID);
        Mockito.when(player.getName()).thenReturn("GmTester");
        Mockito.when(player.getMapId()).thenReturn(0);
        Mockito.when(player.getPosition()).thenReturn(new Point(0, 0));
        client = Mockito.mock(Client.class);
        Mockito.when(client.getPlayer()).thenReturn(player);

        botChar = Mockito.mock(Character.class);
        Mockito.when(botChar.getId()).thenReturn(BOT_ID);
        Mockito.when(botChar.getName()).thenReturn("GcBot1");
        Mockito.when(botChar.getMapId()).thenReturn(0);
        Mockito.when(botChar.getPosition()).thenReturn(new Point(100, 100));
        Mockito.when(botChar.getMap()).thenReturn(null);
        storage.addPlayer(botChar);
        // isBot 双判据需要注册表命中：挂一个 IdleBot 占位（不启动）。
        BotStorage.addActiveBot(BOT_ID, new IdleBot(botChar));

        command = new GCMoveCommand();
    }

    @AfterEach
    void tearDown() {
        GCMovement.disable(botChar);
        BotStorage.removeActiveBot(BOT_ID);
        serverMock.close();
    }

    // =========================================================================
    // 顶层分发
    // =========================================================================

    @Test
    void noParamsShowsHelp() {
        command.execute(client, new String[]{});
        verify(player, atLeastOnce()).dropMessage(anyString());
    }

    @Test
    void explicitHelpShowsHelp() {
        command.execute(client, new String[]{"help"});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.header"));
    }

    @Test
    void lodUnloadDispatches() {
        command.execute(client, new String[]{"lod", "unload"});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.lodUnload", 0));
    }

    @Test
    void lodLoadDispatches() {
        command.execute(client, new String[]{"lod", "load", "5"});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.lodLoad", 0));
    }

    // =========================================================================
    // bot 解析失败分支
    // =========================================================================

    @Test
    void nonNumericBotIdShowsInvalid() {
        command.execute(client, new String[]{"status", "abc"});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.invalidBotId", "abc"));
    }

    @Test
    void unknownBotIdShowsNotFound() {
        command.execute(client, new String[]{"status", "123456"});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.noBot", 123456));
    }

    @Test
    void missingBotIdShowsUsage() {
        command.execute(client, new String[]{"move"});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.usage", "move"));
    }

    // =========================================================================
    // 无 map 依赖的子命令后端调用
    // =========================================================================

    @Test
    void statusReportsDynamicState() {
        command.execute(client, new String[]{"status", String.valueOf(BOT_ID)});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.status",
                "GcBot1", false, false, false, false));
        assertFalse(GCMovement.isEnabled(botChar));
    }

    @Test
    void offRemovesFromDynamicControl() {
        command.execute(client, new String[]{"off", String.valueOf(BOT_ID)});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.off", "GcBot1"));
        assertFalse(GCMovement.isEnabled(botChar));
    }

    @Test
    void stopCancelsIntent() {
        command.execute(client, new String[]{"stop", String.valueOf(BOT_ID)});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.stopped", "GcBot1"));
    }

    @Test
    void turnDuckJumpFidgetDoNotThrow() {
        command.execute(client, new String[]{"turn", String.valueOf(BOT_ID)});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.turns", "GcBot1"));

        command.execute(client, new String[]{"duck", String.valueOf(BOT_ID)});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.ducks", "GcBot1"));

        command.execute(client, new String[]{"jump", String.valueOf(BOT_ID)});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.jumps", "GcBot1"));

        command.execute(client, new String[]{"fidget", String.valueOf(BOT_ID), "off"});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.fidget", "GcBot1", "OFF"));
    }

    // =========================================================================
    // 参数不足 usage 分支
    // =========================================================================

    @Test
    void moveWithoutCoordinatesShowsUsage() {
        command.execute(client, new String[]{"move", String.valueOf(BOT_ID)});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.moveUsage"));
    }

    @Test
    void travelWithoutMapShowsUsage() {
        command.execute(client, new String[]{"travel", String.valueOf(BOT_ID)});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.travelUsage"));
    }

    @Test
    void routeWithoutMapShowsUsage() {
        command.execute(client, new String[]{"route", String.valueOf(BOT_ID)});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.routeUsage"));
    }

    @Test
    void routeWithUnknownMapReportsNoMap() {
        command.execute(client, new String[]{"route", String.valueOf(BOT_ID), "999999999"});
        // bot 无 map → routeReport 固定回 "GCTravel: no map."
        verify(player).dropMessage("GCTravel: no map.");
    }

    @Test
    void unknownSubcommandFallsBackToHelp() {
        command.execute(client, new String[]{"bogus", String.valueOf(BOT_ID)});
        verify(player).dropMessage(I18nUtil.getMessage("BotCommand.gcmove.header"));
    }
}
