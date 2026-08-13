package org.gms.server.bot.types;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.EventType;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.maps.MapleMap;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.gms.util.Randomizer;

import java.util.Arrays;
import java.util.List;

/**
 * 社交型示范 bot：被观察时以随机间隔说台词 / 做表情；订阅同图聊天事件，
 * 冷却窗口内随机应答一句（i18n 台词池）。演示「事件入队 → tick 内消费」的
 * 标准工作流与观察门槛。
 */
@Slf4j
public class SocialBot extends BotSM {

    /** 两次主动动作的间隔（动作触发时一次性掷定，与 tick 节奏解耦）。 */
    private static final int ACTION_MIN_GAP_MS = 15_000;
    private static final int ACTION_MAX_GAP_MS = 45_000;
    /** 聊天应答冷却。 */
    private static final long REPLY_COOLDOWN_MS = 20_000;
    /** 友好表情子集（客户端 F1-F7 中 3、4 号是臭脸，刻意不用）。 */
    private static final int[] FRIENDLY_EMOTES = {1, 2, 5, 6};

    private static final String LINE_POOL_KEY = "bot.social.line.pool";

    /** 下一次主动动作的时刻：未观察期间不推进，玩家到场后立即动作。 */
    private volatile long nextActionMs = 0;
    private volatile long lastReplyMs = 0;

    public SocialBot(Character character) {
        super(character);
        this.botType = "SOCIAL_BOT";
        subscribeChat();
    }

    @Override
    protected void onScheduledStart() {
        // stopScheduledTask 会退订全部事件：重启时必须重新订阅，否则永久失聪。
        // subscribe 幂等（addIfAbsent），重复调用安全。
        subscribeChat();
    }

    private void subscribeChat() {
        BotEventBus.getInstance().subscribe(EventType.CHAT, this);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (!getRunning() || state != BotState.RUNNING) {
            return; // PAUSE/TRADING/FINISHED 期间不做任何氛围动作
        }
        boolean repliedThisTick = processQueuedEvents() != null; // 先消费玩家聊天事件（应答）

        long now = System.currentTimeMillis();
        if (now < nextActionMs) {
            return; // 动作间隔未到
        }
        if (repliedThisTick) {
            // 本 tick 刚应答过：跳过主动动作，避免同一拍双广播（更像人）
            rollNextAction(now);
            return;
        }
        if (!checkMainPlayersOnMap()) {
            return; // 未观察：不表演（氛围动作的门槛）；nextActionMs 不推进，玩家到场即动作
        }
        if (Randomizer.nextBoolean()) {
            sayLine(pickLine());
        } else {
            emote(FRIENDLY_EMOTES[Randomizer.nextInt(FRIENDLY_EMOTES.length)]);
        }
        rollNextAction(now);
    }

    @Override
    public void handleEvent(GameEvent event) {
        if (event.getType() != EventType.CHAT) {
            return;
        }
        if (event.getSourceCharacterId() == getChr().getId()) {
            return; // 自己说的话不触发应答
        }
        MapleMap map = getChr().getMap();
        if (map == null) {
            return; // 已离图/销毁窗口：不应答，也不消耗冷却
        }
        long now = System.currentTimeMillis();
        if (now - lastReplyMs < REPLY_COOLDOWN_MS) {
            return; // 冷却内不重复应答
        }
        lastReplyMs = now;
        sayLine(pickLine());
    }

    /** 一次性掷定下一次主动动作时刻。 */
    private void rollNextAction(long now) {
        nextActionMs = now + Randomizer.rand(ACTION_MIN_GAP_MS, ACTION_MAX_GAP_MS);
    }

    private String pickLine() {
        String pool = I18nUtil.getMessage(LINE_POOL_KEY);
        List<String> lines = parsePool(pool);
        if (lines.isEmpty()) {
            return "...";
        }
        return lines.get(Randomizer.nextInt(lines.size()));
    }

    private List<String> parsePool(String pool) {
        if (pool == null || pool.isBlank()) {
            return List.of();
        }
        // 过滤空白项：畸形池（如 ","、"a,,b"）不会抽到空气泡；空数组由调用方回退
        return Arrays.stream(pool.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private void sayLine(String line) {
        MapleMap map = getChr().getMap();
        if (map == null) {
            return;
        }
        map.broadcastMessage(PacketCreator.getChatText(getChr().getId(), line, getChr().getWhiteChat(), 0));
    }

    private void emote(int expression) {
        MapleMap map = getChr().getMap();
        if (map == null) {
            return;
        }
        map.broadcastMessage(PacketCreator.facialExpression(getChr(), expression));
    }
}
