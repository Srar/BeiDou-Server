package org.gms.server.bot.console;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.commands.gm4.OPQCommands;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.types.opq.OPQBot;
import org.gms.server.bot.types.opq.OPQOrchestrator;
import org.gms.server.bot.types.opq.OPQSharedContext;
import org.gms.server.bot.types.opq.OPQSharedContext.OPQPhase;
import org.gms.server.maps.MapManager;
import org.gms.test.BotTestSupport;
import org.gms.util.I18nUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * OPQCommands 四个分发器（handleDirectCommand / handleNumberedCommand /
 * handleTwoStringCommand / handleStringIntStringCommand）的参数解析与后端调用。
 * 后端真实走 OPQOrchestrator 单例（resetForNewRun/mirrorPhase/shutdownRun/
 * assignPlatformTarget 等）；角色解析经 DefaultBotServerAccess，这里用
 * mockStatic(Server) + 真实 PlayerStorage 提供 bot 频道角色查找。
 */
class OPQCommandsTest {

    private static final int BOT_ID = 2_100_000_001;
    private static final int GM_ID = 1_000_000;

    private MockedStatic<Server> serverMock;
    private Character player;
    private Client client;
    private Character botChar;
    private OPQCommands command;
    private final List<OPQBot> createdBots = new ArrayList<>();

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
        // spawn 的 map 查找：MapManager mock 一律返回 null → 命令提示 mapNotFound。
        MapManager mapManager = Mockito.mock(MapManager.class);
        Mockito.when(channel.getMapFactory()).thenReturn(mapManager);
        Mockito.when(mapManager.getMap(anyInt())).thenReturn(null);

        player = Mockito.mock(Character.class);
        Mockito.when(player.getId()).thenReturn(GM_ID);
        Mockito.when(player.getName()).thenReturn("GmTester");
        Mockito.when(player.getMapId()).thenReturn(0);
        Mockito.when(player.getPosition()).thenReturn(new Point(0, 0));
        client = Mockito.mock(Client.class);
        Mockito.when(client.getPlayer()).thenReturn(player);

        botChar = Mockito.mock(Character.class);
        Mockito.when(botChar.getId()).thenReturn(BOT_ID);
        Mockito.when(botChar.getName()).thenReturn("OPQBot1");
        Mockito.when(botChar.getMapId()).thenReturn(0);
        Mockito.when(botChar.getPosition()).thenReturn(new Point(100, 100));
        Mockito.when(botChar.getParty()).thenReturn(null);
        Mockito.when(botChar.getMap()).thenReturn(null);
        storage.addPlayer(botChar);

