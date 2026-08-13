package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.server.bot.event.BotEventBuffer;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.EventSubscriber;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.maps.MapleMap;
import org.gms.util.I18nUtil;
import org.gms.util.Randomizer;

import java.util.ArrayList;
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

    private static final long NUDGE_DEBOUNCE_MS = 1500;
    private static final int EVENT_BUFFER_CAPACITY = 100;

    private volatile long waitUntilMs = 0;
    private volatile long lastNudgeMs = 0;
    private volatile long currentDelay;
    private volatile boolean cadenceObserved = true; // startScheduledTask 以正常 2-6s 节奏起步

    private final BotEventBuffer eventBuffer;

    public BotSM(Character chr) {
        this.character = chr;
        this.running = false;
        this.state = BotState.IDLE;
        this.currentDelay = getRandomDelay();
        this.eventBuffer = new BotEventBuffer(EVENT_BUFFER_CAPACITY);
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
            updateState();
        } catch (Exception e) {
            log.warn(I18nUtil.getLogMessage("BotSM.tick.error", getChr().getName()), e);
        }
    };

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
     * 「图上有真实玩家吗」——直接扫地图角色列表找非 bot（O(n)，n = 地图人数）。
     * 遍历前拷贝快照：getCharacters() 返回底层集合的活视图，直接遍历会与
     * 玩家进出图并发抛 ConcurrentModificationException（异常被 tickRunnable 吞掉
     * 即整拍丢失，连掉线检测一起跳过）。
     * 无 ObserverTracker（BeiDou 版未移植 LOD 系统）时这就是判定实现。
     */
    public boolean checkMainPlayersOnMap() {
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
                if (verifyTradePartner()) {
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
                // 交易子系统为后续版本；verifyTradePartner 默认恒 false，此分支正常不可达。
                // 掉线必须拆卸（TRADING 期间 bot 同样会被销毁）。
                if (!checkRunningOnline()) {
                    cleanupTradeState();
                    setState(BotState.FINISHED);
                    log.info(I18nUtil.getLogMessage("BotSM.state.finished", getChr().getName()));
                    break;
                }
                if (!verifyTradePartner() && !isOfferAccepted()) {
                    cleanupTradeState();
                    waitFor(2000); // 交易收尾的稳定拍（门控，不持有线程）
                    setState(BotState.RUNNING);
                    break;
                }
                updateTradeSM();
                break;
            case FINISHED:
                // 单次 tick 内的瞬态拆卸态：不阻塞、不停留，同拍落到 IDLE。
                this.setRunning(false);
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

    // ── 交易挂钩（预留：9 态交易子机为后续版本，届时替换以下三个钩子） ──────

    /** 是否有交易伙伴正在请求。v1 恒 false——TRADING 分支不可达。 */
    protected boolean verifyTradePartner() {
        return false;
    }

    protected boolean isOfferAccepted() {
        return false;
    }

    protected void updateTradeSM() {
        throw new UnsupportedOperationException(I18nUtil.getExceptionMessage("BotSM.exception.trade_not_implemented"));
    }

    protected void cleanupTradeState() {
        // 预留：交易状态清理
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
}
