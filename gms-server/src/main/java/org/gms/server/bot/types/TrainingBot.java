package org.gms.server.bot.types;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.constants.game.ExpTable;
import org.gms.constants.id.MapId;
import org.gms.net.server.Server;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.net.server.world.World;
import org.gms.server.TimerManager;
import org.gms.server.bot.BotExecutors;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotOptionMenu;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.BotTypeManager;
import org.gms.server.bot.attack.BotAttackDriver;
import org.gms.server.bot.attack.BotBuffDriver;
import org.gms.server.bot.dialogue.BotDialogueHandler;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.grind.DeepHub;
import org.gms.server.bot.grind.GrindBrain;
import org.gms.server.bot.grind.GrindStyle;
import org.gms.server.bot.grind.MapMobIndex;
import org.gms.server.bot.grind.SpotFinder;
import org.gms.server.bot.grind.TrainingMap;
import org.gms.server.bot.grind.TrainingMapChooser;
import org.gms.server.bot.party.BotPartyQueue;
import org.gms.server.bot.party.BotRecruitManager;
import org.gms.server.bot.town.TownLoiter;
import org.gms.server.bot.wander.BotWanderSystem;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.util.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

// A roaming grinder. Spawns in a base town, travels out to a level-appropriate map with mobs, grinds
// for a session, returns to a town hub, and repeats — organically, forever. First production consumer
// of GCMovement (LOD-governed movement) and BotAttackDriver (combat).
//
// Two state machines, kept separate (the design's core split):
//  - Macro brain: updateState() on the slow BotSM tick (2-6s): INIT -> IN_TOWN -> DECIDE -> GO_TRAIN
//    -> GRIND -> GO_TOWN -> IN_TOWN ... Tier-agnostic; only issues movement intents and accrues abstract EXP.
//  - Combat tier: one shared 0.5s ticker (ensureCombatTicker) swings every grinding bot whose map is
//    observed (GCMovement.isMapObserved). The single shared task drives all bots' combat — no thread per bot.
//
// Ported 1:1 from SoloMapling. gms adaptations: ExecutorServiceManager -> BotExecutors/TimerManager,
// CharacterStorage -> BotStorage, EventFactory -> GameEvent.levelUp, BotLogger -> slf4j,
// SocialCommands/MovementCommands inlined.
@Slf4j
public class TrainingBot extends BotSM {

    // ── Tunables (decoration, not balance — rough is fine) ───────────────────
    private static final long COMBAT_TICK_MS = 250;
    private static final double KILLS_PER_MIN = 30.0;        // abstract grind speed
    private static final long GRIND_MIN_MS = 600_000;        // a grind session lasts 10–20 min
    private static final long GRIND_MAX_MS = 1_200_000;
    private static final long MID_SESSION_FLOOR_MS = 90_000; // min remaining on a partially-elapsed first session
    private static final long TRAVEL_TIMEOUT_MS = 120_000;
    private static final int TRAVEL_PROGRESS_EPS_PX = 24;    // min position change between ticks that counts as "moving"
    private static final long TOWN_DWELL_MIN_MS = 4_000;     // linger in town (settle) 4–10 s before deciding
    private static final long TOWN_DWELL_MAX_MS = 10_000;
    private static final double SHOP_VISIT_CHANCE = 0.4;     // chance a town visit includes a shop trip (timing variety)
    private static final long SHOP_DWELL_MIN_MS = 30_000;    // browse a store 30–90 s
    private static final long SHOP_DWELL_MAX_MS = 90_000;
    // Town map id -> {potion shop map, weapon/armor shop map}.
    private static final Map<Integer, int[]> TOWN_SHOPS = Map.of(
            100000000, new int[]{100000102, 100000101}, // Henesys
            101000000, new int[]{101000002, 101000001}, // Ellinia
            102000000, new int[]{102000002, 102000001}, // Perion
            103000000, new int[]{103000002, 103000001}, // Kerning City
            104000000, new int[]{104000002, 104000001}, // Lith Harbor
            200000000, new int[]{200000002, 200000001}, // Orbis
            220000000, new int[]{220000002, 220000001}, // Ludibrium
            211000000, new int[]{211000102, 211000101}  // El Nath
    );
    // Sleepywood has no potion/equip stores; its town "errand" is a flavor trip to the hotel sauna instead.
    private static final int SLEEPYWOOD_HOTEL = 105040400;
    private static final int SAUNA_REGULAR = 105040401;
    private static final int SAUNA_VIP = 105040402;
    private static final int SAUNA_DOOR_X = -19;
    private static final int SAUNA_DOOR_Y = -208;
    private static final double SAUNA_VIP_CHANCE = 0.40;     // 60% regular sauna / 40% VIP sauna
    private static final long DOOR_WALK_MAX_MS = 20_000;     // cap the door walk so a missed arrival can't stall the trip
    private static final long SAUNA_TRIP_TIMEOUT_MS = 300_000; // whole-trip watchdog → force-recover to town if it hangs
    private static final int LEVEL_CAP = 195;
    private static final double FIRST_TRIP_TELEPORT_CHANCE = 0.70;

    // ── Self-repair watchdog (macro tick) ────────────────────────────────────
    private static final long STUCK_TELEPORT_MS = 30_000;
    private static final long STUCK_BAIL_MS = 60_000;
    private static final long REPAIR_COOLDOWN_MS = 12_000;

    // ── Map crowding balance (spread the cohort across maps, not just spots) ──────
    private static final long MAP_SATURATED_DWELL_MS = 8_000;
    private static final long MAP_EXCLUDE_MS = 45_000;
    private static final int MAX_MAP_HOPS_PER_EPISODE = 2;
    private static final boolean CROWD_BAIL_UNOBSERVED = true;

    // ── Ambient dialogue (context-token flavor; spoken only when observed, throttled) ──
    private static final long AMBIENT_MIN_MS = 120_000;
    private static final long AMBIENT_MAX_MS = 240_000;
    private static final long BUFF_MIN_MS = 90_000;
    private static final long BUFF_MAX_MS = 120_000;

