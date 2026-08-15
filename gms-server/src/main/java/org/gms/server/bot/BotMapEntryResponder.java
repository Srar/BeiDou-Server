package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.EventSubscriber;
import org.gms.server.bot.event.EventType;
import org.gms.server.bot.event.GameEvent;
import org.gms.util.I18nUtil;
import org.gms.util.Randomizer;

import java.util.Set;

/**
 * MAP_ENTERED 单例订阅者（对应 SoloMapling 的 BotMapEntryResponder）：
 * 真实玩家进图时把同图 bot 的下一次 tick 拉前（150-700ms 抖动），
 * 让 bot 在共享地图的瞬间就有反应。
 * <p>
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
                bot.nudgeSoon(NUDGE_MIN_MS + Randomizer.nextInt(NUDGE_MAX_MS - NUDGE_MIN_MS + 1));
            }
        }
    }

    @Override
    public boolean matchesFilter(GameEvent event) {
        return event.getType() == EventType.MAP_ENTERED;
    }
}
