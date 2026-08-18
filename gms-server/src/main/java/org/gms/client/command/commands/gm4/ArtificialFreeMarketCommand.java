package org.gms.client.command.commands.gm4;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.server.bot.DefaultBotServerAccess;
import org.gms.server.bot.freemarket.ArtificialFreeMarket;
import org.gms.util.I18nUtil;

/**
 * GM4 命令 !betafmshop：人工自由市场（FM）商店测试命令，逐子命令对照
 * SoloMapling ArtificialFreeMarketCommand 移植（补全缺失入口）：
 * <ul>
 *   <li>无参            —— 在 GM 脚下单点填充摊位（populateFreeMarketSpot）</li>
 *   <li>help            —— 命令列表</li>
 *   <li>destroy         —— 销毁所有商店（destroyAllShops）</li>
 *   <li>&lt;region&gt;  —— 填充整个 FM 区域（populateFreeMarketRegion）</li>
 *   <li>store/permit &lt;cid&gt; —— 授予 bot 玩家商店许可（BotPlayerStorePermit）</li>
 *   <li>botshop &lt;name&gt;    —— 在 GM 脚下创建 bot 商店（createBotShopAtLocation）</li>
 * </ul>
 */
@Slf4j
public class ArtificialFreeMarketCommand extends Command {
    {
        setDescription("Artificial FM Test.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            ArtificialFreeMarket.populateFreeMarketSpot(c);
            return;
        }
        if (params.length == 1) {
            switch (params[0].toLowerCase()) {
                case "help":
                    printHelp(player);
                    return;
                case "destroy":
                    ArtificialFreeMarket.destroyAllShops(c);
                    return;
                default:
                    ArtificialFreeMarket.populateFreeMarketRegion(params[0]);
                    return;
            }
        }
        if (params.length == 2) {
            if (isInteger(params[1])) {
                handleStringIntCommand(params[0], Integer.parseInt(params[1]), c);
            } else if (!isInteger(params[0])) {
                handleStringStringCommand(params[0], params[1], c);
            } else {
                player.yellowMessage(I18nUtil.getMessage("BotCommand.opq.notInt"));
            }
        }
    }

    private void printHelp(Character player) {
        player.yellowMessage(I18nUtil.getMessage("BotCommand.fmshop.header"));
        for (int i = 1; i <= 5; i++) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.fmshop.help" + i));
        }
    }

    /** store / permit <cid>：授予 bot 玩家商店许可。 */
    void handleStringIntCommand(String input, int input2, Client c) {
        Character player = c.getPlayer();
        Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(input2);
        if (fakechar == null) {
            player.yellowMessage(I18nUtil.getMessage("BotCommand.fmshop.botNull"));
            return;
        }

        switch (input.toLowerCase()) {
            case "store":
            case "permit":
                ArtificialFreeMarket.BotPlayerStorePermit(fakechar);
                break;
            default:
                player.yellowMessage(I18nUtil.getMessage("BotCommand.fmshop.invalid"));
                break;
        }
    }

    /** botshop <name>：在 GM 脚下创建 bot 商店。 */
    void handleStringStringCommand(String input, String input2, Client c) {
        switch (input.toLowerCase()) {
            case "botshop":
                ArtificialFreeMarket.createBotShopAtLocation(c.getPlayer().getPosition(), c.getPlayer().getMapId());
                break;
            default:
                c.getPlayer().yellowMessage(I18nUtil.getMessage("BotCommand.fmshop.invalid"));
                break;
        }
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
