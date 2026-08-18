package org.gms.server.bot.freemarket.shopoffer;

import org.gms.client.Character;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotTiming;
import org.gms.server.bot.dialogue.BotDialogueHandler;
import org.gms.server.maps.PlayerShop;
import org.gms.util.I18nUtil;
import org.gms.util.Randomizer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进店打招呼与砍价提示（移植自 SoloMapling FreeMarket.ShopOfferSystem.ShopOfferWelcome）。
 * <p>
 * 真人玩家进入 PRESENT 模式的 bot 店铺时，60% 概率在 15-20 秒后由店主打招呼；
 * 玩家在店内闲聊（非报价）满 2 条后，店主会提示报价玩法。
 */
public class ShopOfferWelcome {

    private static final String DIALOGUE_PATH = "ShopOfferDialogue.yaml";
    private static final String BOT_TYPE = "ShopOffer";

    private static final double WELCOME_CHANCE = 0.60;
    private static final int HINT_AFTER_MESSAGES = 2;

    private static final Map<String, Integer> playerMessageCounts = new ConcurrentHashMap<>();
    private static final Set<String> hintedPlayers = ConcurrentHashMap.newKeySet();

    public static void onPlayerEnterShop(PlayerShop shop, Character visitor) {
        if (!BotHelpers.isBot(shop.getOwner())) {
            return;
        }

        int ownerId = shop.getOwner().getId();
        ShopOfferSystem.ShopMode mode = ShopOfferSystem.getInstance().getOrAssignMode(ownerId);

        if (mode != ShopOfferSystem.ShopMode.PRESENT) {
            return;
        }
        if (Randomizer.nextDouble() >= WELCOME_CHANCE) {
            return;
        }

        int delay = 15000 + Randomizer.nextInt(5000);
        BotTiming.after(delay, () -> {
            if (visitor.getPlayerShop() != shop) {
                return;
            }
            String line = getWelcomeLine();
            if (line != null) {
                shop.chat(shop.getOwner(), line);
            }
        });
    }

    public static void onPlayerChat(Character player, PlayerShop shop, boolean offerParsed) {
        if (offerParsed) {
            return;
        }
        if (!BotHelpers.isBot(shop.getOwner())) {
            return;
        }

        int ownerId = shop.getOwner().getId();
        ShopOfferSystem.ShopMode mode = ShopOfferSystem.getInstance().getOrAssignMode(ownerId);
        if (mode != ShopOfferSystem.ShopMode.PRESENT) {
            return;
        }

        String key = ownerId + "_" + player.getId();
        if (hintedPlayers.contains(key)) {
            return;
        }

        int count = playerMessageCounts.merge(key, 1, Integer::sum);
        if (count >= HINT_AFTER_MESSAGES) {
            hintedPlayers.add(key);
            int delay = 2000 + Randomizer.nextInt(2000);
            BotTiming.after(delay, () -> {
                if (player.getPlayerShop() != shop) {
                    return;
                }
                shop.chat(shop.getOwner(), I18nUtil.getMessage("ShopOfferSystem.hint.tip"));
            });
        }
    }

    public static void clearShopData(int ownerId) {
        playerMessageCounts.entrySet().removeIf(e -> e.getKey().startsWith(ownerId + "_"));
        hintedPlayers.removeIf(k -> k.startsWith(ownerId + "_"));
    }

    private static String getWelcomeLine() {
        BotDialogueHandler.DialogueConstructor dialog =
                BotDialogueHandler.getDialogueCon(DIALOGUE_PATH, BOT_TYPE, "WelcomeResponse");
        if (dialog == null || dialog.getDialogue().isEmpty()) {
            return null;
        }
        List<String> lines = dialog.getDialogue();
        return lines.get(Randomizer.nextInt(lines.size()));
    }
}