    // ── Shared combat ticker (one task for ALL training bots) ────────────────
    private static final Set<TrainingBot> ACTIVE_GRINDERS = ConcurrentHashMap.newKeySet();
    private static volatile boolean combatTickerStarted = false;

    private static synchronized void ensureCombatTicker() {
        if (combatTickerStarted) {
            return;
        }
        combatTickerStarted = true;
        BotExecutors.ensureStarted();
        TimerManager.getInstance().register(TrainingBot::combatTickAll, COMBAT_TICK_MS);
    }

    /* 停机钩子：复位 combatTickerStarted 并清空 ACTIVE_GRINDERS，使 in-place 重启后可重新注册共享 ticker。 */
    public static void resetCombatTicker() {
        ACTIVE_GRINDERS.clear();
        combatTickerStarted = false;
    }

    // Swing every grinding bot whose map a real player can see. One exception per bot never stops the ticker.
    private static void combatTickAll() {
        for (TrainingBot bot : ACTIVE_GRINDERS) {
            try {
                bot.combatTick();
            } catch (Exception e) {
                // a single bot's combat error must never kill the shared ticker
            }
        }
    }

    // How many bots the shared combat ticker currently visits (for !env perf).
    public static int activeGrinderCount() {
        return ACTIVE_GRINDERS.size();
    }

    @Override
    protected long lowPriorityDelayMs() {
        if (phase == Phase.GRIND) {
            // gms 增强（F6）：源每次返回 60_000 + rng.nextInt(60_000) 新随机数，
            // 使 BotSM.updateScheduleDelay 的「周期不变则短路」永远失效——每个未观察
            // 宏 tick 都触发 synchronized reschedule（轮盘写 + 重排）。改为按 bot id
            // 的确定性稳定值：60-120s 范围语义不变、bot 间按 id 错峰、不引入新状态，
            // 相位不变时后续 tick 的相等短路恢复生效。
            return 60_000 + (getChr().getId() % 60_000);
        }
        return super.lowPriorityDelayMs();
    }

    private void combatTick() {
        Character chr = getChr();
        if (chr == null || !getRunning() || phase != Phase.GRIND) {
            return; // gated; removal happens in leaveGrind()/stopScheduledTask()
        }
        grind.tick(chr); // observed → spot grind (FIGHT⇄WAIT); unobserved → no-op (the macro tick accrues abstract EXP)
    }

    // ── Macro brain state ────────────────────────────────────────────────────
    private enum Phase { INIT, IN_TOWN, SHOP_TRAVEL, SHOP_DWELL, SHOP_RETURN, DECIDE, GO_TRAIN, GRIND, GO_TOWN,
        BREAK_TRAVEL, BREAK_REST }

    private volatile Phase phase = Phase.INIT;
    private boolean phaseEntered = false;     // has this phase run its one-time setup?
    private long phaseDeadlineMs = 0;         // travel timeout / town dwell

    // Async travel coordination — the callback fires on the GCMovement driver thread.
    private volatile boolean moveDone = false;
    private volatile boolean moveOk = false;
    private int travelLastMapId = -1;
    private Point travelLastPos = null;

    private int homeMapId = -1;           // the spawn town; GO_TOWN returns here
    private boolean firstTrip = true;     // first decision to go train: maybe warp straight there
    private boolean midSessionGrind = false; // next grind starts partially elapsed
    private final List<Integer> shopQueue = new ArrayList<>(); // store maps still to visit this town stop
    private int shopTargetMapId = -1;     // the store currently being travelled to
    private int currentTrainMapId = -1;   // the discovered map currently being trained on
    private int currentMobLevel = 0;      // its representative mob level (drives abstract EXP)
    private long grindUntilMs = 0;
    private long lastExpAccrualMs = 0;
    private long nextChatterMs = 0;        // throttle gate for ambient grind chatter
    private long nextBuffMs = 0;           // re-buff gate while grinding (only advances after an actual buff)
    private int lastKnownLevel = -1;       // tracks level to detect a level-up worth announcing

    // Sleepywood sauna flavor trip — an async errand that runs off the macro FSM; the IN_TOWN tick parks on it.
    private volatile boolean saunaTripActive = false;
    private volatile boolean saunaTripDone = false;
    private volatile long saunaTripDeadlineMs = 0;

    // ── Grind engine + macro watchdog state ──
    private final GrindBrain grind = new GrindBrain(this::debugChat);
    private boolean teleportedThisEpisode = false;
    private long lastRepairMs = 0L;

    // ── Map crowding state (macro tick only) ──
    private final Map<Integer, Long> mapCrowdCooldown = new HashMap<>();
    private int crowdHopsThisEpisode = 0;
    private long mapSaturatedSinceMs = 0L;

    // ── Grind breaks (macro tick only; state + behavior in GrindBreakRoutine) ──
    private final GrindBreakRoutine breaks = new GrindBreakRoutine(this);

    private final Random rng = new Random();

    private final BotOptionMenu soloMenu = new BotOptionMenu(this,
            List.of("How's the training?", "Wanna party up?", "Goodbye"),
            List.of(List.of("hows", "how is", "how goes"),
                    List.of("party", "team", "join"),
                    List.of("bye", "goodbye", "cya", "later")),
            this::onSoloMenuSelect);
    private final BotOptionMenu partyMenu = new BotOptionMenu(this,
            List.of("How's the training?", "Follow me!", "Goodbye"),
            List.of(List.of("hows", "how is", "how goes"),
                    List.of("follow", "come", "lead"),
                    List.of("bye", "goodbye", "cya", "later")),
            this::onPartyMenuSelect);

    public TrainingBot(Character character) {
        super(character);
        botType = "TrainingBot";
        dialoguePath = "TrainingBotDialogue.yaml";
    }

    @Override
    public void displayCommands(Character chr) {
        if (getChr().getParty() != null) {
            soloMenu.deactivate();
            partyMenu.show(chr);
        } else {
            partyMenu.deactivate();
            soloMenu.show(chr);
        }
    }

