package org.gms.client.command.commands.gm6;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.Server;
import org.gms.server.bot.BotStorage;
import org.gms.test.BotTestSupport;
import org.gms.util.I18nUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GM6 命令 !bot：分支路由（spawn/list/count/start/stop/type/dc）的玩家可见文案。
 * 全部经 mock Client/Character；触达 DefaultBotServerAccess 的用例用 mockStatic Server
 * 且 getChannel 返回 null 短路（不回落到真实 Server 单例）；resolveBotWorld/Channel
 * 走空 GameConfig（回落 0/1）。参数已由 CommandsExecutor 小写化，直接传小写。
 */
class BotCommandTest {

    private BotCommand command;
    private Client client;
    private Character player;
    private MockedStatic<Server> serverStatic;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        // 兜底隔离：任何用例触达 DefaultBotServerAccess 都会落到 mock Server 且 channel 为空短路
        serverStatic = Mockito.mockStatic(Server.class);
        Server server = mock(Server.class);
        serverStatic.when(Server::getInstance).thenReturn(server);
        when(server.getChannel(anyInt(), anyInt())).thenReturn(null);

        command = new BotCommand();
        client = mock(Client.class);
        player = mock(Character.class);
        when(client.getPlayer()).thenReturn(player);
    }

    @AfterEach
    void tearDown() {
        serverStatic.close();
    }

    @Test
    void noParamsShowsUsage() {
        command.execute(client, new String[]{});

        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.message2"));
    }

    @Test
    void unknownSubcommandShowsUsage() {
        command.execute(client, new String[]{"frobnicate"});

        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.message2"));
    }

    @Test
    void supportedSubcommandForwardsToArtificialPlayerCommand() {
        // "help" 不在 gm6 的 7 个子命令内，应转发给 ArtificialPlayerCommand 并打印其帮助首行
        command.execute(client, new String[]{"help"});

        verify(player).yellowMessage("---- Bot Commands (!bot) ----");
    }

    @Test
    void countShowsTotal() {
        command.execute(client, new String[]{"count"});

        verify(player).yellowMessage(
                eq(I18nUtil.getMessage("BotCommand.message5", BotStorage.getActiveBotCount())));
    }

    @Test
    void listEmptyShowsHeader() {
        command.execute(client, new String[]{"list"});

        verify(player).yellowMessage(
                eq(I18nUtil.getMessage("BotCommand.message3", BotStorage.getActiveBotCount())));
    }

    @Test
    void spawnUnknownTypeShowsErrorAndTypes() {
        command.execute(client, new String[]{"spawn", "nope"});

        verify(player).yellowMessage(eq(I18nUtil.getMessage("BotCommand.message7", "nope")));
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.message15"));
    }

    @Test
    void convertMissingParamsShowsUsage() {
        command.execute(client, new String[]{"type", "970000001"});

        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.message2"));
    }

    @Test
    void convertUnknownTypeShowsErrorAndTypes() {
        command.execute(client, new String[]{"type", "970000001", "nope"});

        verify(player).yellowMessage(eq(I18nUtil.getMessage("BotCommand.message7", "nope")));
        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.message15"));
    }

    @Test
    void dcMissingParamsShowsUsage() {
        command.execute(client, new String[]{"dc"});

        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.message2"));
    }

    @Test
    void dcUnknownBotShowsNotFound() {
        // 内部走 DefaultBotServerAccess.INSTANCE.getCharacterById → Server.getInstance()；
        // mockStatic 的 getChannel 返回 null → getCharacterById 返回 null → message9
        command.execute(client, new String[]{"dc", "970000009"});

        verify(player).yellowMessage(eq(I18nUtil.getMessage("BotCommand.message9", 970_000_009)));
    }

    @Test
    void spawnNonNumericMapShowsUsage() {
        // spawn 首行取图（getChannel→null 短路）后 parseInt("abc") 抛 NumberFormatException → 用法提示
        command.execute(client, new String[]{"spawn", "social_bot", "abc"});

        verify(player).yellowMessage(I18nUtil.getMessage("BotCommand.message2"));
    }
}
