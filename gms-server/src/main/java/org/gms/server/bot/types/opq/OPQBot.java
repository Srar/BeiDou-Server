package org.gms.server.bot.types.opq;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.server.bot.BotLogic;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotTiming;
import org.gms.server.bot.commands.BotAttack;
import org.gms.server.bot.environment.platform.PlatformPlacement;
import org.gms.server.bot.messaging.ChatMessage;
import org.gms.server.bot.messaging.MessageQueue;
import org.gms.server.bot.party.BotPartyLogic;
import org.gms.server.bot.replay.MovementCommands;
import org.gms.server.bot.types.BotGameSupport;
import org.gms.server.bot.types.opq.OPQSharedContext.OPQPhase;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Reactor;
import org.gms.util.PacketCreator;

import java.awt.Point;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.gms.server.bot.types.opq.OPQConstants.STAGE_1_COMPLETE_TP;
import static org.gms.server.bot.types.opq.OPQConstants.STAGE_1_ENTRY_TP;

/**
 * Orbis Party Quest rush-bot state machine.
 * <p>
 * Design:
 * - Perception-driven. The bot decides transitions from its own observable
 * state (current mapId, position, inventory) plus orchestrator blackboard
 * reads. It never accepts commands from other bots.
 * - Map change is authoritative for major phase swaps (lobby -> stage 1 ->
 * tower -> stage 2 -> exit lobby). When the game teleports the party, the
 * mapId flips and the bot rehomes itself via {@link #detectPhaseFromMap()}.
 * - Per-stage work is a short loop: navigate -> hit target -> loot -> return
 * -> drop -> wait. The wait state flips to TRANSITION once the orchestrator
 * (or a map change) signals the stage is done.
 * - All waits are time-boxed against {@link OPQConstants#STAGE_WAIT_TIMEOUT_MS}
 * so a stuck bot eventually falls back to LOOP_CHECK instead of hanging.
 */
@Slf4j
public class OPQBot extends BotSM {

    // volatile: the swing chains run off-tick and read/write these
    private volatile OPQBotState opqBotState = OPQBotState.RESET;
    private final OPQOrchestrator orchestrator;
    private final OPQSharedContext sharedContext;
    private List<String> hint = Collections.singletonList(getChr().getName());

    // Per-state timers / scratch fields
    private long stageWaitStartTime;
    private long lastRecruitMessageAt;
    private volatile int reactorHitsThisTarget;

    private int cloudPiecesLooted;
    private volatile int lootedRecordItemId = -1;

    public OPQBot(Character character) {
        super(character);
        dialoguePath = "OPQBotDialogue.yaml"; // TODO: add YAML or fall back gracefully
        botType = "OPQBot";
        this.orchestrator = OPQOrchestrator.getInstance();
        this.sharedContext = orchestrator.getSharedContext();
        orchestrator.registerBot(this);
    }

    // =========================================================================
    // State machine plumbing
    // =========================================================================

    private void setOPQBotState(OPQBotState state) {
        this.opqBotState = state;
    }

    /**
     * Dev-only setter used by the !opq forcestate command.
     */
    public void setStateForDebug(OPQBotState state) {
        OPQBotState prev = this.opqBotState;
        this.opqBotState = state;
        log.info(String.format("[OPQBot %s] DEBUG forced %s -> %s",
                getChr().getName(), prev, state));
    }

    /**
     * Read the current top-level state (for dev tooling / dump command).
     */
    public OPQBotState getOPQBotState() {
        return opqBotState;
    }

    /**
     * Logged state transition. Use this instead of setOPQBotState(...) for any
     * transition you want to see in the log. The reason argument is the
     * single most useful field for tracing why a bot moved — write it as a
     * short clause: "arrived at platform m3", "stage1Complete flag flipped",
     * "wait timed out", etc.
     */
    private void transitionTo(OPQBotState next, String reason) {
        OPQBotState prev = this.opqBotState;
        if (prev == next) {
            return;
        }
        this.opqBotState = next;
        log.info(String.format("[OPQBot %s] %s -> %s | %s | map=%d pos=%s",
                getChr().getName(), prev, next, reason,
                getChr().getMapId(), getChr().getPosition()));
    }

    public enum OPQBotState {
        RESET,
        RECRUITMENT,
        IN_PARTY_IDLE,
        STAGE_1_NAVIGATE,
        STAGE_1_HIT_REACTOR,
        STAGE_1_LOOT,
        STAGE_1_RETURN,
        STAGE_1_DROP_ITEMS,
        STAGE_1_WAIT,
        STAGE_1_TRANSITION,
        STAGE_1_TRANSITION_PT_2,
        STAGE_2_NAVIGATE,
        STAGE_2_HIT_BOX,
        STAGE_2_LOOT,
        STAGE_2_RETURN,
        STAGE_2_DROP_ITEMS,
        STAGE_2_WAIT,
        EXIT_DETECT,
        EXIT_LOBBY,
        LOOP_CHECK
    }

    private void resetOPQBotState() {
        setOPQBotState(OPQBotState.RESET);
        hint = Collections.singletonList(getChr().getName());
        stageWaitStartTime = 0;
        lastRecruitMessageAt = 0;
        reactorHitsThisTarget = 0;
        cloudPiecesLooted = 0;
        lootedRecordItemId = -1;
    }

