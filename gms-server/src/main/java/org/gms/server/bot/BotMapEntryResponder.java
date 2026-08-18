package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.EventSubscriber;
import org.gms.server.bot.event.EventType;
import org.gms.server.bot.event.GameEvent;
import org.gms.util.I18nUtil;
import org.gms.util.Randomizer;

import java.util.Set;

/**
 * MAP_ENTERED 单例订阅者（对应 SoloMapling 的 BotMapEntryResponder）。两个方向，一次 nudge：
 * <ul>
 * <li>A) 真实玩家进入有 bot 的图——onEvent(MAP_ENTERED)：把同图每个运行中 bot 的
 * 下一次宏 tick 拉前（150-700ms 抖动），而不是等完 2-6s/10s 慢轮盘。</li>
 * <li>B) bot 进入一张已有真人的图——{@link #onBotArrivedObserved(Character)}：只 nudge
 * 到达的这只 bot。其移动已由 GCMovementDriver.onMapChange 当拍置为观察档（FULL），
 * 这里同时唤醒它的宏观脑。</li>
 * </ul>
 * 注意：事件发布是同步的且无异常保护——订阅者必须自保：重活转虚拟线程，
 * 异常自行吞掉，否则会炸掉玩家的进图线程。
 */
@Slf4j
public final class BotMapEntryResponder implements EventSubscriber {

    private static final int NUDGE_MIN_MS = 150;
    private static final int NUDGE_MAX_MS = 700;

    private static final BotMapEntryResponder INSTANCE = new BotMapEntryResponder();

    private BotMapEntryResponder() {
    }

    public static void register() {
        BotEventBus.getInstance().subscribe(EventType.MAP_ENTERED, INSTANCE);
    }

    @Override
    public void onEvent(GameEvent event) {
        try {
            BotExecutors.runAsync(() -> nudgeBotsOnMap(event));
        } catch (Throwable t) {
            log.warn(I18nUtil.getLogMessage("BotMapEntryResponder.nudge.error"), t);
        }
    }

    private void nudgeBotsOnMap(GameEvent event) {
        // F9 gms 增强：按 mapId 索引取候选，不再全量遍历注册表。SoloMapling 源遍历
        // map.getAllPlayers()（同为 O(图上角色数)）；gms 移植曾简化为
        // BotStorage.getAllBots() 全表扫描（O(全部活跃 bot)），2500+ bot 时真人每次
        // 进图都线性扫全表——此处经 BotStorage 的 mapId 索引恢复 O(该图 bot 数)。
        Set<Integer> ids = BotStorage.getBotIdsOnMap(event.getMapId());
        if (ids.isEmpty()) {
            return;
        }
        for (int id : ids) {
            BotSM bot = BotStorage.getBotById(id);
            if (bot == null) {
                continue; // 索引残留（销毁与 tick 刷新竞态）：注册表已无此 bot，无害跳过
            }
            // world + channel 双重校验保留：同图号在不同频道/世界是不同地图实例，
            // 索引只承担 mapId 维度，跨频道同图号防误伤仍由本层校验兜底
            // （与 BotSM.matchesFilter 口径一致）。
            if (bot.getChr().getWorld() == event.getWorld()
                    && bot.getChr().getClient() != null
                    && bot.getChr().getClient().getChannel() == event.getChannel()) {
                nudge(bot);
            }
        }
    }

    /**
     * 方向 B（SoloMapling BotMapEntryResponder.onBotArrivedObserved 完整移植）：
     * bot 刚进入一张已有真人的图，立即唤醒它的宏观脑——移动已由
     * {@code GCMovementDriver.onMapChange} 当拍置为观察档（FULL），宏观 FSM 也应在
     * 150-700ms 抖动窗口内跑下一拍，而不是等完 2-6s/10s 慢轮盘。
     * <p>
     * 由移动 tick 线程调用；安全——nudgeSoon 只重排轮盘上的下一次触发，
     * 绝不内联执行 FSM，且自带去抖与运行态/交易态门控。
     */
    public static void onBotArrivedObserved(Character bot) {
        try {
            if (bot == null) {
                return;
            }
            BotSM sm = BotStorage.getBotById(bot.getId());
            if (sm != null) {
                INSTANCE.nudge(sm);
            }
        } catch (Throwable ignored) {
            // 绝不把 nudge 失败传播回移动 tick 线程/进图线程
            log.warn(I18nUtil.getLogMessage("BotMapEntryResponder.nudge.error"), ignored);
        }
    }

    /** 单 bot nudge：仅对运行中 bot 把下一次宏 tick 拉前（150-700ms 抖动）。 */
    private void nudge(BotSM bot) {
        if (!bot.getRunning()) {
            return;
        }
        bot.nudgeSoon(NUDGE_MIN_MS + Randomizer.nextInt(NUDGE_MAX_MS - NUDGE_MIN_MS + 1));
    }

    @Override
    public boolean matchesFilter(GameEvent event) {
        return event.getType() == EventType.MAP_ENTERED;
    }
}