    private boolean menuActive() {
        return soloMenu.isActive() || partyMenu.isActive();
    }

    // Mid-recruit: a conversation is open or the bot said "invite me" and the window is live.
    boolean recruitingNow() { // package-visible: GrindBreakRoutine defers its stand-up on a live recruit
        return menuActive() || BotRecruitManager.isArmed(getChr().getId());
    }

    @Override
    public void checkPrioritySpeed() {
        if (recruitingNow()) {
            setPriorityHigh();
            return;
        }
        super.checkPrioritySpeed();
    }

    private void enterPhase(Phase next) {
        if (phase == Phase.SHOP_DWELL) {
            BotWanderSystem.stop(getChr()); // leaving a shop: end the flavor wander before travelling out
        }
        if (phase == Phase.IN_TOWN) {
            TownLoiter.stop(getChr()); // leaving town: end the loiter (stop fidget, free the ledge claim)
        }
        phase = next;
        phaseEntered = false;
        moveDone = false;
        moveOk = false;
        debugChat("phase -> " + next);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        getDebugger().debugLoggingFull(
                String.format("%s TrainingBot phase: %s", chr.getName(), phase), String.format("%s", phase));

        switch (phase) {
            case INIT -> doInit();
            case IN_TOWN -> doInTown();
            case SHOP_TRAVEL -> doTravel(shopTargetMapId, Phase.SHOP_DWELL, Phase.SHOP_RETURN);
            case SHOP_DWELL -> doShopDwell();
            case SHOP_RETURN -> doTravel(homeMapId >= 0 ? homeMapId : MapId.HENESYS,
                    Phase.DECIDE, Phase.DECIDE);
            case DECIDE -> doDecide();
            case GO_TRAIN -> doTravel(currentTrainMapId, Phase.GRIND, Phase.DECIDE);
            case GRIND -> doGrind();
            case GO_TOWN -> doTravel(homeMapId >= 0 ? homeMapId : MapId.HENESYS,
                    Phase.IN_TOWN, Phase.IN_TOWN);
            case BREAK_TRAVEL -> doBreakTravel();
            case BREAK_REST -> doBreakRest();
        }

        pollRecruitInvite(); // gated party-invite drain (the "wanna party up?" flow)
        soloMenu.poll();
        partyMenu.poll();    // kept last: a "Follow me!" selection converts this bot away
    }

    // ── Phases ───────────────────────────────────────────────────────────────

    private void doInit() {
        ensureCombatTicker();
        homeMapId = getChr().getMapId();
        // No mobs here → it's a town: do the town beat first. Has mobs → a field: decide immediately.
        enterPhase(MapMobIndex.level(homeMapId) < 0 ? Phase.IN_TOWN : Phase.DECIDE);
    }

    private void doInTown() {
        if (saunaTripActive) {
            if (now() > saunaTripDeadlineMs) {
                abortSaunaTrip();
            }
            return;
        }
        if (saunaTripDone) {
            saunaTripDone = false;
            enterPhase(Phase.DECIDE); // trip finished — resume the grind loop
            return;
        }
        if (!phaseEntered) {
            phaseEntered = true;
            crowdHopsThisEpisode = 0; // back home -> fresh outing, reset the crowd-migration budget
            buildShopPlan();
            boolean errand = maybeStartHubErrand();
            if (!errand) {
                TownLoiter.settle(getChr());
            }
            phaseDeadlineMs = now() + (errand ? shopDwellMs() : dwellMs());
            if (rng.nextInt(3) == 0) {
                emote(getChr(), 1 + rng.nextInt(7)); // a little life in town
            }
            return;
        }
        if (now() < phaseDeadlineMs) {
            return;
        }
        if (!shopQueue.isEmpty()) {
            shopTargetMapId = shopQueue.remove(0);
            enterPhase(Phase.SHOP_TRAVEL);
        } else if (homeMapId == MapId.SLEEPYWOOD && rng.nextDouble() < SHOP_VISIT_CHANCE) {
            startSaunaTrip(); // Sleepywood has no stores — its errand is a trip to the hotel sauna
        } else {
            enterPhase(Phase.DECIDE);
        }
    }

    private void buildShopPlan() {
        shopQueue.clear();
        int[] shops = TOWN_SHOPS.get(homeMapId);
        if (shops == null || rng.nextDouble() > SHOP_VISIT_CHANCE) {
            return; // unknown town, or skipping the shops this visit
        }
        int potion = shops[0];
        int equip = shops[1];
        switch (rng.nextInt(4)) {
            case 0 -> shopQueue.add(potion);
            case 1 -> shopQueue.add(equip);
            case 2 -> { shopQueue.add(potion); shopQueue.add(equip); }
            default -> { shopQueue.add(equip); shopQueue.add(potion); }
        }
    }

    private boolean maybeStartHubErrand() {
        Character chr = getChr();
        DeepHub.Info hub = DeepHub.of(homeMapId);
        if (hub == null || hub.potionNpc() == null || chr.getMapId() != homeMapId) {
            return false; // not a deep hub, no on-map vendor, or not currently at home
        }
        if (!shopQueue.isEmpty() || rng.nextDouble() >= SHOP_VISIT_CHANCE) {
            return false; // a store itinerary already covers this stop, or skipping the errand this visit
        }
        GCMovement.move(chr, hub.potionNpc().x, hub.potionNpc().y);
        sayContext("ShopRestock", chr, null);
        return true;
    }

    private void doShopDwell() {
        if (!phaseEntered) {
            phaseEntered = true;
            phaseDeadlineMs = now() + shopDwellMs();
            if (rng.nextInt(2) == 0) {
                emote(getChr(), 1 + rng.nextInt(7));
            }
            sayContext("ShopRestock", getChr(), null);
            BotWanderSystem.start(getChr()); // browse the shop map (whole-map) instead of piling on the portal
            return;
        }
        if (now() < phaseDeadlineMs) {
            return;
        }
        if (!shopQueue.isEmpty()) {
            shopTargetMapId = shopQueue.remove(0);
            enterPhase(Phase.SHOP_TRAVEL);
        } else {
            enterPhase(Phase.SHOP_RETURN);
        }
    }

