package org.gms.server.bot.types;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.bot.BotExecutors;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotTypeManager;
import org.gms.server.bot.dialogue.BotDialogueHandler;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.messaging.ChatMessage;
import org.gms.server.bot.messaging.MessageQueue;
import org.gms.server.maps.MapleMap;
import org.gms.util.I18nUtil;
import org.gms.util.Randomizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.gms.server.bot.environment.platform.PlatformPlacement.botMoveToPlatformAnyUnoccupiedSpotAware;
import static org.gms.server.bot.environment.platform.PlatformPlacement.getCurrentPlatform;
import static org.gms.server.bot.environment.platform.PlatformPlacement.getMainPlatformIds;
import static org.gms.server.bot.freemarket.BotRand.getRandomElement;
import static org.gms.server.bot.replay.MovementCommands.nudgeAwayFromOverlap;

@Slf4j
public class HenesysBot extends BotSM {
    private HenesysBotState henesysBotState = HenesysBotState.RESET;
    private List<String> hint = Collections.singletonList(getChr().getName());

    private long startTime;
    private long endTime;
    private long lastMapChangeTime;

    // Henesys Map IDs -
    private static final int HENESYS_MAIN = 100000000;
    private static final int HENESYS_MARKET = 100000100;
    private static final int HENESYS_PARK = 100000200;
    private static final int PET_PARK = 100000202;
    private static final List<Integer> HENESYS_MAPS = List.of(HENESYS_MAIN, HENESYS_MARKET, HENESYS_PARK, PET_PARK);

    // Portal IDs for connecting maps (kept for reference; gcmove 负责实际跨图导航) -
    private static final int PORTAL_MAIN_TO_MARKET = 23;
    private static final int PORTAL_MAIN_TO_PARK = 24;
    private static final int PORTAL_MARKET_TO_MAIN = 14;
    private static final int PORTAL_PARK_TO_MAIN = 18;
    private static final int PORTAL_PARK_TO_MARKET = 19;
    private static final int PORTAL_MARKET_TO_PARK = 15;
    private static final int PORTAL_PARK_TO_PETPARK = 13;
    private static final int PORTAL_PETPARK_TO_PARK = 5;

    private static final long JQ_CONVERSION_COOLDOWN_MS = 10 * 60 * 1000;
    private long lastJQConversionTime = 0;

    // Cooldown between map changes (3 minutes)
    private static final long MAP_CHANGE_COOLDOWN_MS = 3 * 60 * 1000;

    public HenesysBot(Character character) {
        super(character);
        dialoguePath = "HenesysBotDialogue.yaml";
        botType = "HenesysBot";
        lastMapChangeTime = 0;
    }

    private void setHenesysBotState(HenesysBotState state) {
        this.henesysBotState = state;
    }

    private enum HenesysBotState {
        RESET,
        IDLE,
        WANDER,
        EMOTE,
        CHANGE_MAP
    }

    private void resetHenesysBotState() {
        setHenesysBotState(HenesysBotState.RESET);
        startTime = System.currentTimeMillis();
        endTime = 0;
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        getDebugger().debugLoggingFull(String.format("%s HenesysBotState: %s", this.getChr().getName(), henesysBotState), String.format("%s", henesysBotState));

        switch (henesysBotState) {
            case RESET:
                resetHenesysBotState();
                setHenesysBotState(HenesysBotState.IDLE);
                break;
            case IDLE:
                decideNextAction();
                break;
            case WANDER:
                wanderPlatforms();
                doRandomEmote();
                doRandomChat("WanderChat");
                setHenesysBotState(HenesysBotState.IDLE);
                break;
            case EMOTE:
                doRandomEmote();
                doRandomChat("EmoteReaction");
                setHenesysBotState(HenesysBotState.IDLE);
                break;
            case CHANGE_MAP:
                doRandomChat("MapTransition");
                changeMap();
                lastMapChangeTime = System.currentTimeMillis();
                doRandomEmote();
                // 落地游走移至 travel 到达回调（onMapChangeArrival → wanderToMainPlatform），
                // 否则此刻 travel 的 gcmove 会话已持锁且 bot 尚未换图，游走必然 no-op（M1-R3）。
                setHenesysBotState(HenesysBotState.IDLE);
                break;
            default:
                log.info("Unexpected state: " + henesysBotState);
                state = BotState.FINISHED;
                resetHenesysBotState();
                throw new IllegalStateException("Unexpected state: " + state);
        }
    }

