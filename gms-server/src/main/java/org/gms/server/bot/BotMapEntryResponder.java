package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.EventSubscriber;
import org.gms.server.bot.event.EventType;
import org.gms.server.bot.event.GameEvent;
import org.gms.util.I18nUtil;
import org.gms.util.Randomizer;

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
        for (BotSM bot : BotStorage.getAllBots().values()) {
            // world + channel + mapId 三重匹配：同 world 下不同频道的同图号是不同地图实例，
            // 漏掉 channel 维度会跨频道误 nudge（与 BotSM.matchesFilter 口径一致）
            if (bot.getChr().getWorld() == event.getWorld()
                    && bot.getChr().getClient() != null
                    && bot.getChr().getClient().getChannel() == event.getChannel()
                    && bot.getChr().getMapId() == event.getMapId()) {
                bot.nudgeSoon(NUDGE_MIN_MS + Randomizer.nextInt(NUDGE_MAX_MS - NUDGE_MIN_MS + 1));
            }
        }
    }

    @Override
    public boolean matchesFilter(GameEvent event) {
        return event.getType() == EventType.MAP_ENTERED;
    }
}