    private void startSaunaTrip() {
        Character chr = getChr();
        if (chr == null) {
            enterPhase(Phase.DECIDE);
            return;
        }
        TownLoiter.stop(chr); // this async errand leaves town without enterPhase - end the loiter first
        saunaTripActive = true;
        saunaTripDone = false;
        saunaTripDeadlineMs = now() + SAUNA_TRIP_TIMEOUT_MS;
        debugChat("Sleepywood errand -> heading to the sauna");
        if (rng.nextInt(2) == 0) {
            emote(chr, 1 + rng.nextInt(7)); // a beat of life before heading off
        }
        GCMovement.travel(chr, SLEEPYWOOD_HOTEL, ok -> enterSaunaFromHotel(chr));
    }

    private void enterSaunaFromHotel(Character chr) {
        if (!saunaTripActive) {
            return; // aborted while travelling to the hotel
        }
        AtomicBoolean entered = new AtomicBoolean(false);
        Runnable enter = () -> {
            if (!saunaTripActive || !entered.compareAndSet(false, true)) {
                return; // already entered (door-arrival vs cap race), or the trip was aborted
            }
            int sauna = rng.nextDouble() < SAUNA_VIP_CHANCE ? SAUNA_VIP : SAUNA_REGULAR;
            chr.changeMap(sauna);
            BotWanderSystem.start(chr); // relax in the sauna for a beat
            BotExecutors.schedule(() -> leaveSauna(chr), shopDwellMs());
        };
        GCMovement.move(chr, SAUNA_DOOR_X, SAUNA_DOOR_Y, enter);
        BotExecutors.schedule(enter, DOOR_WALK_MAX_MS);
    }

    private void leaveSauna(Character chr) {
        if (!saunaTripActive) {
            return;
        }
        BotWanderSystem.stop(chr);
        GCMovement.travel(chr, SLEEPYWOOD_HOTEL, okHotel ->
                GCMovement.travel(chr, homeMapId, okTown -> finishSaunaTrip()));
    }

    private void finishSaunaTrip() {
        if (!saunaTripActive) {
            return; // already aborted/finished — don't re-arm the handoff
        }
        saunaTripActive = false;
        saunaTripDone = true; // doInTown advances to DECIDE on the macro thread
    }

    private void abortSaunaTrip() {
        Character chr = getChr();
        debugChat("sauna trip overran -> force-recover to town");
        saunaTripActive = false; // disarm any in-flight callbacks first
        if (chr != null) {
            BotWanderSystem.stop(chr);
            GCMovement.cancelTravel(chr);
            GCMovement.stop(chr);
            if (homeMapId >= 0 && chr.getMapId() != homeMapId) {
                chr.changeMap(homeMapId);
            }
        }
        saunaTripDone = true;
    }

    private void doDecide() {
        Character chr = getChr();
        // Station-here handoff (FollowerBot's "Train here with me!"): grind the current map, skip discovery.
        if (BotRecruitManager.consumeStationHere(chr.getId()) && MapMobIndex.level(chr.getMapId()) >= 0) {
            debugChat("DECIDE: station-here -> grind current map " + chr.getMapId());
            setTrainTarget(chr.getMapId(), Math.max(1, MapMobIndex.level(chr.getMapId())));
            firstTrip = false;
            enterPhase(Phase.GRIND);
            return;
        }
        // Party-aware: partied with a real player -> train on their map (or hold the current map while they're parked).
        Integer partyMap = partyTargetMapId();
        if (partyMap != null) {
            debugChat("DECIDE: party target -> map " + partyMap);
            setTrainTarget(partyMap, Math.max(1, MapMobIndex.level(partyMap)));
            firstTrip = false;
            enterPhase(chr.getMapId() == partyMap ? Phase.GRIND : Phase.GO_TRAIN);
            return;
        }
        Set<Integer> excluded = crowdExcludedMaps();
        clearTrainTarget(); // release any stale reservation before choosing
        TrainingMap pick = TrainingMapChooser.choose(chr, homeMapId, excluded, this::debugChat);
        if (pick == null) {
            debugChat("DECIDE: nothing reachable (lv " + chr.getLevel() + ") -> idle in town");
            enterPhase(Phase.IN_TOWN);
            return;
        }
        currentTrainMapId = pick.mapId();
        currentMobLevel = Math.max(1, pick.mobLevel());
        debugChat("DECIDE: chose map " + pick.mapId() + " (mob lv " + pick.mobLevel() + ")");
        boolean wasFirstTrip = firstTrip;
        firstTrip = false;
        boolean migrates = chr.getMapId() != pick.mapId();
        if (wasFirstTrip && migrates
                && !GCMovement.isMapObserved(chr.getMapId())
                && rng.nextDouble() < FIRST_TRIP_TELEPORT_CHANCE) {
            debugChat("DECIDE: first-trip warp -> map " + pick.mapId());
            midSessionGrind = true; // warped in -> reads as already-grinding -> shortened first session
            GCMovement.stop(chr);
            chr.changeMap(pick.mapId());
            enterPhase(Phase.GRIND);
            return;
        }
        if (wasFirstTrip && !migrates) {
            midSessionGrind = true; // already on the grind map (no visible travel) -> also a mid-session start
        }
        enterPhase(Phase.GO_TRAIN);
    }

