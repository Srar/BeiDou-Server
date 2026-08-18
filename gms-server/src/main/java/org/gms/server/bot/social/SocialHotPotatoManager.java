package org.gms.server.bot.social;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.server.TimerManager;
import org.gms.server.bot.BotCustomization;
import org.gms.server.bot.BotExecutors;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.BotTiming;
import org.gms.server.bot.dialogue.BotDialogueHandler;
import org.gms.server.bot.dialogue.RecentLineGuard;
import org.gms.server.bot.dialogue.ConversationManager;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.replay.MovementCommands;
import org.gms.server.bot.town.TownPresenceConfig;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

import static org.gms.server.bot.commands.MegaphoneCommands.BotAvatarMegaphone;
import static org.gms.server.bot.commands.MegaphoneCommands.BotSuperMegaphone;
import static org.gms.server.bot.replay.MovementCommands.microTurnAround;
import static org.gms.server.bot.replay.MovementCommands.nudgeAwayFromOverlap;
import static org.gms.server.bot.replay.MovementCommands.nudgeSmall;

/**
 * 社交叫卖管理器（对应 SoloMapling SocialHotPotatoManager 1:1 移植）。
 * 让 Henesys 四图 + TownPresence.yaml 各城镇地图上的 filler bot 偶尔叫卖聊天/表情/换位/喇叭。
 */
@Slf4j
public class SocialHotPotatoManager {

    private static SocialHotPotatoManager instance;
    private ScheduledFuture<?> scheduledTask;
    private ScheduledFuture<?> megaScheduledTask;
    private final Random random = new Random();
    private boolean running = false;

    private static final int MIN_INTERVAL_MS = 8_000;
    private static final int MAX_INTERVAL_MS = 20_000;

    private static final int MEGA_MIN_INTERVAL_MS = 30_000;
    private static final int MEGA_MAX_INTERVAL_MS = 90_000;

    private static final int[] HENESYS_MAP_IDS = {
            100000000,  // Henesys
            100000100,  // Henesys Market
            100000200,  // Henesys Park
            100000102   // Henesys Potion Shop
    };

    private volatile Set<Integer> ambientMapIds = buildAmbientMapIds();

    private static final String SOCIAL_DIALOGUE_PATH = "SocialHotPotatoDialogue.yaml";
    private static final String SOCIAL_BOT_TYPE = "SocialHotPotato";
    private static final String MEGA_DIALOGUE_PATH = "MegaphoneDialogue.yaml";
    private static final String MEGA_BOT_TYPE = "MegaphoneBroadcast";

    private static final String[] SOCIAL_CATEGORIES = {
            "RealLife", "MapleInUniverse", "SocialSim", "AFK",
            "Nostalgia", "RandomOneLiners", "Complaints", "Reactions", "FlexBrag"
    };

    private static final String[] MEGA_CATEGORIES = {
            "BirthdayMessages", "ItemSales", "GuildRecruitment", "PQRecruitment",
            "RWTSpam", "Flex", "RandomAnnouncements", "SocialMessages"
    };

    private static final int[] MEGA_WEIGHTS = {
            5,   // BirthdayMessages
            7,   // ItemSales
            7,   // GuildRecruitment
            7,   // PQRecruitment
            19,  // RWTSpam
            19,  // Flex
            18,  // RandomAnnouncements
            18   // SocialMessages
    };
    private static final int MEGA_WEIGHT_TOTAL = 100;

    private SocialHotPotatoManager() {}

    public static synchronized SocialHotPotatoManager getInstance() {
        if (instance == null) {
            instance = new SocialHotPotatoManager();
        }
        return instance;
    }

    public void start() {
        if (running) return;
        running = true;
        BotExecutors.ensureStarted();
        refreshMapScope();
        scheduleNextTick();
        scheduleNextMegaTick();
        log.info("Started.");
    }

    public void refreshMapScope() {
        ambientMapIds = buildAmbientMapIds();
        log.info("Bark map scope: {} maps.", ambientMapIds.size());
    }