    // =========================================================================
    // Main tick
    // =========================================================================

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        getDebugger().debugLoggingFull(
                String.format("%s OPQBotState: %s", this.getChr().getName(), opqBotState),
                String.format("%s", opqBotState));

        if (isInsidePQ() && !isInParty()) {
            handlePQAbandoned();
            return;
        }

        // Authoritative re-home: if the game teleported us to a map we weren't
        // expecting, snap to the correct phase entry state.
        OPQBotState mapDerived = detectPhaseFromMap();
        if (mapDerived != null && mapDerived != opqBotState && !inSameStageFamily(mapDerived, opqBotState)) {
            transitionTo(mapDerived,
                    "map-derived rehome (mapId=" + getChr().getMapId() + ")");
        }

        switch (opqBotState) {
            case RESET:
                resetOPQBotState();
                transitionTo(OPQBotState.RECRUITMENT, "RESET completed, beginning recruitment");
                break;
            case RECRUITMENT:
                handleRecruitment();
                break;
            case IN_PARTY_IDLE:
                handleInPartyIdle();
                break;
            case STAGE_1_NAVIGATE:
                handleStage1Navigate();
                break;
            case STAGE_1_HIT_REACTOR:
                hitReactor4Times();
                break;
            case STAGE_1_LOOT:
                handleStage1Loot();
                break;
            case STAGE_1_RETURN:
                handleStage1Return();
                break;
            case STAGE_1_DROP_ITEMS:
                handleStage1DropItems();
                break;
            case STAGE_1_WAIT:
                handleStage1Wait();
                break;
            case STAGE_1_TRANSITION:
                handleStage1Transition();
                break;
            case STAGE_1_TRANSITION_PT_2:
                handleStage1TransitionPart2();
                break;
            case STAGE_2_NAVIGATE:
                handleStage2Navigate();
                break;
            case STAGE_2_HIT_BOX:
                hitBox4Times();
                break;
            case STAGE_2_LOOT:
                handleStage2Loot();
                break;
            case STAGE_2_RETURN:
                handleStage2Return();
                break;
            case STAGE_2_DROP_ITEMS:
                handleStage2DropItems();
                break;
            case STAGE_2_WAIT:
                handleStage2Wait();
                break;
            case EXIT_DETECT:
                handleExitDetect();
                break;
            case EXIT_LOBBY:
                handleExitLobby();
                break;
            case LOOP_CHECK:
                handleLoopCheck();
                break;
            default:
                log.info("Unexpected state: " + opqBotState);
                state = BotState.FINISHED;
                resetOPQBotState();
                throw new IllegalStateException("Unexpected state: " + state);
        }
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
            // OPQ bots don't need to react to player chat during a run, but the
            // hook is here for future extensions (e.g. leader shouting "go").
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // =========================================================================
    // Phase: Recruitment (lobby)
    // =========================================================================

    private void handleRecruitment() {
        debugLogf("handleRecruitment: mapId=" + getChr().getMapId()
                + " inParty=" + isInParty()
                + " sinceLastChat=" + (System.currentTimeMillis() - lastRecruitMessageAt) + "ms");

        // Auto-accept any pending party invite first; if accepted we'll flip
        // to IN_PARTY_IDLE on the next tick via the isInParty() check below.
        boolean accepted = BotPartyLogic.checkPartyQueue(getChr());
        if (accepted) {
            debugLogf("Accepted a pending party invite.");
        }

        if (isInParty()) {
            orchestrator.noteLeaderFromBot(this);
            sharedContext_trySetPhase(OPQPhase.IN_PARTY_IDLE);
            transitionTo(OPQBotState.IN_PARTY_IDLE, "joined a party");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRecruitMessageAt >= OPQConstants.RECRUIT_MESSAGE_INTERVAL_MS) {
            String msg = OPQRecruitMessages.generateRecruitMessage(getChr());
            BotGameSupport.botSpeak(getChr(), msg);
            lastRecruitMessageAt = now;
            debugLogf("Recruit chat sent: \"" + msg + "\"");

            // 换位 API 接线：招募喊话后走到大厅某个主平台的空位（占位感知，避免招募 bot 堆叠）。
            List<String> platforms = PlatformPlacement.getMainPlatformIds(getChr().getMapId());
            if (!platforms.isEmpty()) {
                String target = platforms.get(new Random().nextInt(platforms.size()));
                PlatformPlacement.botMoveToPlatformAnyUnoccupiedSpot(getChr(), target);
            }
        }
    }

    // =========================================================================
    // Phase: In party, pre-start idle
    // =========================================================================