    private void doTravel(int destMapId, Phase onArrive, Phase onFail) {
        Character chr = getChr();
        if (destMapId < 0) {
            enterPhase(onFail);
            return;
        }
        if (chr.getMapId() == destMapId) {
            enterPhase(onArrive);
            return;
        }
        if (!phaseEntered) {
            phaseEntered = true;
            phaseDeadlineMs = now() + TRAVEL_TIMEOUT_MS;
            travelLastMapId = chr.getMapId();
            travelLastPos = chr.getPosition();
            debugChat("travel -> map " + destMapId);
            GCMovement.travel(chr, destMapId, ok -> {
                moveOk = (ok != null && ok);
                moveDone = true;
            });
            return;
        }
        if (moveDone) {
            if (!moveOk) {
                debugChat("travel reported FAIL -> " + onFail);
            }
            enterPhase(moveOk ? onArrive : onFail);
            return;
        }
        if (madeTravelProgress(chr)) {
            phaseDeadlineMs = now() + TRAVEL_TIMEOUT_MS;
        } else if (now() > phaseDeadlineMs) {
            debugChat("travel STUCK: no progress for " + (TRAVEL_TIMEOUT_MS / 1000) + "s -> " + onFail);
            GCMovement.cancelTravel(chr);
            enterPhase(onFail);
        }
    }

    private boolean madeTravelProgress(Character chr) {
        int curMap = chr.getMapId();
        if (curMap != travelLastMapId) {
            travelLastMapId = curMap;
            travelLastPos = chr.getPosition(); // hop completed — unambiguous progress; re-anchor on the new map
            return true;
        }
        Point pos = chr.getPosition();
        if (pos != null && (travelLastPos == null
                || Math.abs(pos.x - travelLastPos.x) + Math.abs(pos.y - travelLastPos.y) > TRAVEL_PROGRESS_EPS_PX)) {
            travelLastPos = pos;
            return true;
        }
        return false;
    }

    private void doGrind() {
        Character chr = getChr();
        if (!phaseEntered) {
            phaseEntered = true;
            teleportedThisEpisode = false;
            GCMovement.setGrinding(chr, true); // engage grind nav guards (no idle-hang on ropes)
            grind.start(chr); // fresh heartbeat, select a spot, and move into it
            boolean resumingFromBreak = breaks.resuming();
            grindUntilMs = now() + (resumingFromBreak ? breaks.consumeResumeRemaining() : grindSessionMs());
            breaks.schedule(grindUntilMs, resumingFromBreak);
            lastExpAccrualMs = now();
            nextBuffMs = now(); // buff up as soon as a player can see it
            ACTIVE_GRINDERS.add(this);
            lastKnownLevel = chr.getLevel();
            if (!resumingFromBreak) {
                sayContext("GrindStart", chr, null);
            }
            debugChat("GRIND on map " + chr.getMapId() + " " + grind.spotLabel());
            return;
        }

        // Abstract EXP only when unobserved (observed time is covered by real kills via the ticker).
        long nowMs = now();
        double elapsedSec = (nowMs - lastExpAccrualMs) / 1000.0;
        lastExpAccrualMs = nowMs;
        if (elapsedSec > 0 && !GCMovement.isMapObserved(chr.getMapId()) && currentMobLevel > 0) {
            accrueAbstractExp(currentMobLevel, elapsedSec);
        }

        // Announce a level-up the moment it becomes visible (real kills on an observed map).
        int lvl = chr.getLevel();
        if (lastKnownLevel >= 0 && lvl > lastKnownLevel) {
            sayContext("LevelUp", chr, null);
        }
        lastKnownLevel = lvl;

        maybeGrindChatter(chr);
        maybeSelfBuff(chr);

        if (grindWatchdog(chr)) {
            return; // self-repair left the map (re-DECIDE) — skip the normal grind-timer transition
        }

        if (grindCrowdBail(chr)) {
            return; // map too crowded — left for a deeper one (re-DECIDE)
        }

        if (breaks.due() && !recruitingNow()) {
            if (breaks.begin(chr, grind, Math.max(MID_SESSION_FLOOR_MS, grindUntilMs - nowMs))) {
                enterPhase(Phase.BREAK_TRAVEL);
            }
            return;
        }

        if (nowMs >= grindUntilMs && !recruitingNow()) { // don't walk off mid-conversation/invite window
            sayContext("MapTransition", chr, null);
            leaveGrind();
            enterPhase(Phase.GO_TOWN);
        }
    }

    private boolean grindWatchdog(Character chr) {
        if (chr == null || !GCMovement.isMapObserved(chr.getMapId())) {
            return false; // unobserved bots don't fight (abstract EXP) — they can't be physically stuck
        }
        if (recruitingNow()) {
            return false; // mid-conversation / live invite window: never teleport or bail from under the player
        }
        long stuck = grind.msSinceProgress();
        if (stuck < STUCK_TELEPORT_MS) {
            teleportedThisEpisode = false; // making (or recently made) progress → reset the ladder
            return false;
        }
        if (now() - lastRepairMs < REPAIR_COOLDOWN_MS) {
            return false; // let the previous repair take effect before escalating
        }
        boolean mobReachable = isReachableHostileNearby(chr);
        boolean recovering = grind.isRecovering(chr);
        if (mobReachable && !teleportedThisEpisode && !recovering) {
            debugChat("watchdog: no hit for " + (stuck / 1000) + "s, mob reachable -> teleport to portal & re-grind");
            teleportToNearestPortal(chr);
            grind.resetupAfterTeleport(chr); // fresh kill window + re-select a spot from the new position
            teleportedThisEpisode = true;
            lastRepairMs = now();
            return false;
        }
        if (!mobReachable || stuck >= STUCK_BAIL_MS) {
            debugChat("watchdog: stuck " + (stuck / 1000) + "s (" + (mobReachable ? "teleport didn't help" : "no reachable mob") + ") -> bail map, re-decide");
            leaveGrind();
            enterPhase(Phase.DECIDE);
            return true;
        }
        return false;
    }

