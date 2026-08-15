package org.gms.server.bot.gcmove;

import org.gms.client.Character;
import org.gms.server.bot.BotExecutors;
import org.gms.server.bot.BotLogic;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.dialogue.BotDialogueHandler;
import org.gms.util.PacketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

// Our own player-reaction layer for bots roaming on GCMovement: while a bot shares a map with a real
// player, it occasionally reacts to a nearby one - keep walking, emote/chat on the move, or stop, turn,
// and emote/chat. Reuses PlayerReaction's roll, but the STOP is non-blocking here (the old
// executeStopReaction sleeps for seconds and would stall the shared driver thread): it sets a short pause
// the driver honours by idling, faces the player, and fires the line async. Observed maps only.
//
// The spoken line comes from the bot's OWN dialogue (its "PlayerReaction" node), not a hardcoded file,
// so a TrainingBot speaks adventurer lines and any future GCMovement bot speaks its own. A per-player
// cooldown is shared across ALL bots so a single player isn't greeted on a loop by a passing crowd.
final class BotPlayerReaction {
    private BotPlayerReaction() {
    }

    private static final Logger log = LoggerFactory.getLogger(BotPlayerReaction.class);

    // The dialogue node every GCMovement bot uses to react to a nearby player.
    private static final String REACT_NODE = "PlayerReaction";
    // Fallback walk reaction (botSM == null) mirrors PlayerReaction's hardcoded HenesysBot dialogue.
    private static final String FALLBACK_DIALOGUE_PATH = "HenesysBotDialogue.yaml";
    private static final String FALLBACK_BOT_TYPE = "HenesysBot";

    // Detection box around the bot (centred). Matches the original pathFinderAware default (300x200).
    private static final int DETECT_WIDTH = 300;
    private static final int DETECT_HEIGHT = 200;
    // Reaction mix: ignore / stop-and-turn / react-while-walking. Adventurers mostly keep moving.
    private static final int IGNORE_WEIGHT = 55;
    private static final int STOP_WEIGHT = 15;
    private static final int WALK_WEIGHT = 30;
    // Don't scan every AI tick; a relaxed cadence is plenty and keeps it cheap.
    private static final long SCAN_INTERVAL_MS = 1_500;
    // How long a stop-reaction holds the bot in place (face + line play during this).
    private static final long STOP_PAUSE_MIN_MS = 900;
    private static final long STOP_PAUSE_MAX_MS = 2_500;
    // Once a player has been reacted to, no bot reacts to them again for this long (anti-spam, tied to
    // the player so map-hopping or a crowd of bots can't loop greetings at them).
    private static final long REACT_COOLDOWN_MIN_MS = 120_000; // 2 min
    private static final long REACT_COOLDOWN_MAX_MS = 240_000; // 4 min

    // playerId -> earliest time any bot may react to that player again. Shared across all bots.
    private static final Map<Integer, Long> PLAYER_REACT_UNTIL = new ConcurrentHashMap<>();

    // Three-way roll outcome. Mirrors SoloMapling PlayerReaction.ReactionType (not yet ported).
    private enum Reaction {
        IGNORE,
        STOP_REACT,
        WALK_REACT
    }

    // Throttled scan for a nearby real player; roll a reaction for the first one off cooldown.
    static void maybeReact(BotMovementState entry, Character bot) {
        long nowMs = System.currentTimeMillis();
        if (nowMs < entry.nextPlayerScanMs || entry.reactingUntilMs > nowMs) {
            return;
        }
        entry.nextPlayerScanMs = nowMs + SCAN_INTERVAL_MS;

        List<Character> players = BotLogic.getRealPlayersInRange(bot, DETECT_WIDTH, DETECT_HEIGHT);
        for (Character player : players) {
            if (player == null || onCooldown(player.getId(), nowMs)) {
                continue;
            }
            switch (rollReaction(IGNORE_WEIGHT, STOP_WEIGHT, WALK_WEIGHT)) {
                case IGNORE -> {
                    // no reaction, no cooldown: a lingering player can still earn one on a later scan
                }
                case WALK_REACT -> {
                    speak(bot, player);                 // line on the move, no movement change
                    markReacted(player.getId(), nowMs);
                    return;
                }
                case STOP_REACT -> {
                    reactStop(entry, bot, player);
                    markReacted(player.getId(), nowMs);
                    return;
                }
            }
        }
    }

