package org.gms.client.command.commands.gm4;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.DefaultBotServerAccess;
import org.gms.server.bot.dialogue.BotFlavor;
import org.gms.server.bot.dialogue.ConversationManager;
import org.gms.server.bot.dialogue.FlavorAction;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.ReactorDropEntry;

import java.util.List;

import static org.gms.server.bot.commands.MapleMessengerCommands.botSendMessengerChat;
import static org.gms.server.bot.commands.MapleMessengerCommands.botTypingStatus;
import static org.gms.server.bot.commands.MapleMessengerCommands.sendMessengerInviteComplete;
import static org.gms.server.bot.commands.SocialCommands.BotSpeak;
import static org.gms.server.bot.gacha.CustomReactor.createReactorDropList;
import static org.gms.server.bot.gacha.CustomReactor.deleteReactor;
import static org.gms.server.bot.gacha.CustomReactor.gachaPop;
import static org.gms.server.bot.gacha.CustomReactor.getAllReactorsData;
import static org.gms.server.bot.gacha.CustomReactor.getNearestReactor;
import static org.gms.server.bot.gacha.CustomReactor.hitReactor;
import static org.gms.server.bot.gacha.CustomReactor.spawnReactor;
import static org.gms.server.bot.gacha.CustomReactor.sprayFromReactor;
import static org.gms.server.bot.gacha.CustomReactor.threeHitReactor;
import static org.gms.server.bot.itempool.GachaFillerSystem.createGachaListWithPrize;
import static org.gms.server.bot.replay.TestMethods.addMMC;

/**
 * GM4 命令 !test：Test Dev 测试命令（reactor / messenger / flavor / 事件等）。
 * 逐语义对齐 SoloMapling TestDevCommand。
 */
public class TestDevCommand extends Command {
    {
        setDescription("Template Commands Test.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            player.yellowMessage("No Command Parameter Found. Try !test help");
            return;
        }
        if (params.length == 1) {
            handleSingleInputCommand(params[0], c);
            return;
        }
        if (params.length == 2) {
            if (isInteger(params[1])) {
                int commandNum = Integer.parseInt(params[1]);
                handleStringIntCommand(params[0], commandNum, c);
            } else if (!isInteger(params[0]) && !isInteger(params[1])) {
                handleStringStringCommand(params[0], params[1], c);
            } else {
                player.yellowMessage("Second input not an integer");
            }
            return;
        }
        if (params.length == 3) {
            if (isInteger(params[1]) && isInteger(params[2])) {
                int commandNum = Integer.parseInt(params[1]);
                int commandNum2 = Integer.parseInt(params[2]);
                handleStringIntIntCommand(params[0], commandNum, commandNum2, c);
            } else if (isInteger(params[1])) {
                int commandNum = Integer.parseInt(params[1]);
                String commandString = params[2];
                handleStringIntStringCommand(params[0], commandNum, commandString);
            } else {
                player.yellowMessage("Second input not an integer");
            }
        }
    }

    private void handleSingleInputCommand(String input, Client c) {
        Character player = c.getPlayer();
        switch (input.toLowerCase()) {
            case "help":
                printHelp(player);
                break;
            case "addmmc":
                addMMC(c);
                break;
            case "getallreactors":
                getAllReactorsData(c.getPlayer());
                break;
            case "getnearestreactor":
                getNearestReactor(c.getPlayer());
                break;
            case "testconvo":
                player.yellowMessage("[ConversationManager] Triggering conversation on current map...");
                ConversationManager.getInstance().triggerOnMap(player);
                break;
            case "spawnreactor":
                spawnReactor(c.getPlayer());
                break;
            case "congrats":
                BotEventBus.getInstance().publish(levelUpEvent(player));
                player.yellowMessage("[flavor] published LEVEL_UP for you - nearby bots may congratulate in ~5-9s");
                break;
            case "flavor":
                forceFlavorOnMap(c, null);
                break;
            default:
                player.yellowMessage("Invalid command - Direct Command");
                break;
        }
    }

    private void handleStringIntIntCommand(String input, int input2, int input3, Client c) {
        Character player = c.getPlayer();
        Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null");
            return;
        }