    private static Set<Integer> buildAmbientMapIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (int id : HENESYS_MAP_IDS) {
            ids.add(id);
        }
        ids.addAll(TownPresenceConfig.allTownMapIds());
        return ids;
    }

    public void stop() {
        running = false;
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        if (megaScheduledTask != null) {
            megaScheduledTask.cancel(false);
        }
        log.info("Stopped.");
    }

    private void scheduleNextTick() {
        if (!running) return;
        int delay = MIN_INTERVAL_MS + random.nextInt(MAX_INTERVAL_MS - MIN_INTERVAL_MS);
        scheduledTask = TimerManager.getInstance().schedule(() -> {
            try {
                tick();
            } catch (Exception e) {
                log.warn("Error during social hot-potato tick", e);
            }
            scheduleNextTick();
        }, delay);
    }

    private void scheduleNextMegaTick() {
        if (!running) return;
        int delay = MEGA_MIN_INTERVAL_MS + random.nextInt(MEGA_MAX_INTERVAL_MS - MEGA_MIN_INTERVAL_MS);
        megaScheduledTask = TimerManager.getInstance().schedule(() -> {
            try {
                megaTick();
            } catch (Exception e) {
                log.warn("Error during social hot-potato mega tick", e);
            }
            scheduleNextMegaTick();
        }, delay);
    }

    private void megaTick() {
        // 喇叭是全区可闻的（与发送者所在图无关），池不按观察过滤——从范围内任意合格 bot 抽一个发送者。
        Character bot = selectRandomFillerBot(false);
        if (bot == null) return;
        doMegaphone(bot);
    }

    private void tick() {
        // 叫卖是本地聊天/表情/椅子包，按观察门控：只从有真人的图里抽。
        Character bot = selectRandomFillerBot(true);
        if (bot == null) return;

        if (nudgeAwayFromOverlap(bot)) return;

        executeRandomAction(bot);
    }

    private Character selectRandomFillerBot(boolean requireObserved) {
        List<Character> fillerBots = new ArrayList<>();

        for (int mapId : ambientMapIds) {
            if (requireObserved && !GCMovement.isMapObserved(mapId)) continue;
            try {
                MapleMap map = Server.getInstance().getChannel(0, 1).getMapFactory().getMap(mapId);
                if (map == null) continue;
                for (Character chr : map.getAllPlayers()) {
                    if (!BotHelpers.isBot(chr)) continue;
                    BotSM bot = BotStorage.getBotById(chr.getId());
                    if (bot == null || !bot.isAvailableForAmbientActions()) continue;
                    if (ConversationManager.getInstance().isInConversation(chr.getId())) continue;
                    fillerBots.add(chr);
                }
            } catch (Exception e) {
                // Map not loaded yet, skip
            }
        }

        if (fillerBots.isEmpty()) return null;
        return fillerBots.get(random.nextInt(fillerBots.size()));
    }

    private void executeRandomAction(Character bot) {
        int roll = random.nextInt(100);

        if (roll < 45) {
            doChat(bot);
        } else if (roll < 62) {
            doEmote(bot);
        } else if (roll < 77) {
            doPositionChange(bot);
        } else if (roll < 92) {
            doEmoteAndChat(bot);
        } else {
            doChalkboard(bot);
        }
    }

    // --- Action handlers ---

    private void doChat(Character bot) {
        String category = SOCIAL_CATEGORIES[random.nextInt(SOCIAL_CATEGORIES.length)];
        String line = getRandomLine(SOCIAL_DIALOGUE_PATH, SOCIAL_BOT_TYPE, category);
        if (line != null) {
            botSpeak(bot, line);
        }
    }

    private void doEmote(Character bot) {
        int emoteId = 1 + random.nextInt(22);
        botEmote(bot, emoteId);
    }

    private void doPositionChange(Character bot) {
        int pick = random.nextInt(4);
        switch (pick) {
            case 0:
                if (bot.getChair() > 0) {
                    botCancelChair(bot);
                } else {
                    botSitChair(bot, BotCustomization.getRandomChairId());
                }
                break;
            case 1:
                microTurnAround(bot);
                break;
            case 2:
                botIdleStandingUpdate(bot);
                break;
            case 3:
                nudgeSmall(bot);
                break;
        }
    }

    private void doEmoteAndChat(Character bot) {
        int emoteId = 1 + random.nextInt(22);
        botEmote(bot, emoteId);
        String category = SOCIAL_CATEGORIES[random.nextInt(SOCIAL_CATEGORIES.length)];
        String line = getRandomLine(SOCIAL_DIALOGUE_PATH, SOCIAL_BOT_TYPE, category);
        if (line != null) {
            botSpeak(bot, line);
        }
    }

    private void doChalkboard(Character bot) {
        String line = getRandomLine(SOCIAL_DIALOGUE_PATH, SOCIAL_BOT_TYPE, "Chalkboard");
        if (line == null) return;
        botSetChalkboard(bot, line);
        BotTiming.after(5 * 60 * 1000, () -> botClearChalkboard(bot));
    }

    private void doMegaphone(Character bot) {
        String category = selectWeightedMegaCategory();
        String line = getRandomLine(MEGA_DIALOGUE_PATH, MEGA_BOT_TYPE, category);
        if (line == null) return;

        // 录制引擎接线（P5-H2）：复用 commands/MegaphoneCommands 已移植的喇叭实现，
        // 删除本类内联等价（superMegaphone/avatarMegaphone 与 4 行拆行工具已移除）。
        if (random.nextInt(100) < 75) {
            BotSuperMegaphone(bot, line);
        } else {
            BotAvatarMegaphone(bot, line);
        }
    }

    private String selectWeightedMegaCategory() {
        int roll = random.nextInt(MEGA_WEIGHT_TOTAL);
        int cumulative = 0;
        for (int i = 0; i < MEGA_CATEGORIES.length; i++) {
            cumulative += MEGA_WEIGHTS[i];
            if (roll < cumulative) {
                return MEGA_CATEGORIES[i];
            }
        }
        return MEGA_CATEGORIES[MEGA_CATEGORIES.length - 1];
    }

    public void testNudge(Character bot) {
        nudgeSmall(bot);
    }

    public boolean testNudgeOverlap(Character bot) {
        return nudgeAwayFromOverlap(bot);
    }

    // --- Dialogue loading ---

    /** 每对话包+分类记忆的最近条数（分类池 67-124 行，排除 10 条后仍充足）。 */
    private static final int RECENT_LINES = 10;

    private String getRandomLine(String dialoguePath, String botType, String category) {
        try {
            BotDialogueHandler.DialogueConstructor dialog =
                    BotDialogueHandler.getDialogueCon(dialoguePath, botType, category);
            if (dialog == null || dialog.getDialogue().isEmpty()) return null;
            List<String> lines = dialog.getDialogue();
            String key = "hp:" + dialoguePath + ":" + category;
            int idx = RecentLineGuard.pickIndex(key, lines.size(), RECENT_LINES, null);
            String line = lines.get(idx);
            RecentLineGuard.remember(key, idx, RECENT_LINES);
            return line;
        } catch (Exception e) {
            log.debug("SocialHotPotatoManager failed to load dialogue '{}'", dialoguePath, e);
            return null;
        }
    }

    // ── 等价实现（gms 无 SocialCommands；喇叭/移动原语已接线到移植类） ──
    // superMegaphone/avatarMegaphone → commands/MegaphoneCommands（BotSuperMegaphone/BotAvatarMegaphone）
    // microTurnAround/nudgeSmall/nudgeAwayFromOverlap → replay/MovementCommands 同名原语（录制回放）

    private static void botSpeak(Character character, String message) {
        if (character == null || character.getMap() == null) return;
        character.getMap().broadcastMessage(
                PacketCreator.getChatText(character.getId(), message, character.getWhiteChat(), 0));
    }

    private static void botEmote(Character character, int emote) {
        if (character == null || character.getMap() == null) return;
        character.getMap().broadcastMessage(PacketCreator.facialExpression(character, emote));
    }

    private static void botSetChalkboard(Character bot, String msg) {
        bot.setChalkboard(msg);
        if (bot.getMap() != null) {
            bot.getMap().broadcastMessage(PacketCreator.useChalkboard(bot, false));
        }
    }

    private static void botClearChalkboard(Character bot) {
        bot.setChalkboard(null);
        if (bot.getMap() != null) {
            bot.getMap().broadcastMessage(PacketCreator.useChalkboard(bot, true));
        }
    }

    /** 等价 MovementCommands.BotIdleStandingUpdate：gms 用 broadcastStance 广播站立帧。 */
    private static void botIdleStandingUpdate(Character bot) {
        if (bot == null || bot.getMap() == null) return;
        bot.broadcastStance();
    }

    private static void botSitChair(Character bot, int chairId) {
        if (bot == null) return;
        bot.sitChair(chairId);
    }

    private static void botCancelChair(Character bot) {
        if (bot == null) return;
        bot.sitChair(-1);
    }

    // microTurnAround/nudgeSmall/nudgeAwayFromOverlap 已接线到 replay/MovementCommands 同名原语
    //（static import 于类头）：microTurnAround 回放转身录制；nudgeSmall 走 BotMoveSmallDistanceX
    //（内部拿/放移动锁，锁被 gcmove 会话占住时安全 no-op）；nudgeAwayFromOverlap 重叠检测后
    // 同样走 BotMoveSmallDistanceX。原内联 setPosition+broadcastStance 近似已删除。
}