    // Non-blocking stop-and-turn: face the player, broadcast the turn, hold the bot for a short beat (the
    // driver idles it while reactingUntilMs is in the future), and fire the line asynchronously.
    private static void reactStop(BotMovementState entry, Character bot, Character player) {
        Point pp = player.getPosition();
        Point bp = bot.getPosition();
        if (pp != null && bp != null) {
            entry.facingDir = (pp.x >= bp.x) ? 1 : -1;
            BotMovementManager.broadcastMovement(entry);
        }
        entry.reactingUntilMs = System.currentTimeMillis()
                + ThreadLocalRandom.current().nextLong(STOP_PAUSE_MIN_MS, STOP_PAUSE_MAX_MS + 1);
        speak(bot, player);
    }

    // Speak a reaction line from the bot's own dialogue (context-resolved, 80/20 plain-vs-token), off
    // the driver thread so the line's hold never stalls it. Bots without an FSM fall back to the old
    // generic emote/chat so the layer still works for any non-TrainingBot GCMovement mover.
    private static void speak(Character bot, Character player) {
        BotSM botSM = BotStorage.getBotById(bot.getId());
        if (botSM == null) {
            executeWalkReactionFallback(bot);
            return;
        }
        BotExecutors.runAsync(() -> botSM.getDialogueHandler()
                .executeBotContextDialogue(REACT_NODE, botSM, player, BotDialogueHandler.CONTEXT_LINE_CHANCE));
    }

    // Inline equivalent of SoloMapling PlayerReaction.executeWalkReaction (PlayerReaction not ported):
    // on a virtual thread, roll a random emote-or-chat walk reaction. The emote is a random ambient
    // emote from the shared HenesysBot "PlayerReaction" node; chat falls back to emote when no
    // token-free line resolves (there is no specific player here, so {PLAYER_*} lines drop).
    private static void executeWalkReactionFallback(Character bot) {
        BotExecutors.runAsync(() -> {
            try {
                if (bot.getMap() == null) {
                    return;
                }
                if (ThreadLocalRandom.current().nextInt(2) == 0) {
                    bot.getMap().broadcastMessage(PacketCreator.facialExpression(bot, randomAmbientEmote()));
                    return;
                }
                String line = BotDialogueHandler.getRandomResolvedLine(
                        FALLBACK_DIALOGUE_PATH, FALLBACK_BOT_TYPE, REACT_NODE, bot, null);
                if (line != null) {
                    bot.getMap().broadcastMessage(
                            PacketCreator.getChatText(bot.getId(), line, bot.getWhiteChat(), 0));
                } else {
                    bot.getMap().broadcastMessage(PacketCreator.facialExpression(bot, randomAmbientEmote()));
                }
            } catch (Throwable ignored) {
                log.debug("BotPlayerReaction fallback reaction failed for bot {}",
                        bot != null ? bot.getId() : "null", ignored);
            }
        });
    }

    // Random ambient emote for the fallback walk reaction (mirrors PlayerReaction.getRandomAmbientEmote:
    // loads the HenesysBot "PlayerReaction" node's emote palette once and falls back to 2).
    private static volatile List<Integer> ambientEmotes;

    private static int randomAmbientEmote() {
        List<Integer> emotes = ambientEmotes;
        if (emotes == null) {
            BotDialogueHandler.DialogueConstructor dialog =
                    BotDialogueHandler.getDialogueCon(FALLBACK_DIALOGUE_PATH, FALLBACK_BOT_TYPE, REACT_NODE);
            emotes = (dialog == null || dialog.getEmotes() == null) ? List.of() : dialog.getEmotes();
            ambientEmotes = emotes;
        }
        if (emotes.isEmpty()) {
            return 2;
        }
        return emotes.get(ThreadLocalRandom.current().nextInt(emotes.size()));
    }

    private static boolean onCooldown(int playerId, long nowMs) {
        Long until = PLAYER_REACT_UNTIL.get(playerId);
        if (until == null) {
            return false;
        }
        if (until <= nowMs) {
            PLAYER_REACT_UNTIL.remove(playerId); // expired, drop it so the map stays small
            return false;
        }
        return true;
    }

    private static void markReacted(int playerId, long nowMs) {
        PLAYER_REACT_UNTIL.put(playerId,
                nowMs + ThreadLocalRandom.current().nextLong(REACT_COOLDOWN_MIN_MS, REACT_COOLDOWN_MAX_MS + 1));
    }

    // Weighted three-way roll (1:1 with SoloMapling PlayerReaction.rollReaction).
    private static Reaction rollReaction(int ignoreWeight, int stopWeight, int walkWeight) {
        int total = ignoreWeight + stopWeight + walkWeight;
        int roll = ThreadLocalRandom.current().nextInt(total);
        if (roll < ignoreWeight) {
            return Reaction.IGNORE;
        } else if (roll < ignoreWeight + stopWeight) {
            return Reaction.STOP_REACT;
        } else {
            return Reaction.WALK_REACT;
        }
    }

}