    private boolean grindCrowdBail(Character chr) {
        if (chr == null) {
            return false;
        }
        Integer partyMap = partyTargetMapId();
        if (partyMap != null && partyMap == chr.getMapId()) {
            return false; // never crowd-bail off the party's map - staying with the player wins
        }
        if (recruitingNow()) {
            return false; // mid-recruit: hold the map until the exchange resolves
        }
        if (!CROWD_BAIL_UNOBSERVED && !GCMovement.isMapObserved(chr.getMapId())) {
            return false; // old gate: only relieve crowding a player can actually see
        }
        if (!grind.mapSaturated() || crowdHopsThisEpisode >= MAX_MAP_HOPS_PER_EPISODE) {
            mapSaturatedSinceMs = 0L; // not saturated, or out of hops -> reset the persist timer
            return false;
        }
        if (mapSaturatedSinceMs == 0L) {
            mapSaturatedSinceMs = now(); // first saturated tick -> start the dwell
            return false;
        }
        if (now() - mapSaturatedSinceMs < MAP_SATURATED_DWELL_MS) {
            return false; // saturation must persist (ride out a cohort-arrival race before bailing)
        }
        int leaving = chr.getMapId();
        debugChat("crowd: map " + leaving + " saturated -> bail to a deeper map");
        mapCrowdCooldown.put(leaving, now() + MAP_EXCLUDE_MS);
        crowdHopsThisEpisode++;
        mapSaturatedSinceMs = 0L;
        leaveGrind();
        enterPhase(Phase.DECIDE);
        return true;
    }

    // ── Grind breaks (rest flavor — behavior in GrindBreakRoutine; the phases just delegate) ──