    /**
     * Decides what action the bot takes this tick.
     * Weighted rolls determine whether it wanders, changes maps, or just idles.
     */
    private void decideNextAction() {
        if (getChr().getMapId() == PET_PARK
                && (System.currentTimeMillis() - lastJQConversionTime) > JQ_CONVERSION_COOLDOWN_MS
                && Randomizer.nextInt(4) == 0) {
            convertToJQBot();
            return;
        }

        boolean mapChangeCooledDown = (System.currentTimeMillis() - lastMapChangeTime) > MAP_CHANGE_COOLDOWN_MS;

        if (mapChangeCooledDown && Randomizer.nextInt(10) == 0) {
            setHenesysBotState(HenesysBotState.CHANGE_MAP);
            return;
        }

        // ~33% chance to wander on any given tick
        if (Randomizer.nextInt(3) == 0) {
            setHenesysBotState(HenesysBotState.WANDER);
            return;
        }

        // 10% chance to do an emote even while staying put
        if (Randomizer.nextInt(5) == 0) {
            setHenesysBotState(HenesysBotState.EMOTE);
            return;
        }

        // Otherwise stay idle - do nothing this tick
    }

    /**
     * Moves the bot to another platform on the current map.
     * Uses weighted rolls for variety - sometimes stays on current platform,
     * sometimes moves to a nearby one, sometimes picks from the full map.
     * 换位 API 接线：走 PlatformPlacement.botMoveToPlatformAnyUnoccupiedSpotAware
     * 占位感知换位（避免 bot 互相堆叠），移动后以概率 nudgeAwayFromOverlap 推开重叠者。
     */
    private void wanderPlatforms() {
        List<String> platforms = getWanderablePlatforms(getChr().getMapId());
        if (platforms.isEmpty()) return;

        boolean moved = false;
        if (Randomizer.nextInt(5) == 0) {
            String current = getCurrentPlatform(getChr());
            if (platforms.contains(current)) {
                botMoveToPlatformAnyUnoccupiedSpotAware(getChr(), current);
                moved = true;
            }
        } else if (Randomizer.nextInt(10) == 0) {
            botMoveToPlatformAnyUnoccupiedSpotAware(getChr(),
                    getRandomElement(platforms.size() > 1 ? platforms.subList(0, 2) : platforms));
            moved = true;
        } else if (Randomizer.nextInt(35) == 0) {
            botMoveToPlatformAnyUnoccupiedSpotAware(getChr(), getRandomElement(platforms));
            moved = true;
        }

        if (moved && Randomizer.nextInt(2) == 0) {
            nudgeAwayFromOverlap(getChr());
        }
    }

    private void doRandomEmote() {
        if (Randomizer.nextInt(10) == 0) {
            int emoteId = Randomizer.nextInt(50) == 0 ? 2 : 3;
            BotGameSupport.botEmote(getChr(), emoteId);
        }
    }

    private void doRandomChat(String dialogueNode) {
        if (Randomizer.nextInt(20) == 0) {
            try {
                String line = BotDialogueHandler.getRandomResolvedLine(this, dialogueNode);
                if (line != null) BotGameSupport.botSpeak(getChr(), line);
            } catch (Exception e) {
                // dialogue YAML node missing, skip
            }
        }
    }

