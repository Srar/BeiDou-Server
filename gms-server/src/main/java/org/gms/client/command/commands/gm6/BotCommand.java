package org.gms.client.command.commands.gm6;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.server.bot.BotGeneration;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.BotTypeManager;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.DefaultBotServerAccess;
import org.gms.server.maps.MapleMap;
import org.gms.util.I18nUtil;

/**
 * GM6 命令 !bot：人工玩家 Bot 的生命周期管理。
 * 子命令：spawn / list / count / start / stop / type / dc。
 */
public class BotCommand extends Command {
    {
        setDescription(I18nUtil.getMessage("BotCommand.message1"));
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 1) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.message2"));
            return;
        }
        switch (params[0]) {
            case "spawn" -> spawn(c, params);
            case "list" -> list(c);
            case "count" -> count(c);
            case "start" -> startStop(c, params, true);
            case "stop" -> startStop(c, params, false);
            case "type" -> convert(c, params);
            case "dc" -> dc(c, params);
            default -> player.yellowMessage(I18nUtil.getMessage("BotCommand.message2"));
        }
    }

    private void spawn(Client c, String[] params) {
        Character player = c.getPlayer();
        String typeName = params.length > 1 ? params[1] : "social_bot";
        BotTypeManager.BotType type;
        try {
            type = BotTypeManager.BotType.valueOf(typeName.toUpperCase());
        } catch (IllegalArgumentException e) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.message7", typeName));
            player.yellowMessage(I18nUtil.getMessage("BotCommand.message15"));
            return;
        }

        // 地图始终取自 bot 配置的 world/channel 的地图工厂：bot 注册在 bot.world/bot.channel，
        // 用 GM 所在频道的地图实例会造成跨频道错位（bot 频道玩家看不到、广播污染 GM 频道图）
        MapleMap map = DefaultBotServerAccess.INSTANCE.getMap(
                DefaultBotServerAccess.resolveBotWorld(), DefaultBotServerAccess.resolveBotChannel(), player.getMapId());
        if (params.length > 2) {
            try {
                int mapId = Integer.parseInt(params[2]);
                map = DefaultBotServerAccess.INSTANCE.getMap(
                        DefaultBotServerAccess.resolveBotWorld(), DefaultBotServerAccess.resolveBotChannel(), mapId);
            } catch (NumberFormatException e) {
                player.yellowMessage(I18nUtil.getMessage("BotCommand.message2"));
                return;
            }
        }
        if (map == null) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.message8", player.getMapId()));
            return;
        }

        try {
            int botId = BotGeneration.createBot(player.getPosition(), map);
            Character bot = DefaultBotServerAccess.INSTANCE.getCharacterById(botId);
            if (bot == null) {
                player.yellowMessage(I18nUtil.getMessage("BotCommand.message9", botId));
                return;
            }
            type.createAndSetBot(bot);
            BotTypeManager.manuallyStartBot(bot);
            player.yellowMessage(I18nUtil.getMessage("BotCommand.message6", bot.getName(), botId));
        } catch (RuntimeException e) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.message17"));
        }
    }

    private void list(Client c) {
        Character player = c.getPlayer();
        player.yellowMessage(I18nUtil.getMessage("BotCommand.message3", BotStorage.getActiveBotCount()));
        for (BotSM bot : BotStorage.getAllBots().values()) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.message4",
                    bot.getChr().getId(), bot.getChr().getName(), bot.getBotType(), bot.getState()));
        }
    }

    private void count(Client c) {
        c.getPlayer().yellowMessage(I18nUtil.getMessage("BotCommand.message5", BotStorage.getActiveBotCount()));
    }

    private void startStop(Client c, String[] params, boolean start) {
        Character player = c.getPlayer();
        if (params.length < 2) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.message2"));
            return;
        }
        if ("all".equals(params[1])) {
            if (start) {
                BotTypeManager.startAllBots();
            } else {
                BotTypeManager.stopAllBots();
            }
            player.yellowMessage(I18nUtil.getMessage(start ? "BotCommand.message10" : "BotCommand.message11",
                    I18nUtil.getMessage("BotCommand.message16")));
            return;
        }
        try {
            int botId = Integer.parseInt(params[1]);
            Character bot = DefaultBotServerAccess.INSTANCE.getCharacterById(botId);
            if (bot == null) {
                player.yellowMessage(I18nUtil.getMessage("BotCommand.message9", botId));
                return;
            }
            if (start) {
                BotTypeManager.manuallyStartBot(bot);
            } else {
                BotTypeManager.manuallyStopBot(bot);
            }
            player.yellowMessage(I18nUtil.getMessage(start ? "BotCommand.message10" : "BotCommand.message11", botId));
        } catch (NumberFormatException e) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.message2"));
        }
    }

    private void convert(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 3) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.message2"));
            return;
        }
        BotTypeManager.BotType type;
        try {
            type = BotTypeManager.BotType.valueOf(params[2].toUpperCase());
        } catch (IllegalArgumentException e) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.message7", params[2]));
            player.yellowMessage(I18nUtil.getMessage("BotCommand.message15"));
            return;
        }
        try {
            int botId = Integer.parseInt(params[1]);
            Character bot = DefaultBotServerAccess.INSTANCE.getCharacterById(botId);
            if (bot == null) {
                player.yellowMessage(I18nUtil.getMessage("BotCommand.message9", botId));
                return;
            }
            if (BotTypeManager.convertBotType(bot, type)) {
                player.yellowMessage(I18nUtil.getMessage("BotCommand.message12", botId, type.name()));
            } else {
                player.yellowMessage(I18nUtil.getMessage("BotCommand.message13", botId));
            }
        } catch (NumberFormatException e) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.message2"));
        }
    }

    private void dc(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 2) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.message2"));
            return;
        }
        try {
            int botId = Integer.parseInt(params[1]);
            Character bot = DefaultBotServerAccess.INSTANCE.getCharacterById(botId);
            if (bot == null) {
                player.yellowMessage(I18nUtil.getMessage("BotCommand.message9", botId));
                return;
            }
            BotGeneration.removeBotFromServer(bot);
            player.yellowMessage(I18nUtil.getMessage("BotCommand.message14", botId));
        } catch (NumberFormatException e) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.message2"));
        }
    }
}