        player.yellowMessage("Command: " + input2 + ", arg: " + input3);
        switch (input.toLowerCase()) {
            case "destroyreactor":
                deleteReactor(c.getPlayer().getMap(), input3);
                break;
            case "hitreactor":
                hitReactor(c.getPlayer().getMap(), input3);
                break;
            case "3hitreactor":
            case "breakreactor":
                threeHitReactor(c.getPlayer().getMap(), input3);
                break;
            case "sprayreactor":
                List<ReactorDropEntry> drops = createReactorDropList(List.of(1082223, 2022179, 1050018, 1082149, 1032026));
                sprayFromReactor(fakechar.getMap(), input3, drops, fakechar);
                break;
            default:
                player.yellowMessage("Invalid command - handleStringIntIntCommand");
                break;
        }
    }

    private void handleStringIntCommand(String input, int input2, Client c) {
        Character player = c.getPlayer();
        Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null for handleStringIntCommand");
            return;
        }

        switch (input.toLowerCase()) {
            case "Test":
                break;
            case "botmminvite":
            case "botmminv":
                sendMessengerInviteComplete(fakechar, c.getPlayer());
                break;
            case "botmmtyping":
                botTypingStatus(fakechar, true);
                break;
            case "botgacha":
                List<ReactorDropEntry> popDrops = createReactorDropList(createGachaListWithPrize(1082223));
                gachaPop(fakechar, popDrops);
                break;
            case "testevent":
                eventUnitTests(c.getPlayer());
                break;
            default:
                player.yellowMessage("Invalid command - handleStringIntCommand");
                break;
        }
    }

    private void handleStringStringCommand(String input, String input2, Client c) {
        Character player = c.getPlayer();
        switch (input.toLowerCase()) {
            case "flavor":
                forceFlavorOnMap(c, parseFlavorAction(input2, player));
                break;
            default:
                player.yellowMessage("Invalid command - handleStringIntCommand");
                break;
        }
    }

    private void handleStringIntStringCommand(String input, int input2, String str) {
        Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(input2);
        if (fakechar == null) {
            return;
        }

        switch (input.toLowerCase()) {
            case "chat":
                BotSpeak(fakechar, str);
                break;
            case "botmmchat":
                botSendMessengerChat(fakechar, str);
                break;
            default:
                break;
        }
    }

    private void forceFlavorOnMap(Client c, FlavorAction action) {
        Character p = c.getPlayer();
        int count = 0;
        // SoloMapling 用 PlatformPlacement.getAllCharsOnMap(mapId)（源 578-581）：
        // 取 bot 世界/频道的地图工厂（getMapleMapById -> channel(0,1)）遍历，而非 GM 自身频道的地图。
        // gms 等价走 DefaultBotServerAccess 的 bot world/channel 地图工厂。
        MapleMap botMap = DefaultBotServerAccess.INSTANCE.getMap(
                DefaultBotServerAccess.resolveBotWorld(), DefaultBotServerAccess.resolveBotChannel(), p.getMapId());
        if (botMap == null) {
            p.yellowMessage("[flavor] bot channel map not found for map " + p.getMapId());
            return;
        }
        for (Character chr : botMap.getAllPlayers()) {
            if (!org.gms.server.bot.BotHelpers.isBot(chr)) {
                continue;
            }
            BotSM bot = BotStorage.getBotById(chr.getId());
            if (bot == null) {
                continue;
            }
            if (action == null) {
                BotFlavor.forceExpress(bot);
            } else {
                BotFlavor.forceExpress(bot, action);
            }
            count++;
        }
        p.yellowMessage("[flavor] forced " + (action == null ? "random" : action) + " on " + count + " bot(s) on this map");
    }

    private FlavorAction parseFlavorAction(String s, Character player) {
        switch (s.toLowerCase()) {
            case "emote":
                return FlavorAction.EMOTE;
            case "buff":
            case "bufflex":
                return FlavorAction.BUFF_FLEX;
            case "swing":
            case "skill":
            case "skillswing":
                return FlavorAction.SKILL_SWING;
            default:
                player.yellowMessage("[flavor] unknown action '" + s + "' - using random");
                return null;
        }
    }

    private void printHelp(Character player) {
        player.yellowMessage("---- Test Dev Commands (!test) ----");
        player.yellowMessage("-- Utility --");
        player.yellowMessage("!test addmmc                     - add MMC test");
        player.yellowMessage("!test testconvo                  - trigger conversation on current map");
        player.yellowMessage("!test testevent <cid>            - run event unit tests");
        player.yellowMessage("-- Reactors --");
        player.yellowMessage("!test getallreactors             - dump all reactors data");
        player.yellowMessage("!test getnearestreactor          - get nearest reactor");
        player.yellowMessage("!test spawnreactor               - spawn reactor at your location");
        player.yellowMessage("!test destroyreactor <cid> <oid> - delete reactor");
        player.yellowMessage("!test hitreactor <cid> <oid>     - hit reactor once");
        player.yellowMessage("!test breakreactor <cid> <oid>   - three-hit reactor break");
        player.yellowMessage("!test sprayreactor <cid> <oid>   - spray drops from reactor");
        player.yellowMessage("-- Messenger --");
        player.yellowMessage("!test botmminvite <cid>          - send messenger invite");
        player.yellowMessage("!test botmmtyping <cid>          - send messenger typing status");
        player.yellowMessage("!test botmmchat <cid> <message>  - bot sends messenger chat");
        player.yellowMessage("-- VFX --");
        player.yellowMessage("!test botgacha <cid>             - test gacha drop pop");
        player.yellowMessage("-- Chat --");
        player.yellowMessage("!test chat <cid> <message>       - bot speaks in chat");
        player.yellowMessage("-- Bot Flavor --");
        player.yellowMessage("!test congrats                   - publish a level-up for YOU (nearby bots congratulate)");
        player.yellowMessage("!test flavor                     - force a random idle expression on all bots here");
        player.yellowMessage("!test flavor <emote|buff|swing>  - force that expression on all bots here");
    }

    public static void eventUnitTests(Character fakechar) {
        GameEvent event = levelUpEvent(fakechar);
        BotEventBus.getInstance().publish(event);
    }

    private static GameEvent levelUpEvent(Character chr) {
        return GameEvent.levelUp(chr.getWorld(), chr.getClient().getChannel(), chr.getMapId(), chr.getId());
    }

    private static boolean isInteger(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
