package org.gms.client.command.commands.gm4;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.server.bot.DefaultBotServerAccess;

import static org.gms.server.bot.commands.SocialCommands.BotSpeak;

/**
 * GM4 命令 !fmbot：FM Bot 测试命令。逐语义对齐 SoloMapling FMBotCommand。
 */
public class FMBotCommand extends Command {
    {
        setDescription("FmBot Commands.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            player.yellowMessage("No Command Parameter Found. Try !fmbot help");
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
            case "move":
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
            default:
                player.yellowMessage("Invalid command - handleStringIntCommand");
                break;
        }
    }

    private void handleStringStringCommand(String input, String input2, Client c) {
        Character player = c.getPlayer();
        switch (input.toLowerCase()) {
            case "Test":
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
            default:
                break;
        }
    }

    private void printHelp(Character player) {
        player.yellowMessage("---- FM Bot Commands (!fmbot) ----");
        player.yellowMessage("!fmbot chat <cid> <message>      - bot speaks in chat");
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