    public String forceGrindStyle(String styleName) {
        GrindStyle wanted = null;
        if (!styleName.equalsIgnoreCase("auto")) {
            try {
                wanted = GrindStyle.valueOf(styleName.toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        grind.forceStyle(wanted);
        nudgeSoon(300);
        return getChr().getName() + ": grind style -> " + (wanted == null ? "AUTO" : wanted)
                + (phase == Phase.GRIND ? " (live swap on next combat tick)" : " (applies at next grind)");
    }

    public boolean forceBreakNow() {
        if (phase != Phase.GRIND) {
            return false;
        }
        breaks.forceNow();
        nudgeSoon(300);
        return true;
    }

    private void doBreakTravel() {
        phaseEntered = true; // the routine owns its own setup latches (armed in begin())
        if (breaks.tickTravel(getChr())) {
            enterPhase(Phase.BREAK_REST);
        }
    }

    private void doBreakRest() {
        phaseEntered = true;
        if (breaks.tickRest(getChr())) {
            enterPhase(Phase.GRIND); // re-enters with the saved remainder; grind.start re-picks a spot
        }
    }

    // Break-sign flavor line ("brb" chalkboard).
    String breakSignText() {
        try {
            String line = BotDialogueHandler.getRandomResolvedLine(dialoguePath, botType, "BreakSign", getChr(), null);
            if (line != null) {
                return line;
            }
        } catch (Exception e) {
            // fall through to the default sign
        }
        return "brb";
    }

    private boolean isReachableHostileNearby(Character chr) {
        MapleMap map = chr.getMap();
        Point pos = chr.getPosition();
        if (map == null || pos == null) {
            return false;
        }
        Set<Integer> reach = GCMovement.reachableRegions(map, pos.x, pos.y);
        if (reach.isEmpty()) {
            return false;
        }
        for (Monster m : map.getAllMonsters()) {
            if (!SpotFinder.isHostile(m) || m.getPosition() == null) {
                continue;
            }
            Point g = GCMovement.groundPointBelow(map, m.getPosition().x, m.getPosition().y);
            if (g != null && reach.contains(GCMovement.regionIdAt(map, g.x, g.y))) {
                return true;
            }
        }
        return false;
    }

    private void teleportToNearestPortal(Character chr) {
        MapleMap map = chr.getMap();
        Point pos = chr.getPosition();
        if (map == null || pos == null) {
            return;
        }
        Point best = null;
        double bestSq = Double.MAX_VALUE;
        for (Portal p : map.getPortals()) {
            Point pp = p.getPosition();
            if (pp == null) {
                continue;
            }
            double dsq = pos.distanceSq(pp);
            if (dsq < bestSq) {
                bestSq = dsq;
                best = pp;
            }
        }
        if (best != null) {
            GCMovement.stop(chr); // drop the in-flight move so the driver re-acquires cleanly from the new spot
            GCMovement.teleportTo(chr, best.x, best.y);
        }
    }

    private void leaveGrind() {
        ACTIVE_GRINDERS.remove(this);
        grind.release(getChr()); // drop the spot claim + reset combat state
        clearTrainTarget(); // release this map's occupancy slot
        GCMovement.setRestHold(getChr(), false);
        GCMovement.setGrinding(getChr(), false);
        GCMovement.stop(getChr());
    }

    // ── Ambient dialogue (context-aware flavor) ───────────────────────────────

    private void maybeGrindChatter(Character chr) {
        long t = now();
        if (t < nextChatterMs) {
            return;
        }
        nextChatterMs = t + AMBIENT_MIN_MS + (long) (rng.nextDouble() * (AMBIENT_MAX_MS - AMBIENT_MIN_MS));
        if (!GCMovement.isMapObserved(chr.getMapId())) {
            return;
        }
        sayContext("GrindAmbient", chr, null);
    }

    private void maybeSelfBuff(Character chr) {
        if (now() < nextBuffMs) {
            return;
        }
        if (!GCMovement.isMapObserved(chr.getMapId())) {
            return; // hold the timer until it's worth showing.
        }
        BotBuffDriver.forceBuff(chr);
        nextBuffMs = now() + BUFF_MIN_MS + (long) (rng.nextDouble() * (BUFF_MAX_MS - BUFF_MIN_MS));
    }

    // Package-visible: GrindBreakRoutine speaks through the bot.
    void sayContext(String node, Character chr, Character player) {
        if (chr == null || !GCMovement.isMapObserved(chr.getMapId())) {
            return;
        }
        BotExecutors.runAsync(() ->
                getDialogueHandler().executeBotContextDialogue(node, this, player, BotDialogueHandler.CONTEXT_LINE_CHANCE));
    }

    // ── Party recruiting & interactive menu ─────────────────────────────────

    private Integer partyTargetMapId() {
        Party party = getChr().getParty();
        if (party == null) {
            return null;
        }
        Character member = firstRealPartyMember(party);
        if (member == null) {
            return null;
        }
        int memberMap = member.getMapId();
        if (MapMobIndex.level(memberMap) >= 0) {
            return memberMap;
        }
        if (MapMobIndex.level(getChr().getMapId()) >= 0) {
            return getChr().getMapId();
        }
        return null;
    }

    private Character firstRealPartyMember(Party party) {
        for (PartyCharacter pc : party.getMembers()) {
            Character p = pc == null ? null : pc.getPlayer();
            if (p != null && !BotHelpers.isBot(p) && p.getMap() != null) {
                return p;
            }
        }
        return null;
    }

    private void pollRecruitInvite() {
        Character chr = getChr();
        if (!BotPartyQueue.getInstance().hasPendingInvite(chr)) {
            return;
        }
        int recruiterId = BotRecruitManager.armedInviterId(chr.getId()); // read BEFORE poll - JOINED clears it
        if (BotRecruitManager.pollInvites(chr) == BotRecruitManager.InvitePoll.JOINED) {
            Character recruiter = chr.getClient().getChannelServer()
                    .getPlayerStorage().getCharacterById(recruiterId);
            sayRecruit("PartyJoined", recruiter);
            // No re-typing: a partied TrainingBot keeps grinding; DECIDE is now party-aware.
        }
    }

    private void onSoloMenuSelect(int idx, Character player) {
        switch (idx) {
            case 0 -> { // How's the training?
                sayRecruit("TrainingTalk", player);
                soloMenu.show(player);
            }
            case 1 -> optPartyAsk(player);
            default -> { // Goodbye
                sayRecruit("Goodbye", player);
                soloMenu.close(player);
            }
        }
    }

    private void onPartyMenuSelect(int idx, Character player) {
        switch (idx) {
            case 0 -> { // How's the training?
                sayRecruit("TrainingTalk", player);
                partyMenu.show(player);
            }
            case 1 -> optFollowMe(player);
            default -> { // Goodbye
                sayRecruit("Goodbye", player);
                partyMenu.close(player);
            }
        }
    }

    private void optPartyAsk(Character player) {
        Character chr = getChr();
        if (chr.getParty() != null) { // partied since the solo menu was shown
            sayRecruit(inSameParty(player) ? "AlreadyPartied" : "PartyDecline", player);
            soloMenu.close(player);
            return;
        }
        BotRecruitManager.RecruitAnswer ans = BotRecruitManager.rollPartyAsk(
                chr, player, BotRecruitManager.TRAINING_ACCEPT_CHANCE, false);
        if (ans == BotRecruitManager.RecruitAnswer.ACCEPTED) {
            sayRecruit("PartyAccept", player);
            soloMenu.close(player); // pollRecruitInvite now waits for this player's invite
        } else {
            sayRecruit("PartyDecline", player);
            soloMenu.show(player);
        }
    }

    private void optFollowMe(Character player) {
        Character chr = getChr();
        if (!inSameParty(player)) {
            sayRecruit("PartyFirst", player);
            partyMenu.close(player);
            return;
        }
        if (BotRecruitManager.activeFollowerCount() >= BotRecruitManager.FOLLOWER_CAP) {
            sayRecruit("PartyDecline", player);
            partyMenu.close(player);
            return;
        }
        sayRecruit("FollowAccept", player);
        partyMenu.close(player);
        BotRecruitManager.setPendingLeader(chr.getId(), player.getId());
        BotTypeManager.convertBotType(chr, BotTypeManager.BotType.FOLLOWER_BOT);
    }

    private boolean inSameParty(Character player) {
        return getChr().getParty() != null && player.getParty() != null
                && player.getParty().getId() == getChr().getParty().getId();
    }

    private void sayRecruit(String node, Character player) {
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        try {
            String line = BotDialogueHandler.getRandomResolvedLine(dialoguePath, botType, node, chr, player);
            if (line != null) {
                speak(chr, line);
            }
        } catch (Exception e) {
            // a missing dialogue node must never break the tick
        }
    }

    // Debug narration (OFF by default).
    void debugChat(String message) { // package-visible: GrindBreakRoutine narrates through the bot
        boolean debugChatEnabled = false; // <-- flip to true + HotSwap this method to turn narration on
        if (!debugChatEnabled) {
            return;
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        fullChat(chr, message); // general chat: bubble above the head AND a line in the chat box
    }

    // Silent, level-scaled accrual: kills/min x per-kill-exp, where per-kill-exp ~ the map's mob level.
    private void accrueAbstractExp(int mobLevel, double elapsedSec) {
        int perKillExp = Math.max(1, mobLevel);
        int gain = (int) Math.round((KILLS_PER_MIN / 60.0) * perKillExp * elapsedSec);
        if (gain <= 0) {
            return;
        }
        Character chr = getChr();
        long exp = (long) chr.getExp() + gain;
        int level = chr.getLevel();
        int startLevel = level;
        while (level < LEVEL_CAP) {
            int need = ExpTable.getExpNeededForLevel(level);
            if (need <= 0 || exp < need) {
                break;
            }
            exp -= need;
            level++;
        }
        chr.setLevel(level);
        chr.setExp((int) Math.min(exp, Integer.MAX_VALUE));
        if (level > startLevel) {
            BotEventBus.getInstance().publish(GameEvent.levelUp(
                    chr.getWorld(), chr.getClient().getChannel(), chr.getMapId(), chr.getId()));
        }
    }

    // ── Map selection (level-scaled hops + weighted, occupancy-balanced, chill-aware pick) ──

    private Set<Integer> crowdExcludedMaps() {
        long t = now();
        mapCrowdCooldown.values().removeIf(until -> until <= t);
        return new HashSet<>(mapCrowdCooldown.keySet());
    }

    private void setTrainTarget(int mapId, int mobLevel) {
        clearTrainTarget();
        currentTrainMapId = mapId;
        currentMobLevel = mobLevel;
        TrainingMapChooser.reserve(mapId);
    }

    private void clearTrainTarget() {
        if (currentTrainMapId >= 0) {
            TrainingMapChooser.release(currentTrainMapId);
        }
        currentTrainMapId = -1;
        currentMobLevel = 0;
    }

    private long dwellMs() {
        return TOWN_DWELL_MIN_MS + (long) (rng.nextDouble() * (TOWN_DWELL_MAX_MS - TOWN_DWELL_MIN_MS));
    }

    private long grindSessionMs() {
        long full = GRIND_MIN_MS + (long) (rng.nextDouble() * (GRIND_MAX_MS - GRIND_MIN_MS));
        if (midSessionGrind) {
            midSessionGrind = false;
            return Math.max(MID_SESSION_FLOOR_MS, (long) (rng.nextDouble() * full));
        }
        return full;
    }

    private long shopDwellMs() {
        return SHOP_DWELL_MIN_MS + (long) (rng.nextDouble() * (SHOP_DWELL_MAX_MS - SHOP_DWELL_MIN_MS));
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    // ── Diagnostics: aggregate LOD/training overview (backs !gcmove lod train) ──

    public static List<String> lodDiagnostic() {
        List<String> out = new ArrayList<>();

        Map<Integer, MapleMap> mapsById = new HashMap<>();
        Server server = Server.getInstance();
        if (server != null) {
            for (World w : server.getWorlds()) {
                if (w == null) {
                    continue;
                }
                for (Character c : w.getPlayerStorage().getAllCharacters()) {
                    if (c != null && c.getMap() != null) {
                        mapsById.putIfAbsent(c.getMapId(), c.getMap());
                    }
                }
            }
        }

        int total = 0, full = 0, halo = 0, dwell = 0, coarse = 0;
        int pInit = 0, pTown = 0, pShop = 0, pDecide = 0, pTrain = 0, pGrind = 0, pGoTown = 0, pBreak = 0;
        Map<Integer, Integer> trainByMap = new TreeMap<>();
        for (BotSM b : BotStorage.getAllBots().values()) {
            if (!(b instanceof TrainingBot tb)) {
                continue;
            }
            Character chr = tb.getChr();
            if (chr == null) {
                continue;
            }
            total++;
            int mapId = chr.getMapId();
            trainByMap.merge(mapId, 1, Integer::sum);
            switch (GCMovement.lodTier(mapId)) {
                case "full" -> full++;
                case "halo" -> halo++;
                case "dwell" -> dwell++;
                default -> coarse++;
            }
            switch (tb.phase) {
                case INIT -> pInit++;
                case IN_TOWN -> pTown++;
                case SHOP_TRAVEL, SHOP_DWELL, SHOP_RETURN -> pShop++;
                case DECIDE -> pDecide++;
                case GO_TRAIN -> pTrain++;
                case GRIND -> pGrind++;
                case GO_TOWN -> pGoTown++;
                case BREAK_TRAVEL, BREAK_REST -> pBreak++;
            }
        }

        out.add("=== TrainingBot LOD overview ===");
        out.add(String.format("training bots: %d   tiers: full=%d halo=%d dwell=%d coarse=%d",
                total, full, halo, dwell, coarse));
        out.add(String.format("phases: grind=%d goTrain=%d goTown=%d inTown=%d shop=%d decide=%d init=%d break=%d",
                pGrind, pTrain, pGoTown, pTown, pShop, pDecide, pInit, pBreak));
        out.add(String.format("-- FULL maps (real player present): %d --", GCMovement.observedFullMaps().size()));
        appendMapLines(out, GCMovement.observedFullMaps(), mapsById, trainByMap);
        out.add(String.format("-- HALO maps (portal-adjacent): %d --", GCMovement.observedHaloMaps().size()));
        appendMapLines(out, GCMovement.observedHaloMaps(), mapsById, trainByMap);
        return out;
    }

    private static void appendMapLines(List<String> out, Set<Integer> mapIds,
                                       Map<Integer, MapleMap> mapsById, Map<Integer, Integer> trainByMap) {
        if (mapIds.isEmpty()) {
            out.add("  (none)");
            return;
        }
        for (int mapId : new TreeSet<>(mapIds)) {
            MapleMap m = mapsById.get(mapId);
            String name = (m != null) ? (m.getStreetName() + " - " + m.getMapName()) : "?";
            int bots = trainByMap.getOrDefault(mapId, 0);
            out.add(String.format("  %d  %s  [%d training bot(s)]", mapId, name, bots));
        }
    }

    // Release combat + movement when the bot stops (FINISHED / converted / manually stopped).
    @Override
    public synchronized void stopScheduledTask() {
        ACTIVE_GRINDERS.remove(this);
        Character chr = getChr();
        if (chr != null) {
            if (chr.getChair() > 0) {
                cancelChair(chr); // mid-break conversion: don't leave the new type glued to a chair
            }
            grind.release(chr);
            clearTrainTarget(); // release this map's occupancy slot
            BotAttackDriver.clearBot(chr.getId());
            BotBuffDriver.clearBot(chr.getId());
            BotWanderSystem.stop(chr); // end any shop-dwell flavor wander
            TownLoiter.stop(chr); // free any town-loiter ledge claim + fidget before releasing movement
            GCMovement.disable(chr); // releases the shared movement lock the recorded engine needs
        }
        super.stopScheduledTask();
        log.info("[TrainingBot] stopped: " + (chr != null ? chr.getName() : "?"));
    }

    // ── SocialCommands / MovementCommands equivalents (gms 无录制引擎) ────────

    private static void emote(Character chr, int expression) {
        if (chr == null || chr.getMap() == null) {
            return;
        }
        chr.getMap().broadcastMessage(PacketCreator.facialExpression(chr, expression));
    }

    private static void speak(Character chr, String line) {
        if (chr == null || chr.getMap() == null) {
            return;
        }
        chr.getMap().broadcastMessage(PacketCreator.getChatText(chr.getId(), line, chr.getWhiteChat(), 0));
    }

    private static void fullChat(Character chr, String line) {
        speak(chr, line);
    }

    private static void cancelChair(Character chr) {
        if (chr != null) {
            chr.sitChair(-1);
        }
    }
}
