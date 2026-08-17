package org.gms.server.bot.types;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.bot.BotDebugHandler;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotTiming;
import org.gms.server.bot.BotTypeManager;
import org.gms.server.bot.dialogue.BotDialogueHandler;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.util.Randomizer;

import java.awt.Point;
import java.util.List;
import java.util.Random;

@Slf4j
public class HenesysJQBot extends BotSM {

    private enum JQState {
        RESET,
        NAVIGATE_TO_JQ,
        ATTEMPT_JQ,
        RECOVER,
        REST,
        NAVIGATE_TO_EXIT,
        CONVERT
    }

    private static final int PET_PARK_MAP = 100000202;
    private static final int HENESYS_PARK_MAP = 100000200;
    private static final int EXIT_PORTAL_ID = 5;
    private static final int EXIT_DEST_PORTAL_ID = 13;
    private static final Point JQ_START = new Point(-1005, 274);
    private static final int NEAR_START_THRESHOLD_X = 60;
    private static final int NEAR_START_THRESHOLD_Y = 20;

    private static final String[][] TIER_RECORDINGS = {
            {"jq1fail", "jq1afail"},     // Tier 1
            {"jq2fail", "jq2afail"},     // Tier 2
            {"jq3fail", "jq3afail"},     // Tier 3
            {"jq4fail", "jq4afail"},     // Tier 4
            {"jq5fail", "jq5afail"},     // Tier 5
            {"jq6fail", "jq6afail", "jq6bfail", "jq6cfail", "jq6dfail", "jq6efail"},     // Tier 6
            {"jq7top"}                    // Tier 7
    };

    private static final int[] TIER_WEIGHTS = {20, 18, 16, 14, 12, 8, 4};

    private static final int[] EXPERIENCED_TIER_WEIGHTS = {0, 0, 0, 0, 15, 25, 60};

    private static final double CONTINUE_JQ_CHANCE = 0.75;

    private final Random random = new Random();
    private JQState jqState = JQState.RESET;
    private int highestCompletedTier = 0;
    private boolean hasReachedTop = false;
    private boolean lastAttemptSuccess = false;

    public HenesysJQBot(Character character) {
        super(character);
        dialoguePath = "JQBotDialogue.yaml";
        botType = "JQBot";
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) return;
        // 每 tick 高频日志降级（s2）：debugLoggingFull 只收已格式化 String（无变参重载，
        // BotDebugHandler 不在本批修改范围），故先把文件日志开关判断前置，format 仅在
        // FILE_LOGGING_ENABLED=true 时发生（默认关闭：每 tick 省两次字符串拼接）。
        if (BotDebugHandler.isFileLoggingEnabled()) {
            getDebugger().debugLoggingFull(String.format("%s JQState: %s", getChr().getName(), jqState), String.format("%s", jqState));
        }