    private void handleInPartyIdle() {
        debugLogf("handleInPartyIdle: mapId=" + getChr().getMapId()
                + " inParty=" + isInParty()
                + " phase=" + sharedContext.getCurrentPhase());

        if (!isInParty()) {
            // Disbanded before PQ started — back to lobby chat.
            transitionTo(OPQBotState.RECRUITMENT, "party disbanded before PQ started");
            return;
        }

        if (getPartyLeader().getMapId() == OPQConstants.OPQ_STAGE_1) {
            cloudPiecesLooted = 0;
            lootedRecordItemId = -1;
            // deliberate synchronous warp: follow-leader warp blocks through the
            // arrival choreography and nothing may overlap it
            BotGameSupport.blockingSleep(3000);
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), STAGE_1_ENTRY_TP);
            transitionTo(OPQBotState.STAGE_1_NAVIGATE, "Warp to Stage 1 with Leader");
        }
    }

    // =========================================================================
    // Phase: Stage 1
    // =========================================================================

    private void handleStage1Navigate() {
        sharedContext_trySetPhase(OPQPhase.STAGE_1);

        Integer reactorOid = orchestrator.assignCloudReactor(this);
        debugLogf("handleStage1Navigate: reactorOid=" + reactorOid
                + "Char pos=" + getChr().getPosition());


        boolean moreCloudsLeft = orchestrator.hasUnclaimedLiveCloudReactor(
                getChr().getMap(), getChr().getId());
        if (!moreCloudsLeft) {
            sharedContext.putCloudAssignment(getChr().getId(), null);
            transitionTo(OPQBotState.STAGE_1_RETURN,
                    "no clouds left -> going return state");
            return;
        }

        Reactor reactor = getChr().getMap().getReactorByOid(reactorOid);
        if (reactor == null || !reactor.isAlive() || reactor.getState() >= 4) {
            // Stale assignment — clear and retry next tick.
            sharedContext.putCloudAssignment(getChr().getId(), null);
            debugLogf("Reactor oid=" + reactorOid + " is stale (null/dead/state>=4) — re-requesting next tick");
            return;
        }

        // ensures its in range to hit it, could potentially loop if not in range based on reactor hit range px
        Point reactorPos = reactor.getPosition();
        double dx = Math.abs(getChr().getPosition().getX() - reactorPos.getX());
        if (dx <= OPQConstants.REACTOR_HIT_RANGE_PX) {
            reactorHitsThisTarget = 0;
            transitionTo(OPQBotState.STAGE_1_HIT_REACTOR,
                    "arrived within range of reactor oid=" + reactorOid + " (dx=" + dx + "px)");
            return;
        }
        // 空中寻路接线：云反应器悬空，pathFinderBetaAerial 先投影最近录制地面点再走地面寻路。
        MovementCommands.pathFinderBetaAerial(getChr(), reactorPos);
        waitFor(OPQConstants.NAVIGATE_SETTLE_MS); // let the walk land; range check re-runs next tick
        debugLogf("Stage1Navigate walking: dx=" + dx + " target=" + reactorPos);
    }

    private void hitReactor4Times() {
        // swings play out on a chain; the gate kills leftover swings once a hit
        // transitions us to LOOT, and waitFor holds ticks until the chain is done
        BotTiming.Chain chain = BotTiming.chain()
                .stopUnless(() -> opqBotState == OPQBotState.STAGE_1_HIT_REACTOR);
        for (int x = 0; x < 4; x++) {
            chain.run(this::handleStage1HitReactor).pause(OPQConstants.SWING_INTERVAL_MS);
        }
        chain.start();
        waitFor(OPQConstants.SWING_INTERVAL_MS * 4 + 200);
    }

    private void handleStage1HitReactor() {
        Integer reactorOid = sharedContext.getMyCloudAssignment(getChr().getId());
        if (reactorOid == null) {
            // Lost our assignment somehow — go back to navigate to re-request.
            transitionTo(OPQBotState.STAGE_1_NAVIGATE, "no reactor assignment in HIT state");
            return;
        }
        Reactor reactor = getChr().getMap().getReactorByOid(reactorOid);
        if (reactor == null || !reactor.isAlive() || reactor.getState() >= 4) {
            debugLogf("Reactor oid=" + reactorOid + " already dead — skipping to LOOT");
            transitionTo(OPQBotState.STAGE_1_LOOT, "reactor already broken on arrival");
            return;
        }

        debugLogf("handleStage1HitReactor: hit#" + (reactorHitsThisTarget + 1)
                + "/" + OPQConstants.MAX_REACTOR_HITS
                + " reactorOid=" + reactorOid + " state=" + reactor.getState());

        // Defensive: re-check state immediately before swinging.
        if (reactor.getState() >= 4) {
            debugLogf("Pre-hit guard tripped: state=" + reactor.getState() + " — skipping to LOOT");
            transitionTo(OPQBotState.STAGE_1_LOOT, "pre-hit state guard");
            return;
        }

        BotAttack.basicSwing(getChr());
        hitReactor(getChr().getMap(), reactorOid);
        reactorHitsThisTarget++;

        // Bot-owned drop: clientless bots don't trigger the normal reactor drop
        // pipeline, so we manually spawn the cloud piece if our hit was the one
        // that finalized the break (state == 4 immediately after the hit).
        byte stateAfter = reactor.getState();
        if (stateAfter >= 4) {
            dropItemAtReactor(getChr().getMap(), reactorOid,
                    OPQConstants.CLOUD_PIECE, getChr());
            debugLogf("Forced cloud drop after finalizing break: oid=" + reactorOid);
        }

        if (stateAfter >= 4 || reactorHitsThisTarget >= OPQConstants.MAX_REACTOR_HITS) {
            transitionTo(OPQBotState.STAGE_1_LOOT,
                    "reactor broken (state=" + stateAfter + ", hits=" + reactorHitsThisTarget + ")");
        }
    }

    private void handleStage1Loot() {
        Point botPos = getChr().getPosition();
        Integer reactorOid = sharedContext.getMyCloudAssignment(getChr().getId());
        Reactor reactor = (reactorOid != null) ? getChr().getMap().getReactorByOid(reactorOid) : null;
        Point reactorPos = (reactor != null) ? reactor.getPosition() : null;
        int[] cloudFilter = {OPQConstants.CLOUD_PIECE};

        // 1) Primary scan: at the bot's feet (where the just-broken reactor stood).
        List<MapObject> found = BotLogic.checkForItemsOnFloor(
                getChr(), botPos, OPQConstants.STAGE_1_LOOT_SCAN_RANGE_PX, cloudFilter);
        debugLogf("handleStage1Loot: primary @" + botPos + " hits=" + found.size());

        // 2) Fallback: cloud may have fallen through one or more footholds
        //    below the reactor anchor. Step straight down from the reactor's
        //    x in fixed increments and stop on the first hit.
        if (found.isEmpty() && reactorPos != null) {
            for (int step = 1; step <= OPQConstants.STAGE_1_LOOT_FALLBACK_STEPS; step++) {
                Point probe = new Point(
                        reactorPos.x,
                        reactorPos.y + step * OPQConstants.STAGE_1_LOOT_FALLBACK_STEP_PX);
                List<MapObject> hits = BotLogic.checkForItemsOnFloor(
                        getChr(), probe, OPQConstants.STAGE_1_LOOT_SCAN_RANGE_PX, cloudFilter);
                debugLogf("handleStage1Loot: fallback step " + step
                        + " @" + probe + " hits=" + hits.size());
                if (!hits.isEmpty()) {
                    found = hits;
                    break;
                }
            }
        }

        if (!found.isEmpty()) {
            BotGameSupport.lootItemListOnFloor(getChr(), found);
            cloudPiecesLooted += 1;
            BotGameSupport.botChatbubble(getChr(), "云朵碎片：" + cloudPiecesLooted);
        }
        waitFor(800); // loot beat before NAVIGATE ticks

        debugLogf("handleStage1Loot: scannedHits=" + found.size()
                + " cloudPiecesLooted=" + cloudPiecesLooted);

        transitionTo(OPQBotState.STAGE_1_NAVIGATE,
                "loot pass complete, searching for next cloud reactor");
    }

    private void handleStage1Return() {
        // 锁协议修复（C1）：对齐 SoloMapling 全录制引擎——pathFinderBeta 调用级拿锁，
        // 不再走 gcmove 动态会话（会话级永久持锁会让 Stage2 的 pathFinderBetaAerial 拿锁失败）。
        MovementCommands.pathFinderBeta(getChr(), new Point(497, 143));
        waitFor(OPQConstants.NAVIGATE_SETTLE_MS); // settle before DROP_ITEMS ticks
        transitionTo(OPQBotState.STAGE_1_DROP_ITEMS, "return state done.");
    }

    private void handleStage1DropItems() {
        int cloudCount = cloudPiecesLooted;
        debugLogf("handleStage1DropItems: cloudCount=" + cloudCount + " pos=" + getChr().getPosition());
        if (cloudCount > 0) {
            BotGameSupport.botSpeak(getChr(), "扔 " + cloudCount + " 朵云！");
            BotTiming.after(400, () ->
                    BotGameSupport.botThrowItemQty(getChr(), OPQConstants.CLOUD_PIECE, cloudCount, getChr().getPosition()));
            waitFor(800); // hold WAIT until the throw lands
        }

        sharedContext.markTaskComplete(getChr().getId());
        startStageWaitTimer();
        transitionTo(OPQBotState.STAGE_1_WAIT,
                "all cloud reactors broken, waiting for stage-1 clear");
    }

    private void handleStage1Wait() {
        if (sharedContext.isStage1Complete() && orchestrator.isChamberlainSpawned()) {
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), STAGE_1_COMPLETE_TP);
            // Walk to Portal（C1：恢复 SoloMapling 的录制引擎 moveToPortal，全录制引擎不变量）
            MovementCommands.moveToPortal(getChr(), 4);
            transitionTo(OPQBotState.STAGE_1_TRANSITION, "stage1Complete flag flipped by orchestrator");
            return;
        }
    }

    private void handleStage1Transition() {
        debugLogf("handleStage1Transition: mapId=" + getChr().getMapId()
                + " awaiting teleport to central tower (" + OPQConstants.OPQ_TOWER + ")");

        if (getPartyLeader().getMapId() == OPQConstants.OPQ_TOWER) {
            // deliberate synchronous warp sequence (blocking arrival choreography)
            BotGameSupport.blockingSleep(1000);
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-260,-32)); // Spawn point for OPQ tower [x=-260,y=-32]
            BotGameSupport.blockingSleep(1000);
            // C1：tower 步行恢复 SoloMapling 的录制引擎 pathFinderBeta（动态会话会永久持锁）
            MovementCommands.pathFinderBeta(getChr(), new Point(159, -32)); // Walk to Portal [x=159,y=-32]
            transitionTo(OPQBotState.STAGE_1_TRANSITION_PT_2, "Waiting for leader to enter stage 2");
        }
    }

    private void handleStage1TransitionPart2() {
        // Teleport from Tower to Stage 2
        if (getPartyLeader().getMapId() == OPQConstants.OPQ_STAGE_2) {
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-113,-321)); // Spawn point for stage 2 [x=-113,y=-321]
            waitFor(1000); // settle after the warp before NAVIGATE ticks
            transitionTo(OPQBotState.STAGE_2_NAVIGATE, "arrived in stage-2 map");
        }
    }

    // =========================================================================
    // Phase: Stage 2
    // =========================================================================

    private void handleStage2Navigate() {
        sharedContext_trySetPhase(OPQPhase.STAGE_2);

        if (leaderLeftStage2()) {
            sharedContext.putBoxAssignment(getChr().getId(), null);
            followLeaderOut();
            return;
        }

        debugLogf("handleStage2Navigate: dumping all reactors on map " + getChr().getMapId());

        Integer reactorOid = orchestrator.assignBoxReactor(this);
        debugLogf("handleStage2Navigate: reactorOid=" + reactorOid
                + " pos=" + getChr().getPosition());

        boolean moreBoxesLeft = orchestrator.hasUnclaimedLiveBoxReactor(
                getChr().getMap(), getChr().getId());
        if (!moreBoxesLeft && reactorOid == null) {
            // All boxes broken or claimed — if we have an item, go drop it first
            if (lootedRecordItemId > 0) {
                transitionTo(OPQBotState.STAGE_2_RETURN,
                        "no boxes left, returning to drop looted item");
            } else {
                sharedContext.markTaskComplete(getChr().getId());
                startStageWaitTimer();
                transitionTo(OPQBotState.STAGE_2_WAIT,
                        "no boxes left and nothing to drop — waiting for stage clear");
            }
            return;
        }

        if (reactorOid == null) {
            debugLogf("Stage2Navigate: no assignment yet, waiting for one to free up");
            return;
        }

        Reactor reactor = getChr().getMap().getReactorByOid(reactorOid);
        if (reactor == null || !reactor.isAlive() || reactor.getState() >= 4) {
            sharedContext.putBoxAssignment(getChr().getId(), null);
            debugLogf("Box reactor oid=" + reactorOid + " is stale (null/dead/state>=4) — re-requesting next tick");
            return;
        }

        // Chat which box we're going for (ordinal based on sorted position right-to-left)
        String ordinal = orchestrator.getBoxOrdinal(reactorOid);
        BotGameSupport.botSpeak(getChr(), "我去拿 " + boxOrdinalZh(ordinal) + " 号箱子！");

        Point reactorPos = reactor.getPosition();
        double dx = Math.abs(getChr().getPosition().getX() - reactorPos.getX());
        if (dx <= OPQConstants.REACTOR_HIT_RANGE_PX) {
            reactorHitsThisTarget = 0;
            transitionTo(OPQBotState.STAGE_2_HIT_BOX,
                    "arrived at " + ordinal + " box (oid=" + reactorOid + ")");
            return;
        }
        // 空中寻路接线：音乐盒悬空，同 STAGE_1 走 pathFinderBetaAerial 地面投影寻路。
        MovementCommands.pathFinderBetaAerial(getChr(), reactorPos);
        waitFor(OPQConstants.NAVIGATE_SETTLE_MS); // let the walk land; range check re-runs next tick
        debugLogf("Stage2Navigate walking: dx=" + dx + " target=" + reactorPos);
    }

    private void hitBox4Times() {
        // Face toward the box reactor before swinging（C1：恢复 SoloMapling 的录制引擎转身原语，
        // 不再启用 gcmove 动态会话——会话级永久持锁会让后续 pathFinderBetaAerial 静默失败）
        Integer reactorOid = sharedContext.getMyBoxAssignment(getChr().getId());
        if (reactorOid != null) {
            Reactor reactor = getChr().getMap().getReactorByOid(reactorOid);
            if (reactor != null) {
                boolean boxIsLeft = reactor.getPosition().x < getChr().getPosition().x;
                if (boxIsLeft && !MovementCommands.facingLeft(getChr())) {
                    MovementCommands.microTurnAroundToLeft(getChr());
                } else if (!boxIsLeft && MovementCommands.facingLeft(getChr())) {
                    MovementCommands.microTurnAroundToRight(getChr());
                }
            }
        }

        BotTiming.Chain chain = BotTiming.chain()
                .stopUnless(() -> opqBotState == OPQBotState.STAGE_2_HIT_BOX);
        for (int x = 0; x < 4; x++) {
            chain.run(this::handleStage2HitBox).pause(OPQConstants.SWING_INTERVAL_MS);
        }
        chain.start();
        waitFor(OPQConstants.SWING_INTERVAL_MS * 4 + 200);
    }

    private void handleStage2HitBox() {
        Integer reactorOid = sharedContext.getMyBoxAssignment(getChr().getId());
        if (reactorOid == null) {
            transitionTo(OPQBotState.STAGE_2_NAVIGATE, "no box assignment in HIT state");
            return;
        }
        Reactor reactor = getChr().getMap().getReactorByOid(reactorOid);
        if (reactor == null || !reactor.isAlive() || reactor.getState() >= 4) {
            debugLogf("Box reactor oid=" + reactorOid + " already broken — skipping to LOOT");
            transitionTo(OPQBotState.STAGE_2_LOOT, "box already broken on arrival");
            return;
        }

        debugLogf("handleStage2HitBox: hit#" + (reactorHitsThisTarget + 1)
                + "/" + OPQConstants.MAX_REACTOR_HITS
                + " reactorOid=" + reactorOid + " state=" + reactor.getState());

        if (reactor.getState() >= 4) {
            debugLogf("Pre-hit guard tripped: state=" + reactor.getState() + " — skipping to LOOT");
            transitionTo(OPQBotState.STAGE_2_LOOT, "pre-hit state guard");
            return;
        }

        BotAttack.basicSwing(getChr());
        hitReactor(getChr().getMap(), reactorOid);
        reactorHitsThisTarget++;

        byte stateAfter = reactor.getState();
        if (stateAfter >= 4) {
            int recordItem = orchestrator.getBoxItemId(reactorOid);
            dropItemAtReactor(getChr().getMap(), reactorOid,
                    recordItem, getChr());
            lootedRecordItemId = recordItem;
            debugLogf("Forced record drop (itemId=" + recordItem + ") after finalizing box break: oid=" + reactorOid);
        }

        if (stateAfter >= 4 || reactorHitsThisTarget >= OPQConstants.MAX_REACTOR_HITS) {
            transitionTo(OPQBotState.STAGE_2_LOOT,
                    "box broken (state=" + stateAfter + ", hits=" + reactorHitsThisTarget + ")");
        }
    }

    private void handleStage2Loot() {
        Point botPos = getChr().getPosition();
        Integer reactorOid = sharedContext.getMyBoxAssignment(getChr().getId());
        Reactor reactor = (reactorOid != null) ? getChr().getMap().getReactorByOid(reactorOid) : null;
        Point scanCenter = (reactor != null) ? reactor.getPosition() : botPos;

        // Loot the item off the floor for game state consistency
        int[] recordFilter = OPQConstants.STAGE_2_ITEMS.stream().mapToInt(Integer::intValue).toArray();
        List<MapObject> found = BotLogic.checkForItemsOnFloor(
                getChr(), scanCenter, OPQConstants.STAGE_1_LOOT_SCAN_RANGE_PX, recordFilter);
        if (!found.isEmpty()) {
            BotGameSupport.lootItemListOnFloor(getChr(), found);
        }

        debugLogf("handleStage2Loot: lootedRecordItemId=" + lootedRecordItemId
                + " floorHits=" + found.size());
        if (lootedRecordItemId > 0) {
            BotGameSupport.botChatbubble(getChr(), "破纪录啦！");
        }
        waitFor(800); // loot beat before RETURN ticks

        // Clear box assignment so we can pick a new one
        sharedContext.putBoxAssignment(getChr().getId(), null);

        transitionTo(OPQBotState.STAGE_2_RETURN, "loot pass complete, returning to music box");
    }

    private void handleStage2Return() {
        // 锁协议修复（C1）：对齐 SoloMapling 全录制引擎——pathFinderBeta 调用级拿锁。
        // gcmove 动态会话的 enable 永久持锁是 Stage2 永久死锁（pathFinderBetaAerial 静默
        // 返回 null）与第二轮起 Stage1 死锁的根源。
        MovementCommands.pathFinderBeta(getChr(), new Point(-1588, -127));
        waitFor(OPQConstants.NAVIGATE_SETTLE_MS); // settle before DROP_ITEMS ticks
        transitionTo(OPQBotState.STAGE_2_DROP_ITEMS,
                "arrived at music box drop zone");
    }

    private void handleStage2DropItems() {
        if (lootedRecordItemId > 0) {
            BotGameSupport.botSpeak(getChr(), "扔出我的唱片！");
            int recordId = lootedRecordItemId;
            BotTiming.after(400, () ->
                    BotGameSupport.botThrowItem(getChr(), recordId, getChr().getPosition()));
            debugLogf("handleStage2DropItems: dropping itemId=" + recordId);
            lootedRecordItemId = -1;
        } else {
            debugLogf("handleStage2DropItems: no record to drop (lootedId=" + lootedRecordItemId + ")");
        }
        waitFor(1000); // replaces the old 400+600ms drop beats

        // If leader already cleared and left, don't bother with remaining boxes
        if (leaderLeftStage2()) {
            followLeaderOut();
            return;
        }

        // Check if more boxes remain — loop back if so
        boolean moreBoxes = orchestrator.hasUnclaimedLiveBoxReactor(
                getChr().getMap(), getChr().getId());
        if (moreBoxes) {
            transitionTo(OPQBotState.STAGE_2_NAVIGATE,
                    "more boxes remaining, looping back for another");
        } else {
            sharedContext.markTaskComplete(getChr().getId());
            startStageWaitTimer();
            transitionTo(OPQBotState.STAGE_2_WAIT,
                    "all boxes broken, waiting for stage-2 clear");
        }
    }

    private void handleStage2Wait() {
        long waitedMs = stageWaitStartTime > 0
                ? (System.currentTimeMillis() - stageWaitStartTime) : 0;
        debugLogf("handleStage2Wait: waited=" + waitedMs + "ms"
                + " stage2Complete=" + sharedContext.isStage2Complete());

        // Detect leader leaving Stage 2 (via NPC exit or fast-exit through lobby)
        int leaderMap = getPartyLeader().getMapId();
        if (leaderMap == OPQConstants.OPQ_EXIT_LOBBY) {
            BotGameSupport.blockingSleep(1000); // deliberate: blocking follow-warp below
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-161, 323));
            transitionTo(OPQBotState.EXIT_LOBBY, "followed leader to exit lobby");
            return;
        }
        if (leaderMap == OPQConstants.OPQ_LOBBY) {
            BotGameSupport.blockingSleep(1000); // deliberate: blocking follow-warp below
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-233, 174));
            transitionTo(OPQBotState.LOOP_CHECK, "leader already in OPQ lobby");
            return;
        }

        if (sharedContext.isStage2Complete()) {
            transitionTo(OPQBotState.EXIT_DETECT,
                    "stage2Complete flag flipped by orchestrator");
            return;
        }

        if (waitTimedOut()) {
            transitionTo(OPQBotState.LOOP_CHECK,
                    "stage-2 wait timed out after "
                            + OPQConstants.STAGE_WAIT_TIMEOUT_MS + "ms");
        }
    }

    // =========================================================================
    // Phase: Exit
    // =========================================================================

    private void handleExitDetect() {
        debugLogf("handleExitDetect: mapId=" + getChr().getMapId()
                + " leaderMap=" + getPartyLeader().getMapId());
        sharedContext_trySetPhase(OPQPhase.EXIT);

        if (getChr().getMapId() == OPQConstants.OPQ_EXIT_LOBBY) {
            transitionTo(OPQBotState.EXIT_LOBBY, "arrived at exit lobby");
            return;
        }

        // Actively follow leader to exit lobby or recruitment lobby
        int leaderMap = getPartyLeader().getMapId();
        if (leaderMap == OPQConstants.OPQ_EXIT_LOBBY) {
            BotGameSupport.blockingSleep(1000); // deliberate: blocking follow-warp below
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-161, 323));
            transitionTo(OPQBotState.EXIT_LOBBY, "followed leader to exit lobby");
        } else if (leaderMap == OPQConstants.OPQ_LOBBY) {
            BotGameSupport.blockingSleep(1000); // deliberate: blocking follow-warp below
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-233, 174));
            transitionTo(OPQBotState.LOOP_CHECK, "followed leader to OPQ lobby");
        }
    }

    private void handleExitLobby() {
        debugLogf("handleExitLobby: mapId=" + getChr().getMapId()
                + " leaderMap=" + getPartyLeader().getMapId());

        // If leader has already moved to recruitment lobby, follow them
        // (deliberate synchronous warps: arrival choreography blocks and must not overlap)
        int leaderMap = getPartyLeader().getMapId();
        if (leaderMap == OPQConstants.OPQ_LOBBY) {
            BotGameSupport.blockingSleep(300);
            MapleMap lobbyMap = getChr().getMap().getChannelServer().getMapFactory().getMap(OPQConstants.OPQ_LOBBY);
            getChr().changeMap(lobbyMap, new Point(-233, 174));
            transitionTo(OPQBotState.LOOP_CHECK, "followed leader to recruitment lobby");
            return;
        }

        // Otherwise warp ourselves to lobby after a short wait
        BotGameSupport.blockingSleep(500);
        MapleMap lobbyMap = getChr().getMap().getChannelServer().getMapFactory().getMap(OPQConstants.OPQ_LOBBY);
        getChr().changeMap(lobbyMap, new Point(-233, 174));
        BotGameSupport.blockingSleep(2000);
        // 换位 API 接线：回大厅后走到某个主平台空位（占位感知）。
        List<String> platforms = PlatformPlacement.getMainPlatformIds(getChr().getMapId());
        if (!platforms.isEmpty()) {
            String target = platforms.get(new Random().nextInt(platforms.size()));
            PlatformPlacement.botMoveToPlatformAnyUnoccupiedSpot(getChr(), target);
        }

        transitionTo(OPQBotState.LOOP_CHECK, "exit-lobby complete, warped to recruitment lobby");
    }

    private void handleLoopCheck() {
        debugLogf("handleLoopCheck: inParty=" + isInParty()
                + " mapId=" + getChr().getMapId());
        // Clear ALL per-run scratch so next run starts completely clean.
        sharedContext.clearTaskComplete(getChr().getId());
        sharedContext.putCloudAssignment(getChr().getId(), null);
        sharedContext.putBoxAssignment(getChr().getId(), null);
        sharedContext.putPlatformAssignment(getChr().getId(), null);
        reactorHitsThisTarget = 0;
        stageWaitStartTime = 0;
        lootedRecordItemId = -1;
        cloudPiecesLooted = 0;

        if (isInParty()) {
            transitionTo(OPQBotState.IN_PARTY_IDLE,
                    "still partied, ready for next PQ run");
        } else {
            transitionTo(OPQBotState.RECRUITMENT,
                    "no party, returning to lobby chat");
        }
    }

    // =========================================================================
    // PQ abandonment
    // =========================================================================

    private boolean isInsidePQ() {
        return switch (opqBotState) {
            case RESET, RECRUITMENT, IN_PARTY_IDLE, LOOP_CHECK -> false;
            default -> true;
        };
    }

    private void handlePQAbandoned() {
        debugLogf("Party lost — PQ abandoned. Cleaning up and warping out.");

        // Clear this bot's shared context entries
        int botId = getChr().getId();
        sharedContext.putCloudAssignment(botId, null);
        sharedContext.putBoxAssignment(botId, null);
        sharedContext.putPlatformAssignment(botId, null);
        sharedContext.clearTaskComplete(botId);

        // deliberate synchronous warps (blocking arrival choreography)
        MapleMap exitMap = getChr().getMap().getChannelServer().getMapFactory().getMap(OPQConstants.OPQ_EXIT_LOBBY);
        getChr().changeMap(exitMap, new Point(-161, 323));
        BotGameSupport.blockingSleep(1500);

        MapleMap lobbyMap = getChr().getMap().getChannelServer().getMapFactory().getMap(OPQConstants.OPQ_LOBBY);
        getChr().changeMap(lobbyMap, new Point(-233, 174));

        resetOPQBotState();
        transitionTo(OPQBotState.RECRUITMENT, "party lost — PQ abandoned, returned to lobby");
    }

    // =========================================================================
    // Perception helpers
    // =========================================================================

    /**
     * Translate the current mapId into the state the bot should be in, if any.
     */
    private OPQBotState detectPhaseFromMap() {
        int mapId = getChr().getMapId();
        if (mapId == OPQConstants.OPQ_LOBBY) {
            return isInParty() ? OPQBotState.IN_PARTY_IDLE : OPQBotState.RECRUITMENT;
        }
        if (mapId == OPQConstants.OPQ_STAGE_1) return OPQBotState.STAGE_1_NAVIGATE;
        if (mapId == OPQConstants.OPQ_TOWER) return OPQBotState.STAGE_1_TRANSITION;
        if (mapId == OPQConstants.OPQ_STAGE_2) return OPQBotState.STAGE_2_NAVIGATE;
        if (mapId == OPQConstants.OPQ_EXIT_LOBBY) return OPQBotState.EXIT_DETECT;
        return null;
    }

    /**
     * Avoid re-homing mid-stage: if we're already in a STAGE_1_* state and the
     * map-derived state is also a STAGE_1_* state, don't rewind us to NAVIGATE.
     */
    private boolean inSameStageFamily(OPQBotState a, OPQBotState b) {
        return family(a) != null && family(a).equals(family(b));
    }

    private String family(OPQBotState s) {
        if (s == null) return null;
        String n = s.name();
        if (n.startsWith("STAGE_1")) return "S1";
        if (n.startsWith("STAGE_2")) return "S2";
        if (n.startsWith("EXIT")) return "EX";
        if (n.equals("RECRUITMENT") || n.equals("IN_PARTY_IDLE")) return "LOBBY";
        return null;
    }

    private boolean isInParty() {
        return getChr().getParty() != null;
    }

    private Character getPartyLeader() {
        return getChr().getParty().getLeader().getPlayer();
    }

    private boolean leaderLeftStage2() {
        int leaderMap = getPartyLeader().getMapId();
        return leaderMap == OPQConstants.OPQ_EXIT_LOBBY || leaderMap == OPQConstants.OPQ_LOBBY;
    }

    private void followLeaderOut() {
        int leaderMap = getPartyLeader().getMapId();
        BotGameSupport.blockingSleep(1000); // deliberate: blocking follow-warps below
        if (leaderMap == OPQConstants.OPQ_EXIT_LOBBY) {
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-161, 323));
            transitionTo(OPQBotState.EXIT_LOBBY, "leader already left stage 2 — following to exit lobby");
        } else {
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-233, 174));
            transitionTo(OPQBotState.LOOP_CHECK, "leader already in OPQ lobby — skipping exit");
        }
    }

    private void startStageWaitTimer() {
        stageWaitStartTime = System.currentTimeMillis();
    }

    private boolean waitTimedOut() {
        return stageWaitStartTime > 0
                && (System.currentTimeMillis() - stageWaitStartTime) > OPQConstants.STAGE_WAIT_TIMEOUT_MS;
    }

    private void sharedContext_trySetPhase(OPQPhase phase) {
        if (sharedContext.getCurrentPhase() != phase) {
            orchestrator.mirrorPhase(phase);
        }
    }

    private void debugLogf(String msg) {
        boolean opqDebug = false;
        if (!opqDebug) {
            return;
        }
        log.info("[OPQBot " + getChr().getName() + " " + opqBotState + "] " + msg);
    }

    /**
     * 把箱子序数标签转成中文数字表达（OPQConstants 里存的是 "1st"~"7th" 英文序数）。
     */
    private String boxOrdinalZh(String ordinal) {
        return switch (ordinal) {
            case "1st" -> "1";
            case "2nd" -> "2";
            case "3rd" -> "3";
            default -> ordinal.replaceFirst("th$", "");
        };
    }

    // =========================================================================
    // CustomReactor 等价实现（gms 未移植 MapVFX.CustomReactor）
    // =========================================================================

    private static void hitReactor(MapleMap map, int oid) {
        Reactor reactor = map.getReactorByOid(oid);
        if (reactor == null) return;
        byte state = (byte) (reactor.getState() + 1);
        reactor.setState(state);
        map.broadcastMessage(PacketCreator.triggerReactor(reactor, (short) 0));
    }

    private static void dropItemAtReactor(MapleMap map, int oid, int itemId, Character owner) {
        if (map == null || owner == null) return;
        Reactor reactor = map.getReactorByOid(oid);
        if (reactor == null) return;
        Item drop = BotLogic.generateCleanItem(itemId);
        Point dropPos = new Point(reactor.getPosition());
        map.dropFromReactor(owner, reactor, drop, dropPos, (short) 0);
    }
}