        command = new OPQCommands();
    }

    @AfterEach
    void tearDown() {
        for (OPQBot bot : createdBots) {
            OPQOrchestrator.getInstance().unregisterBot(bot);
        }
        createdBots.clear();
        BotStorage.removeActiveBot(BOT_ID);
        OPQOrchestrator.getInstance().shutdownRun();
        serverMock.close();
    }

    private OPQBot registerBot() {
        OPQBot bot = new OPQBot(botChar);
        BotStorage.addActiveBot(BOT_ID, bot);
        createdBots.add(bot);
        return bot;
    }

    private OPQSharedContext ctx() {
        return OPQOrchestrator.getInstance().getSharedContext();
    }

    // =========================================================================
    // 分发器与参数解析
    // =========================================================================

    @Test
    void noParamsShowsUsage() {
        command.execute(client, new String[]{});
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.noparam"));
    }

    @Test
    void unknownDirectCommandShowsInvalid() {
        command.execute(client, new String[]{"bogus"});
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.invalid"));
    }

    @Test
    void numberedUnknownCidShowsBotNull() {
        command.execute(client, new String[]{"dump", "123456"});
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.botNull"));
    }

    @Test
    void numberedNonOpqCharShowsNotOpq() {
        // botChar 注册为普通 bot（无 OPQBot 包装）→ notOpq 提示
        command.execute(client, new String[]{"dump", String.valueOf(BOT_ID)});
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.notOpq", "OPQBot1"));
    }

    // =========================================================================
    // handleDirectCommand 后端调用
    // =========================================================================

    @Test
    void directStartCallsResetForNewRun() {
        command.execute(client, new String[]{"start"});
        assertTrue(ctx().isPqActive(), "start 后 pqActive 应为 true");
        assertEquals(OPQPhase.RECRUITMENT, ctx().getCurrentPhase());
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.start"));
    }

    @Test
    void directResetCallsShutdownRun() {
        command.execute(client, new String[]{"start"});
        command.execute(client, new String[]{"reset"});
        assertFalse(ctx().isPqActive(), "reset 后 pqActive 应为 false");
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.reset"));
    }

    @Test
    void directStatusPrintsPhaseAndCounts() {
        registerBot();
        command.execute(client, new String[]{"status"});
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.status",
                OPQPhase.INACTIVE, false, false, false));
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.registered", 1));
    }

    // =========================================================================
    // handleNumberedCommand 后端调用
    // =========================================================================

    @Test
    void numberedCompleteMarksTaskComplete() {
        registerBot();
        command.execute(client, new String[]{"complete", String.valueOf(BOT_ID)});
        assertTrue(ctx().isMyTaskComplete(BOT_ID), "complete 后任务完成位应为 true");
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.complete", "OPQBot1"));
    }

    @Test
    void numberedDumpPrintsState() {
        registerBot();
        command.execute(client, new String[]{"dump", String.valueOf(BOT_ID)});
        verify(player, atLeastOnce()).yellowMessage(anyString());
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.dumpHeader", "OPQBot1", BOT_ID));
    }

    @Test
    void numberedKillUnregistersAndClearsCompletion() {
        registerBot();
        command.execute(client, new String[]{"complete", String.valueOf(BOT_ID)});
        assertTrue(ctx().isMyTaskComplete(BOT_ID));

        command.execute(client, new String[]{"kill", String.valueOf(BOT_ID)});
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.kill", "OPQBot1"));
        assertFalse(ctx().isMyTaskComplete(BOT_ID), "kill 注销后任务完成位应被清除");
    }

    @Test
    void directKillallKillsAllRegisteredBots() {
        registerBot();
        command.execute(client, new String[]{"killall"});
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.killed", 1));
    }

    @Test
    void numberedSpawnWithoutMapShowsMapNotFound() {
        command.execute(client, new String[]{"spawn", "3"});
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.mapNotFound", 0));
    }

    // =========================================================================
    // handleTwoStringCommand 后端调用
    // =========================================================================

    @Test
    void twoStringPhaseMirrorsPhase() {
        command.execute(client, new String[]{"phase", "STAGE_1"});
        assertEquals(OPQPhase.STAGE_1, ctx().getCurrentPhase());
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.phaseForced", OPQPhase.STAGE_1));
    }

    @Test
    void twoStringUnknownPhaseShowsValidList() {
        command.execute(client, new String[]{"phase", "NOPE"});
        assertEquals(OPQPhase.INACTIVE, ctx().getCurrentPhase());
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.unknownPhase", "NOPE"));
    }

    @Test
    void twoStringForcestateallForcesEveryBot() {
        OPQBot bot = registerBot();
        command.execute(client, new String[]{"forcestateall", "RECRUITMENT"});
        assertEquals(OPQBot.OPQBotState.RECRUITMENT, bot.getOPQBotState());
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.forcedAll", 1, OPQBot.OPQBotState.RECRUITMENT));
    }

    @Test
    void twoStringForcestateallUnknownStateShowsError() {
        command.execute(client, new String[]{"forcestateall", "NOPE"});
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.unknownState", "NOPE"));
    }

    // =========================================================================
    // handleStringIntStringCommand 后端调用
    // =========================================================================

    @Test
    void stringIntStringForcestateSetsDebugState() {
        OPQBot bot = registerBot();
        command.execute(client, new String[]{"forcestate", String.valueOf(BOT_ID), "STAGE_1_LOOT"});
        assertEquals(OPQBot.OPQBotState.STAGE_1_LOOT, bot.getOPQBotState());
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.stateForced", "OPQBot1", OPQBot.OPQBotState.STAGE_1_LOOT));
    }

    @Test
    void stringIntStringAssignBoxPicksPlatform() {
        registerBot();
        command.execute(client, new String[]{"assign", String.valueOf(BOT_ID), "box"});
        assertNotNull(ctx().getMyPlatformAssignment(BOT_ID), "assign box 后应有平台分配");
        verify(player).yellowMessage(anyString());
    }

    @Test
    void stringIntStringAssignCloudWithNoMapReportsNullOid() {
        registerBot();
        command.execute(client, new String[]{"assign", String.valueOf(BOT_ID), "cloud"});
        // bot 无 map → assignCloudReactor 返回 null，消息应回显 oid=null
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.cloudAssigned", "OPQBot1", (Object) null));
    }

    @Test
    void stringIntStringAssignBadKindShowsError() {
        registerBot();
        command.execute(client, new String[]{"assign", String.valueOf(BOT_ID), "nope"});
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.opq.assignKind"));
    }
}