        switch (jqState) {
            case RESET:
                waitForRandom(2000, 17000); // startup stagger so cohorts don't climb in lockstep
                jqState = JQState.NAVIGATE_TO_JQ;
                break;
            case NAVIGATE_TO_JQ:
                navigateToJQStart();
                waitForRandom(200, 700); // human beat before starting the climb
                jqState = JQState.ATTEMPT_JQ;
                break;
            case ATTEMPT_JQ:
                attemptJQ();
                jqState = JQState.RECOVER;
                break;
            case RECOVER:
                boolean nearStart = recover();
                if (nearStart) {
                    jqState = JQState.ATTEMPT_JQ;
                } else {
                    jqState = JQState.REST;
                }
                break;
            case REST:
                restAndDecide();
                break;
            case NAVIGATE_TO_EXIT:
                chatLine("LeavingJQ");
                navigateToExit();
                jqState = JQState.CONVERT;
                break;
            case CONVERT:
                convertToHenesysBot();
                break;
        }
    }

    private void navigateToJQStart() {
        if (isNearJQStart()) {
            return; // Already at starting point, no need to path there again.
        }

        try {
            GCMovement.move(getChr(), JQ_START.x, JQ_START.y);
        } catch (Exception e) {
            log.warn("[HenesysJQBot] Failed to navigate to JQ start: " + e.getMessage());
        }
    }

    private void attemptJQ() {
        int selectedTier = rollNextTier();
        String[] variants = TIER_RECORDINGS[selectedTier - 1];
        String recordingName = variants[random.nextInt(variants.length)];
        lastAttemptSuccess = selectedTier == 7;

        // 每 tick 高频日志降级 debug：转换风暴时大量 bot 交错换型，info 会在
        // log4j2 同步 Appender 锁上排队 pin 住虚拟线程 carrier。
        log.debug("[HenesysJQBot] " + getChr().getName() + " attempting " + recordingName
                + " (tier=" + selectedTier + ", highest=" + highestCompletedTier + ")");

        if (Randomizer.nextInt(3) == 0) {
            BotTiming.after(500 + random.nextInt(2000), () -> chatLine("AttemptStart"));
        }

        if (Randomizer.nextInt(3) == 0) {
            scheduleMidJQChat();
        }

        try {
            // TODO(P5-H2): 源播放跳跳场录像 TIER_RECORDINGS[selectedTier-1]（getMovementRecording +
            // BotMoveStream）；gms 未移植录制引擎，改用 gcmove 图导航做一次近似攀爬替代。
            wanderOnPetPark();
        } catch (Exception e) {
            log.warn("[HenesysJQBot] Recording playback error: " + e.getMessage());
            return;
        }

        highestCompletedTier = selectedTier;
        waitForRandom(500, 1500); // settle beat before RECOVER ticks
    }

    private void wanderOnPetPark() {
        MapleMap map = getChr().getMap();
        if (map == null) return;
        List<GCMovement.Ledge> ledges = GCMovement.walkableLedges(map);
        if (ledges.isEmpty()) return;
        GCMovement.Ledge ledge = ledges.get(random.nextInt(ledges.size()));
        GCMovement.move(getChr(), ledge.centerX(), ledge.centerY());
    }

    private boolean recover() {
        if (lastAttemptSuccess) {
            hasReachedTop = true;
            chatLine("SuccessReaction");
            doEmote();
            int randomX = -1810 + random.nextInt(693);
            BotTiming.afterRandom(500, 1500, () -> {
                try {
                    GCMovement.move(getChr(), randomX, 274); // break up stack after successful jq finish.
                } catch (Exception e) {
                    log.warn("[HenesysJQBot] Failed to disperse after success: " + e.getMessage());
                }
            });
            waitFor(2000); // hold REST until the dispersal walk has kicked off
            return false;
        }

        boolean nearStart = isNearJQStart();

        if (!nearStart) {
            chatLine("FailReaction");
            if (Randomizer.nextInt(3) == 0) doEmote();
            waitForRandom(1000, 3000); // sulk beat before REST ticks
        } else {
            //I am at the start, just attempt it again.
            if (Randomizer.nextInt(3) == 0) {
                chatLine("FailReaction");
            }
            if (Randomizer.nextInt(5) == 0) doEmote();
        }
        return nearStart;
    }

    private void restAndDecide() {
        // rest chatter plays off-tick; the decision below is invisible until the
        // next tick, which the waitFor holds to the original 1.5-4s rest total
        BotTiming.chain()
                .pauseRandom(500, 1500)
                .run(() -> {
                    if (Randomizer.nextInt(3) == 0) chatLine("RestChat");
                    if (Randomizer.nextInt(5) == 0) doEmote();
                })
                .start();
        waitForRandom(1500, 4000);

        if (hasReachedTop) {
            if (random.nextDouble() < CONTINUE_JQ_CHANCE) {
                // 每 tick 高频日志降级 debug（转换风暴日志降级，见 attemptJQ 注释）。
                log.debug("[HenesysJQBot] " + getChr().getName() + " continuing JQ (experienced).");
                jqState = JQState.NAVIGATE_TO_JQ;
            } else {
                log.info("[HenesysJQBot] " + getChr().getName() + " done with JQ, converting.");
                jqState = JQState.NAVIGATE_TO_EXIT;
            }
        } else {
            jqState = JQState.NAVIGATE_TO_JQ; // todo investigate
        }
    }

    private int rollNextTier() {
        if (hasReachedTop) {
            return rollWeighted(EXPERIENCED_TIER_WEIGHTS);
        }

        int[] adjustedWeights = new int[TIER_WEIGHTS.length];
        for (int i = 0; i < TIER_WEIGHTS.length; i++) {
            adjustedWeights[i] = (i + 1 > highestCompletedTier) ? TIER_WEIGHTS[i] : 0;
        }
        return rollWeighted(adjustedWeights);
    }

    private int rollWeighted(int[] weights) {
        int totalWeight = 0;
        for (int w : weights) totalWeight += w;
        if (totalWeight == 0) return 7;

        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (roll < cumulative) return i + 1;
        }
        return 7;
    }

    private boolean isNearJQStart() {
        Point pos = getChr().getPosition();
        return Math.abs(pos.x - JQ_START.x) <= NEAR_START_THRESHOLD_X
                && Math.abs(pos.y - JQ_START.y) <= NEAR_START_THRESHOLD_Y;
    }

    private void scheduleMidJQChat() {
        int chatCount = random.nextInt(3);
        for (int i = 0; i < chatCount; i++) {
            long delay = 20000 + random.nextInt(10000);
            Character chr = getChr();
            BotTiming.after(delay, () -> {
                try {
                    BotDialogueHandler.DialogueConstructor dialog =
                            BotDialogueHandler.getDialogueCon(dialoguePath, botType, "MidJQ");
                    if (dialog != null && !dialog.getDialogue().isEmpty()) {
                        List<String> lines = dialog.getDialogue();
                        BotGameSupport.botSpeak(chr, lines.get(random.nextInt(lines.size())));
                    }
                } catch (Exception e) {
                    // dialogue load failed, skip
                }
            });
        }
    }

    // Deliberate synchronous exit script — the walk/warp steps have data-driven
    // durations, and CONVERT must never run before the bot has left Pet Park.
    private void navigateToExit() {
        try {
            moveToPortal(EXIT_PORTAL_ID);
            BotGameSupport.blockingSleep(1000 + random.nextInt(1000));
            GCMovement.travel(getChr(), HENESYS_PARK_MAP);
            BotGameSupport.blockingSleep(1000);
            log.info("[HenesysJQBot] " + getChr().getName() + " exited Pet Park.");
        } catch (Exception e) {
            log.warn("[HenesysJQBot] Failed to navigate to exit: " + e.getMessage());
        }
    }

    private void moveToPortal(int portalId) {
        MapleMap map = getChr().getMap();
        if (map == null) return;
        Portal portal = map.getPortal(portalId);
        if (portal == null || portal.getPosition() == null) return;
        GCMovement.move(getChr(), portal.getPosition().x, portal.getPosition().y);
    }

    private void convertToHenesysBot() {
        log.info("[HenesysJQBot] " + getChr().getName() + " converting to HenesysBot.");
        // JQ 冷却改在转换流程内设置（convertBotType 的 onConverted 回调直接拿到新实例），
        // 不再于 convert 返回后回读注册表——并发转换/替换会把 10 分钟冷却落到错误实例，
        // 闸门失效引发 ping-pong 加速。
        BotTypeManager.convertBotType(getChr(), BotTypeManager.BotType.HENESYS_BOT, newBot -> {
            if (newBot instanceof HenesysBot) {
                ((HenesysBot) newBot).setLastJQConversionTime(System.currentTimeMillis());
            }
        });
    }

    private void chatLine(String dialogueNode) {
        try {
            String line = BotDialogueHandler.getRandomResolvedLine(this, dialogueNode);
            if (line != null) BotGameSupport.botSpeak(getChr(), line);
        } catch (Exception e) {
            // dialogue node missing
        }
    }

    private void doEmote() {
        int emoteId = 1 + random.nextInt(7);
        BotGameSupport.botEmote(getChr(), emoteId);
    }

    @Override
    public void displayCommands(Character chr) {
        BotGameSupport.displayPlayerChatCommands(chr, List.of(getChr().getName()));
    }

    @Override
    public void processMessages() {}
}
