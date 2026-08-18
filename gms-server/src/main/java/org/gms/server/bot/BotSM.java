package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.InventoryType;
import org.gms.server.Trade;
import org.gms.server.bot.attack.ThrowingStarSelector;
import org.gms.server.bot.event.BotEventBuffer;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.EventSubscriber;
import org.gms.server.bot.dialogue.BotDialogueHandler;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.bot.gcmove.LodCounts;
import org.gms.server.bot.messaging.ChatMessage;
import org.gms.server.bot.messaging.MessageQueue;
import org.gms.server.bot.trade.BotTradeHandler;
import org.gms.server.bot.trade.BotTradeInventory;
import org.gms.server.bot.trade.BotTradeLogic;
import org.gms.server.bot.trade.BotTradeSM;
import org.gms.server.bot.trade.BotTradeWants;
import org.gms.server.bot.commands.SocialCommands;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.gms.util.Randomizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bot 状态机基类（参考 SoloMapling 的 BotSM 移植）。
 * <p>
 * 每个 bot 都是「真实 Character 对象 + 挂在上面的这个状态机」。本类承载跨类型
 * 共用的状态推进义务：IDLE→RUNNING 唤醒、掉线→FINISHED→IDLE 拆卸、观察分级调速、
 * 无睡眠等待（waitFor）、事件缓冲的消费侧；子类覆写 {@link #updateState()} 时必须
 * 先调用 {@code super.updateState()}，再追加自己的子状态机——漏调会失去掉线拆卸与
 * 事件入口。
 */
@Slf4j
public abstract class BotSM implements EventSubscriber {

    public enum BotState {
        IDLE,
        RUNNING,
        PAUSE,
        TRADING,
        FINISHED;
    }

    private final Character character;

    /** 内存可见性：多个线程族并发读写，必须 volatile（参考实现此处是普通 boolean，属已知隐患，移植时已修复）。 */
    private volatile boolean running;

    /** 同 running：tick 线程写、GM/事件线程读（convertBotType 拒绝判断、nudge 门控、列表展示）。 */
    protected volatile BotState state;
    protected String botType;

    // ── 交易 / 交互 / 调试（P5-A 按 SoloMapling BotSM 补齐；BotTrade* / MessageQueue 由
    //    并行代理 P5-B / P5-C 移植，缺类属预期） ─────────────────────────────
    private final int chosenStarId;
    private BotDebugHandler debugger;
    private BotInteractorsHandler interactors = new BotInteractorsHandler();
    private BotTradeHandler tradeHandler;
    protected String dialoguePath;
    private BotDialogueHandler dialogueHandler;

    private static MessageQueue messageQueue = MessageQueue.getInstance();

    private BotTradeSM botTradeSM = null;
    private BotTradeInventory tradeInventory = new BotTradeInventory();
    private BotTradeWants tradeWants = new BotTradeWants();
    private BotTradeSM.TradeMode currentTradeMode = BotTradeSM.TradeMode.NULL;
    private volatile boolean movementInterrupted = false;
    protected Trade.TradeResult lastTradeResult = null;
    protected Character lastTradedCharacter = null;

    private static final long NUDGE_DEBOUNCE_MS = 1500;
    private static final int EVENT_BUFFER_CAPACITY = 100;

    private volatile long waitUntilMs = 0;
    private volatile long lastNudgeMs = 0;
    private volatile long currentDelay;
    private volatile boolean cadenceObserved = true; // startScheduledTask 以正常 2-6s 节奏起步

    /**
     * 空闲站立广播去重（gms 增强 F3）：上次已广播的 stance，初始哨兵值保证首拍必广播。
     * 对应 SoloMapling MovementCommands.BotIdleStandingUpdate 的每 tick 站立刷新；
     * 源每个宏 tick 无条件构造并广播刷新包，gms 增强为「未观察图跳过 + stance 去重」——
     * 同帧重复广播无观感收益只烧 CPU，观察图观感不变（stance 变化或首拍仍广播）。
     */
    private int lastIdleStance = Integer.MIN_VALUE;

    /**
     * F9 gms 增强：上次已写入 BotStorage 地图索引的 mapId（初始 -1 表示尚未刷新过）。
     * tick 轮里与当前 mapId 比对，仅换图时动索引，避免每拍空转。
     */
    private int lastIndexedMapId = -1;


    private final BotEventBuffer eventBuffer;

    public BotSM(Character chr) {
        this.character = chr;
        this.running = false;
        this.state = BotState.IDLE;
        this.currentDelay = getRandomDelay();
        this.eventBuffer = new BotEventBuffer(EVENT_BUFFER_CAPACITY);
        // 按源补齐：交易/调试/对话句柄与一次性星镖选择（源在构造期完成，createBot 装饰先于本构造）。
        this.tradeHandler = new BotTradeHandler(chr);
        this.debugger = new BotDebugHandler(chr);
        this.dialogueHandler = new BotDialogueHandler(chr);
        // 星镖选择需读已装备武器；单测用 Mockito 模拟的 Character 未初始化装备栏
        //（getInventory 返回 null），此处先判空再选，避免 mock 角色构造期 NPE。
        this.chosenStarId = chr.getInventory(InventoryType.EQUIPPED) != null
                ? ThrowingStarSelector.selectFor(chr)
                : 0;
    }

    /**
     * 所有（重）调度路径共用的唯一 tick 体：waitFor 期间整拍跳过（连调速、
     * 空闲站立都不做——bot 完全惰性）；异常吞掉，绝不杀死调度。
     */
    private final Runnable tickRunnable = () -> {
        try {
            if (isWaiting()) {
                return;
            }
            refreshMapIndexIfNeeded();
            updateState();
        } catch (Exception e) {
            log.warn(I18nUtil.getLogMessage("BotSM.tick.error", getChr().getName()), e);
        }
    };

    /**
     * F9 gms 增强：mapId 索引刷新。仅当当前图与上次索引不同才动集合（首拍必刷一次，
     * 把 addActiveBot 时点与首拍之间可能发生的换图同步进索引）。索引残留无害——
     * 消费方 {@link BotMapEntryResponder} 经 getBotById 判空跳过。
     */
    private void refreshMapIndexIfNeeded() {
        int currentMapId = getChr().getMapId();
        if (currentMapId != lastIndexedMapId) {
            lastIndexedMapId = currentMapId;
            BotStorage.refreshBotMapIndex(getChr().getId(), currentMapId);
        }
    }

    // ── 无睡眠等待（waitFor 门控） ──────────────────────────────────────────
    // FSM 需要停顿就 waitFor(ms) 然后从 tick 里 return；tick 轮在等待期间整拍跳过，
    // 不持有任何线程。等待对 nudge 免疫（这正是 pause 的意义），需要反应灵敏的
    // 等待要保持短。

    public void waitFor(long ms) {
        waitUntilMs = System.currentTimeMillis() + ms;
    }

    protected void waitForRandom(long loMs, long hiMs) {
        waitFor(loMs + Randomizer.nextInt((int) Math.max(1, hiMs - loMs + 1)));
    }

    protected boolean isWaiting() {
        return System.currentTimeMillis() < waitUntilMs;
    }

    // ── 基本访问器 ──

    public Character getChr() {
        return this.character;
    }

    public String getBotType() {
        return this.botType;
    }

    /** 对话 YAML 资源路径（BotDialogueHandler 等子包调用方经此读取）。 */
    public String getDialoguePath() {
        return this.dialoguePath;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean getRunning() {
        return running;
    }

    public BotState getState() {
        return this.state;
    }

    /** 公开以便子类/测试使用（如构造 TRADING 态以验证 convertBotType 拒绝逻辑）。 */
    public void setState(BotState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    /** 运行中且仍在注册表里（未被销毁）——RUNNING 态的存活判据。 */
    public boolean checkRunningOnline() {
        return getRunning() && BotStorage.botLoggedIn(this.getChr().getId());
    }

    /**
     * 「图上有真实玩家吗」——优先走 LOD 观察器（O(1) 查 FULL tier，等价 SoloMapling
     * {@code LodCounts.trackerRunning()} → {@code LodCounts.isMapFull(mapId)}），观察轮尚未
     * 启动（无 GC-movement bot，如裸 dev 刷怪）时回退线性扫描。回退分支遍历前拷贝快照：
     * getCharacters() 返回底层集合的活视图，直接遍历会与玩家进出图并发抛
     * ConcurrentModificationException（异常被 tickRunnable 吞掉即整拍丢失，连掉线检测一起跳过）。
     */
    public boolean checkMainPlayersOnMap() {
        if (LodCounts.trackerRunning()) {
            return LodCounts.isMapFull(character.getMapId());
        }
        MapleMap map = character.getMap();
        if (map == null) {
            return false;
        }
        for (Character chr : new ArrayList<>(map.getCharacters())) {
            if (!BotHelpers.isBot(chr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 状态骨架。子类必须覆写并在方法体里调用 super.updateState()，
     * 然后在 RUNNING 分支之后追加自己的子 FSM。
     */
    public void updateState() {
        debugger.handleDebugPrints(this);
        switch (state) {
            case IDLE:
                if (checkRunningOnline()) {
                    setState(BotState.RUNNING);
                    log.info(I18nUtil.getLogMessage("BotSM.state.running", getChr().getName()));
                }
                break;
            case RUNNING:
                checkPrioritySpeed();
                if (!checkRunningOnline()) {
                    setState(BotState.FINISHED);
                    log.info(I18nUtil.getLogMessage("BotSM.state.finished", getChr().getName()));
                    break;
                }
                // 空闲站立刷新（等价 SoloMapling MovementCommands.BotIdleStandingUpdate，
                // 已随 MovementCommands 移植，见 org.gms.server.bot.replay.MovementCommands；
                // 此处保留状态机内联版，用 Character.broadcastStance() 广播一次站立包，
                // 让同图玩家看到的 bot 保持站立帧不僵死）。
                // gms 增强（F3）：
                //   1) 观察门控——源 BotIdleStandingUpdate 同样带 LOD 观察门控
                //      （trackerRunning && !isMapActive 直接 return）；此处复用本类
                //      checkMainPlayersOnMap()（观察轮运行时走 LodCounts O(1)），
                //      无人观察的图省去每 tick 构造 packet + 遍历同图角色的广播开销。
                //   2) stance 去重——stance 与上次已广播值相同时跳过（源每 tick 都构造
                //      刷新包；连续 tick 间站立帧不变，同帧重播无观感收益）。
                if (getChr().getMap() != null && checkMainPlayersOnMap()) {
                    int stance = getChr().getStance();
                    if (stance != lastIdleStance) {
                        lastIdleStance = stance;
                        getChr().broadcastStance();
                    }
                }
                if (verifyTradePartner()) {
                    tradeInitialized(getTradeMode());
                    setState(BotState.TRADING);
                }
                break;
            case PAUSE:
                // 参考实现中进入 PAUSE 的唯一自动迁移已被注释（由未观察减速取代其职责）；
                // 本移植保留该态仅作「有人进图即恢复」的出口。掉线同样必须拆卸
                //（否则空图 + 掉线的 PAUSE bot 会永久停留）。
                if (!checkRunningOnline()) {
                    setState(BotState.FINISHED);
                    log.info(I18nUtil.getLogMessage("BotSM.state.finished", getChr().getName()));
                    break;
                }
                if (checkMainPlayersOnMap()) {
                    setState(BotState.RUNNING);
                    log.info(I18nUtil.getLogMessage("BotSM.state.resume", getChr().getName()));
                }
                break;
            case TRADING:
                // 掉线必须拆卸（TRADING 期间 bot 同样会被销毁）—— gms 保留增强。
                if (!checkRunningOnline()) {
                    cleanupTradeState();
                    setState(BotState.FINISHED);
                    log.info(I18nUtil.getLogMessage("BotSM.state.finished", getChr().getName()));
                    break;
                }
                /*
                1. completed, has trade partner = should not be possible
                2. not completed, has trade partner = still trading continuously - TRADING

                3. completed, no trade partner = successfully finished trade. go to running
                4. not completed, no partner = canceled / trade declined = go to running - RUNNING
                 */
                if (isTradeComplete() && !verifyTradePartner() ||
                        !isTradeComplete() && !verifyTradePartner() && !isOfferAccepted()) {
                    cleanupTradeState();
                    waitFor(2000); // settle beat after the trade closes (gated, no thread held)
                    setState(BotState.RUNNING);
                    break;
                }
                updateTradeSM();
                break;
            case FINISHED:
                // 单次 tick 内的瞬态拆卸态：不阻塞、不停留，同拍落到 IDLE。
                this.setRunning(false);
                getInteractors().resetRespondant();
                stopScheduledTask();
                setState(BotState.IDLE);
                break;
            default:
                throw new IllegalStateException(I18nUtil.getExceptionMessage("BotSM.exception.unexpected_state", state));
        }
    }

    // ── 调度 API（挂中央 tick 轮） ──────────────────────────────────────────

    /** 把 tick 挂上轮盘（首 tick 无延迟）。注册是 keep-if-present。 */
    public synchronized void startScheduledTask() {
        startScheduledTask(0);
    }

    public synchronized void startScheduledTask(long initialDelayMs) {
        BotTickService.register(getChr().getId(), tickRunnable, initialDelayMs, getRandomDelay());
        onScheduledStart();
    }

    /**
     * 启动（或重启）钩子：每次把 tick 挂上轮盘后调用。子类可在此做幂等的
     * 事件订阅——stopScheduledTask 会退订全部事件，若订阅只发生在构造器，
     * 「stop → start」后 bot 将永久失聪。
     */
    protected void onScheduledStart() {
    }

    /** 改节奏：周期不变则短路。 */
    public synchronized void updateScheduleDelay(long newDelayMs) {
        if (this.currentDelay == newDelayMs) {
            return;
        }
        this.currentDelay = newDelayMs;
        BotTickService.reschedule(getChr().getId(), newDelayMs);
    }

    /**
     * 把下一次宏 tick 拉到 ~initialDelayMs 之后，随后回到正常节奏——bot 与真实玩家
     * 同图时能立即响应，而不是等完整个慢轮盘。去抖窗口防止走动的玩家反复重置下一次
     * tick 而饿死 FSM。只挪轮盘上的下一次触发，绝不直接调 updateState，保持
     * 「无重叠 tick」不变量。
     */
    public synchronized void nudgeSoon(long initialDelayMs) {
        if (!getRunning() || state == BotState.TRADING || state == BotState.FINISHED) {
            return; // 不打断交易或正在拆卸的 bot
        }
        if (!BotTickService.isRegistered(getChr().getId())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastNudgeMs < NUDGE_DEBOUNCE_MS) {
            return;
        }
        lastNudgeMs = now;
        long period = getRandomDelay(); // 稳态仍是随机节奏；只有下一次触发被拉前
        this.currentDelay = period;
        this.cadenceObserved = true; // nudge 重建了正常节奏；下一 tick 重新评估
        BotTickService.nudge(getChr().getId(), initialDelayMs, period);
    }

    /**
     * 节奏档跟踪：只在档位翻转时切回观察档；但每个未观察 tick 都重新断言低档——
     * 阶段相关的低档延迟（子类可覆写 {@link #lowPriorityDelayMs()}）在阶段变化后
     * 一个 tick 内即生效。
     */
    public void checkPrioritySpeed() {
        boolean observed = checkMainPlayersOnMap();
        if (observed) {
            if (!cadenceObserved) {
                cadenceObserved = true;
                setPriorityNormal();
            }
            return;
        }
        cadenceObserved = false;
        setPriorityLow();
    }

    /** 未观察档节奏（可覆写）：9-12s 随机抖动，避免同批 bot 长期同拍。 */
    protected long lowPriorityDelayMs() {
        return 9000 + Randomizer.nextInt(3000);
    }

    public void setPriorityLow() {
        updateScheduleDelay(lowPriorityDelayMs());
    }

    public void setPriorityHigh() {
        updateScheduleDelay(2000);
    }

    public void setPriorityNormal() {
        updateScheduleDelay(getRandomDelay());
    }

    /** 观察档节奏：2000 + nextInt(4000) = 2-6s。 */
    private long getRandomDelay() {
        return 2000 + Randomizer.nextInt(4000);
    }

    /** 停止调度：退订全部事件 + 从轮盘移除。在途 tick 允许跑完。可重复调用。 */
    public synchronized void stopScheduledTask() {
        if (BotTickService.isRegistered(getChr().getId())) {
            log.info(I18nUtil.getLogMessage("BotSM.scheduler.stop", this.getChr().getName()));
        }
        BotEventBus.getInstance().unsubscribeAll(this);
        // 对齐源：关停调度前清掉 bot 的粉笔黑板（等价 SoloMapling SocialCommands.botClearChalkboard）。
        // 拆卸窗口内 map 可能已置 null，加判空避免 teardown NPE（源无此判空，属 gms 安全增强）。
        if (getChr().getMap() != null) {
            SocialCommands.botClearChalkboard(this.getChr());
        }
        BotTickService.unregister(getChr().getId());
    }

    // ── 事件链路：EventBus → onEvent 入队 → tick 内 processQueuedEvents 消费 ──

    @Override
    public void onEvent(GameEvent event) {
        eventBuffer.add(event); // 只入队，不内联处理
    }

    @Override
    public boolean matchesFilter(GameEvent event) {
        if (event.getWorld() != getChr().getWorld()) {
            return false;
        }
        Client client = getChr().getClient();
        // 订阅者契约：matchesFilter 跑在发布线程上且总线无异常保护——任何异常都会
        // 炸掉玩家聊天/进图线程，因此半初始化（client 未设）的 bot 直接拒绝。
        if (client == null) {
            return false;
        }
        if (event.getChannel() != client.getChannel()) {
            return false;
        }
        MapleMap map = getChr().getMap();
        // map 为 null 时宽松通过：bot 尚未落图（创建/销毁窗口），事件入队无害
        return map == null || event.getMapId() == map.getId();
    }

    /** 在子类的状态分支里调用：每次 poll 一个事件处理。返回本次处理的事件（无则 null）。 */
    public GameEvent processQueuedEvents() {
        GameEvent event = eventBuffer.poll();
        if (event != null) {
            handleEvent(event);
        }
        return event;
    }

    /** 基类空壳，子类覆写。 */
    public void handleEvent(GameEvent event) {
        // 子类按事件类型响应
    }

    public boolean hasQueuedEvents() {
        return !eventBuffer.isEmpty();
    }

    // ── 交易挂钩（P5-B 回填：对齐 SoloMapling BotSM 交易钩子） ──────────────

    /** 是否有交易伙伴正在请求。 */
    protected boolean verifyTradePartner() {
        return tradeHandler.verifyTradePartner();
    }

    protected boolean isOfferAccepted() {
        return botTradeSM != null && botTradeSM.isOfferAccepted();
    }

    protected boolean isTradeComplete() {
        return botTradeSM != null && botTradeSM.isTradeComplete();
    }

    protected void updateTradeSM() {
        if (botTradeSM != null) {
            botTradeSM.update();
        }
    }

    protected void cleanupTradeState() {
        botTradeSM = null;
        BotTradeLogic.clearTradeRequest(getChr());
        tradeHandler.resetTradePartner();
        discardTradeSM();
    }

    // ── 测试/诊断访问器 ──

    public long getCurrentDelay() {
        return currentDelay;
    }

    public long getWaitUntilMs() {
        return waitUntilMs;
    }

    public boolean isCadenceObserved() {
        return cadenceObserved;
    }

    // ── P5-A 补齐：SoloMapling BotSM 缺失的字段访问器与交易/交互挂钩 ─────────
    // 逐方法对齐源实现（对照 SoloMapling BotSM 行号）；交易相关按源签名引用
    // org.gms.server.bot.trade（P5-B 落地后编译），消息引用 org.gms.server.bot.messaging（P5-C）。

    /** 该 bot 创建期选定的一次性星镖（非爪投掷手为 0）。 */
    public int getChosenStarId() {
        return this.chosenStarId;
    }

    public BotInteractorsHandler getInteractors() {
        return interactors;
    }

    public BotTradeHandler getTradeHandler() {
        return tradeHandler;
    }

    protected BotDebugHandler getDebugger() {
        return debugger;
    }

    public BotDialogueHandler getDialogueHandler() {
        return dialogueHandler;
    }

    public BotTradeInventory getTradeInventory() {
        return tradeInventory;
    }

    public BotTradeWants getTradeWants() {
        return tradeWants;
    }

    public void interruptMovement() {
        this.movementInterrupted = true;
    }

    public boolean isMovementInterrupted() {
        return this.movementInterrupted;
    }

    public void clearMovementInterrupt() {
        this.movementInterrupted = false;
    }

    // 源内实现依赖 MessageQueue（P5-C 移植到 org.gms.server.bot.messaging）。
    protected void processMessages() {
        log.info("BotSM processMessages");
        try {
            ChatMessage message = messageQueue.getMessageNonBlocking("secondary");
            if (message.getSender() == getInteractors().getRespondant()) {
                log.info("This Message is from Respondant: {}, Msg: {}", getInteractors().getRespondant().getName(), message);
            }
        } catch (Exception e) {
            log.warn("BotSM processMessages error for {}", getChr().getName(), e);
        }
    }

    public List<MapObject> detectItems() {
        // 源为空壳（todo），保持返回 null
        return null;
    }

    protected boolean checkIfNotRunningOrPaused() {
        if (!this.getRunning()) {
            return true;
        }
        if (state == BotState.PAUSE) {
            return true;
        }
        return false;
    }

    public void displayCommands(Character chr) {
        List<String> hint = List.of(getChr().getName());
        // 等价 SocialCommands.talkCygnusGuideCommands(chr, hint)：gms 用 PacketCreator.talkGuide
        if (chr.getClient() != null) {
            chr.getClient().sendPacket(PacketCreator.talkGuide("1. " + hint.get(0) + "\r\n"));
        }
    }

    protected void checkForTrades() {
        boolean acceptedTrade = BotTradeLogic.checkTradeQueue(getChr());
        if (acceptedTrade) {
            tradeHandler.setTradePartner(tradeHandler.getTradePartnerRaw());
            log.debug("Accepted Trade");
        }
    }

    public void setTradeMode(BotTradeSM.TradeMode tradeMode) {
        this.currentTradeMode = tradeMode;
    }

    protected BotTradeSM.TradeMode getTradeMode() {
        return this.currentTradeMode;
    }

    protected void tradeInitialized(BotTradeSM.TradeMode tradeMode) {
        startTradeSM(tradeMode);
    }

    protected void startTradeSM() {
        if (botTradeSM == null) {
            botTradeSM = new BotTradeSM(this); // Create only when entering TRADING
        }
    }

    protected void startTradeSM(BotTradeSM.TradeMode mode) {
        botTradeSM = new BotTradeSM(this, mode);
    }

    protected void discardTradeSM() {
        botTradeSM = null;
    }

    public void resetLastTradeResult() {
        lastTradeResult = null;
    }

    public void setLastTradeResult(Trade.TradeResult result) {
        lastTradeResult = result;
    }

    public Trade.TradeResult getLastTradeResult() {
        return lastTradeResult;
    }

    public void setLastTradedCharacter(Character character) {
        lastTradedCharacter = character;
    }

    public Character getLastTradedCharacter() {
        return lastTradedCharacter;
    }

    public void resetLastTradedCharacter() {
        lastTradedCharacter = null;
    }

    /** 环境氛围动作可用性（源为空壳，保持 false；子类可覆写）。 */
    public boolean isAvailableForAmbientActions() {
        return false;
    }
}
