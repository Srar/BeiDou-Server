package org.gms.server.bot.types;

import org.gms.client.Character;
import org.gms.server.ItemInformationProvider;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.BotTiming;
import org.gms.server.bot.dialogue.BotDialogueHandler;
import org.gms.server.bot.dialogue.ConversationManager;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class DropGameSpectatorSystem {

    private static final String DIALOGUE_PATH = "DropGameSpectatorDialogue.yaml";
    private static final String BOT_TYPE = "DropGameSpectator";
    private static final Random random = new Random();

    private static final String[] CATEGORIES = {"Excitement", "Envy", "Hype", "Question", "Cheer"};
    private static final int[] CATEGORY_WEIGHTS = {30, 20, 20, 15, 15};
    private static final int WEIGHT_TOTAL = 100;

    private static final int COOLDOWN_MIN_DROPS = 7;
    private static final int COOLDOWN_MAX_DROPS = 12;

    private static int dropCounter = 0;
    private static int nextReactionAt = COOLDOWN_MIN_DROPS + new Random().nextInt(COOLDOWN_MAX_DROPS - COOLDOWN_MIN_DROPS + 1);
    private static int lastReactorId = -1;

    public static void onItemDropped(Character dropGameBot, Character player, int itemId) {
        dropCounter++;
        if (dropCounter < nextReactionAt) return;

        dropCounter = 0;
        nextReactionAt = COOLDOWN_MIN_DROPS + random.nextInt(COOLDOWN_MAX_DROPS - COOLDOWN_MIN_DROPS + 1);

        String itemName = ItemInformationProvider.getInstance().getName(itemId);
        if (itemName == null || itemName.isEmpty()) return;

        MapleMap map = dropGameBot.getMap();
        if (map == null) return;

        List<Character> spectators = findAvailableSpectators(map, dropGameBot);
        if (spectators.isEmpty()) return;

        Collections.shuffle(spectators, random);
        Character bot = pickDifferentBot(spectators);
        if (bot == null) return;

        lastReactorId = bot.getId();
        int delay = 1500 + random.nextInt(3500);

        BotTiming.after(delay, () -> {
            faceTowards(bot, player.getPosition());
            reactWithDialogue(bot, itemName);
        });
    }

    private static void faceTowards(Character bot, java.awt.Point target) {
        if (bot == null || target == null || bot.getPosition() == null) return;
        GCMovement.enable(bot);
        GCMovement.face(bot, target.x < bot.getPosition().x);
    }

    private static Character pickDifferentBot(List<Character> spectators) {
        if (spectators.size() == 1) return spectators.get(0);
        for (Character c : spectators) {
            if (c.getId() != lastReactorId) return c;
        }
        return spectators.get(0);
    }

    private static List<Character> findAvailableSpectators(MapleMap map, Character exclude) {
        List<Character> result = new ArrayList<>();
        for (Character chr : map.getAllPlayers()) {
            if (chr.getId() == exclude.getId()) continue;
            if (!BotHelpers.isBot(chr)) continue;
            BotSM bot = BotStorage.getBotById(chr.getId());
            if (bot == null || !bot.isAvailableForAmbientActions()) continue;
            if (ConversationManager.getInstance().isInConversation(chr.getId())) continue;
            result.add(chr);
        }
        return result;
    }

    private static void reactWithDialogue(Character bot, String itemName) {
        String category = selectWeightedCategory();

        BotDialogueHandler.DialogueConstructor dialog =
                BotDialogueHandler.getDialogueCon(DIALOGUE_PATH, BOT_TYPE, category);
        if (dialog == null || dialog.getDialogue().isEmpty()) return;

        List<String> lines = dialog.getDialogue();
        String line = lines.get(random.nextInt(lines.size()));
        line = line.replace("{item}", itemName);

        BotGameSupport.botSpeak(bot, line);

        List<Integer> emotes = dialog.getEmotes();
        if (emotes != null && !emotes.isEmpty()) {
            int emoteId = emotes.get(0);
            if (emoteId > 0 && random.nextInt(100) < 50) {
                BotGameSupport.botEmote(bot, emoteId);
            }
        }
    }

    private static String selectWeightedCategory() {
        int roll = random.nextInt(WEIGHT_TOTAL);
        int cumulative = 0;
        for (int i = 0; i < CATEGORIES.length; i++) {
            cumulative += CATEGORY_WEIGHTS[i];
            if (roll < cumulative) {
                return CATEGORIES[i];
            }
        }
        return CATEGORIES[CATEGORIES.length - 1];
    }
}