    // gcmove 图导航跨图：从 HENESYS_MAPS 里挑最不拥挤的一张，交由 GCTravel 走世界图。
    // 锁协议修复（M1-R3）：travel 的 gcmove 会话会 enable 永久持锁，落地后游走
    // （botMoveToPlatformAnyUnoccupiedSpotAware → pathFinderAware）拿锁失败全部 no-op。
    // 改为在 travel 到达/放弃回调里先 GCMovement.disable 释放会话与锁，再转录制引擎游走，
    // 对齐 SoloMapling 换图后 wanderToMainPlatform 立即生效的同步语义。
    private void changeMap() {
        List<Integer> options = new ArrayList<>();
        for (int mapId : HENESYS_MAPS) {
            if (mapId != getChr().getMapId()) {
                options.add(mapId);
            }
        }
        if (options.isEmpty()) return;

        int chosen = pickLeastCrowdedMap(options);
        try {
            GCMovement.travel(getChr(), chosen, ok -> onMapChangeArrival(chosen, ok));
            checkPrioritySpeed();
            log.info(getChr().getName() + " changing map to " + chosen);
        } catch (Exception e) {
            log.warn("HenesysBot map change failed: " + e.getMessage());
        }
    }

    /**
     * travel 到达/放弃回调（gctravel poll 线程）：释放 gcmove 会话与移动锁，再异步走
     * 录制引擎游走（pathFinderAware 同步阻塞，不得占用 poll 线程）。无论成功失败都游走，
     * 对齐 SoloMapling CHANGE_MAP 分支「changeMap 后无条件 wanderToMainPlatform」语义。
     */
    private void onMapChangeArrival(int destMapId, boolean ok) {
        GCMovement.disable(getChr()); // 释放 travel 的 gcmove 会话与锁，让录制引擎重新拿锁
        log.debug(I18nUtil.getLogMessage("HenesysBot.mapChange.done", getChr().getId(), destMapId, ok));
        BotExecutors.runAsync(() -> {
            try {
                wanderToMainPlatform();
            } catch (Exception e) {
                log.warn("HenesysBot post-map-change wander failed: " + e.getMessage());
            }
        });
    }

    private int pickLeastCrowdedMap(List<Integer> mapIds) {
        Random rng = new Random();
        double[] weights = new double[mapIds.size()];
        double total = 0;

        for (int i = 0; i < mapIds.size(); i++) {
            int population = mapPlayerCount(mapIds.get(i));
            weights[i] = 1.0 / (1 + population);
            total += weights[i];
        }

        double roll = rng.nextDouble() * total;
        double cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (roll < cumulative) return mapIds.get(i);
        }
        return mapIds.get(mapIds.size() - 1);
    }

    private int mapPlayerCount(int mapId) {
        MapleMap current = getChr().getMap();
        if (current == null) return 0;
        MapleMap target = current.getChannelServer().getMapFactory().getMap(mapId);
        return target == null ? 0 : target.getCharacters().size();
    }

    private void wanderToMainPlatform() {
        int mapId = getChr().getMapId();
        List<String> platforms = getWanderablePlatforms(mapId);
        if (!platforms.isEmpty()) {
            botMoveToPlatformAnyUnoccupiedSpotAware(getChr(), getRandomElement(platforms));
        }
    }

    /**
     * 可游走平台语义列表（SoloMapling 原语义）：宠物公园（PET_PARK）只有 m1
     * 可游走（其余为 JQ 专用台阶），其他 Henesys 地图取主平台（m*）全集。
     */
    private List<String> getWanderablePlatforms(int mapId) {
        if (mapId == PET_PARK) {
            return List.of("m1");
        }
        return getMainPlatformIds(mapId);
    }

    private void convertToJQBot() {
        doRandomChat("MapTransition");
        log.info("[HenesysBot] " + getChr().getName() + " converting to JQ bot on Pet Park.");
        BotTypeManager.convertBotType(getChr(), BotTypeManager.BotType.HENESYS_JQ_BOT);
    }

    public void setLastJQConversionTime(long time) {
        this.lastJQConversionTime = time;
    }

    @Override
    public void displayCommands(Character chr) {
        BotGameSupport.displayPlayerChatCommands(chr, hint);
    }

    @Override
    public void processMessages() {
        try {
            ChatMessage message = MessageQueue.getInstance().getMessageWithTimeout("secondary", 1, TimeUnit.SECONDS);
            if (message == null) {
                return;
            }
        } catch (Exception e) {
            // 去同步 stdout（printStackTrace 会在日志锁上 pin 虚拟线程 carrier），改走 slf4j。
            log.debug(I18nUtil.getLogMessage("HenesysBot.processMessages.error", e.getMessage()), e);
        }
    }
}
