package org.gms.server.bot.dialogue;

import org.gms.client.Character;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.BotTiming;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.types.SocialBot;
import org.gms.server.bot.types.TownWandererBot;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 环境 bot 与 bot 对话：两只附近空闲的城镇 bot 偶尔演出一段简短自然的一来一回
 * （2-4 轮），让城镇在读档前就显得有人气，有真人观看时更生动。入口 maybeStartChatter(bot)，
 * 由参与者的既有空闲 tick 调用——不新增线程、不新增 ticker（BotFlavor 的模式）。
 * 全程只发包，硬门控在「有真人能看见」上。
 * <p>
 * 与 SocialHotPotatoManager（Henesys 地图上的单行环境叫卖）不同：本类驱动真正成对、
 * 交替轮流、覆盖所有城镇的闲聊，挂在宏 tick 上。闲聊中的对子通过 isEngaged 使
 * isAvailableForAmbientActions()==false，从而自动排除于 hot-potato 叫卖、LevelUpCongrats、
 * BotFlavor 与玩家搭讪——玩家交互永远优先。
 */
public final class BotChatter {

    private BotChatter() {
    }

    // ---- 调参旋钮（public volatile，供未来 !env chatter 读取） ----
    /** 每次合格且被观察的 tick，尝试开启闲聊的概率（安静 tick 不消耗冷却）。 */
    public static volatile double CHATTER_CHANCE = 0.10;
    /** 只有这么近的 bot 才是可信伙伴（可见地相邻，不是隔图喊话）。 */
    public static volatile int CHATTER_RADIUS = 180;
    /** 自过期参与期限：中途死亡（release 拍被门控丢弃）的对子在此窗口内自行释放。 */
    public static volatile long MAX_CHATTER_MS = 22_000;
    /** 一个 bot 两次闲聊间的最小间距（抖动后）。 */
    public static volatile long CHATTER_COOLDOWN_MIN_MS = 30_000;
    public static volatile long CHATTER_COOLDOWN_MAX_MS = 90_000;
    /** 轮与轮之间的阅读节奏。 */
    public static volatile long TURN_PAUSE_MIN_MS = 2_200;
    public static volatile long TURN_PAUSE_MAX_MS = 3_800;
    /** 某轮说出的台词被友好表情点缀的概率。 */
    public static volatile double CHATTER_EMOTE_CHANCE = 0.25;

    private static final int[] FRIENDLY_EMOTES = {1, 2, 5, 6};

    /** TownChatterDialogue.yaml 无行可加载时的回退对白（闲聊绝不因坏编辑而全静默）。 */
    private static final List<String> FALLBACK_EXCHANGE = List.of(
            "嘿", "嘿 最近咋样？", "还行 就随便逛逛", "这图人还不少", "是啊 热闹点好");

    // charId -> 参与期限（now + MAX_CHATTER_MS）。超过期限的条目视为陈旧。
    private static final Map<Integer, Long> ENGAGED = new ConcurrentHashMap<>();
    // charId -> 该 bot 可再次闲聊的最早时间。
    private static final Map<Integer, Long> COOLDOWN = new ConcurrentHashMap<>();

    /** 从空闲/可用参与者的 tick 调用。先廉价地掷门控，通过后与附近伙伴开启一段交替对白。 */
    public static void maybeStartChatter(BotSM initiator) {
        tryStart(initiator, false);
    }

    /** 立即强制闲聊一次，跳过冷却 + 概率掷点（仍要求可用 + 被观察 + 有附近空闲伙伴）。 */
    public static boolean forceChatter(BotSM initiator) {
        return tryStart(initiator, true);
    }

    private static boolean tryStart(BotSM initiator, boolean force) {
        if (initiator == null) {
            return false;
        }
        Character me = initiator.getChr();
        if (me == null || me.getMap() == null) {
            return false;
        }
        if (!initiator.isAvailableForAmbientActions()) {
            return false;
        }
        if (!GCMovement.isMapObserved(me.getMapId())) {
            return false; // 无观察者——闲聊是包，只门控包，绝不门控模拟
        }
        if (!force) {
            if (onCooldown(me)) {
                return false;
            }
            if (ThreadLocalRandom.current().nextDouble() >= CHATTER_CHANCE) {
                return false; // 安静 tick；不消耗冷却
            }
        }

        BotSM partnerBot = findPartner(initiator, me);
        if (partnerBot == null) {
            return false;
        }
        Character partner = partnerBot.getChr();
        if (partner == null) {
            return false;
        }

        List<String> exchange = pickExchange(me.getMapId());
        if (exchange == null || exchange.size() < 2) {
            return false;
        }
        if (!engagePair(me, partner)) {
            return false; // 争抢两个之一失败
        }
        armCooldown(me);
        armCooldown(partner);
        startExchange(me, partner, exchange);
        return true;
    }

    /** 当前正在闲聊的 bot 数（非陈旧参与）。诊断用（!env chatter）。 */
    public static int engagedCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Long deadline : ENGAGED.values()) {
            if (deadline != null && deadline > now) {
                n++;
            }
        }
        return n;
    }

    private static List<String> pickExchange(int mapId) {
        List<String> ex = TownChatterLines.randomExchange(mapId);
        return (ex != null && ex.size() >= 2) ? ex : FALLBACK_EXCHANGE;
    }

    /** 发起者所在图、CHATTER_RADIUS 内最近的合格 bot。 */
    private static BotSM findPartner(BotSM initiator, Character me) {
        MapleMap map = me.getMap();
        Point mp = me.getPosition();
        double bestSq = (double) CHATTER_RADIUS * CHATTER_RADIUS;
        BotSM best = null;
        for (Character chr : map.getAllPlayers()) {
            if (chr == null || chr.getId() == me.getId() || !BotHelpers.isBot(chr)) {
                continue;
            }
            double dsq = chr.getPosition().distanceSq(mp);
            if (dsq > bestSq) {
                continue;
            }
            BotSM bot = BotStorage.getBotById(chr.getId());
            if (bot == null || bot == initiator || !isParticipant(bot)) {
                continue;
            }
            if (isEngaged(chr) || !bot.isAvailableForAmbientActions()) {
                continue;
            }
            best = bot;
            bestSq = dsq; // 收窄到最近合格伙伴
        }
        return best;
    }

    /**
     * 只有这些类型参与闲聊。
     */
    private static boolean isParticipant(BotSM bot) {
        return bot instanceof SocialBot || bot instanceof TownWandererBot;
    }

    /** 在一条 BotTiming 链上构建并启动交替对白。链只发包并切换参与/冷却表。 */
    private static void startExchange(Character a, Character b, List<String> exchange) {
        BotTiming.Chain chain = BotTiming.chain()
                .stopUnless(() -> chatterAlive(a, b));
        chain.run(() -> {
            faceToward(a, b);
            faceToward(b, a);
        }).pauseRandom(500, 1100);
        for (int i = 0; i < exchange.size(); i++) {
            final Character speaker = (i % 2 == 0) ? a : b;
            final Character listener = (i % 2 == 0) ? b : a;
            final String raw = exchange.get(i);
            chain.run(() -> speakResolved(speaker, listener, raw))
                    .pauseRandom(TURN_PAUSE_MIN_MS, TURN_PAUSE_MAX_MS);
        }
        chain.run(() -> {
            release(a);
            release(b);
        });
        chain.start();
    }

    private static void speakResolved(Character speaker, Character listener, String raw) {
        if (speaker == null || raw == null) {
            return;
        }
        String line = raw;
        if (DialogueContextResolver.hasTokens(raw)) {
            Optional<String> filled = DialogueContextResolver.fill(raw, speaker, listener);
            if (filled.isEmpty()) {
                return; // 无法解析——这一轮保持沉默
            }
            line = filled.get();
        }
        botSpeak(speaker, line);
        if (ThreadLocalRandom.current().nextDouble() < CHATTER_EMOTE_CHANCE) {
            botEmote(speaker, FRIENDLY_EMOTES[ThreadLocalRandom.current().nextInt(FRIENDLY_EMOTES.length)]);
        }
    }

    private static boolean chatterAlive(Character a, Character b) {
        if (a == null || b == null || a.getMap() == null || b.getMap() == null) {
            return false;
        }
        if (a.getMap() != b.getMap()) {
            return false;
        }
        if (!isEngaged(a) || !isEngaged(b)) {
            return false;
        }
        return GCMovement.isMapObserved(a.getMapId());
    }

    /** 用 actor 实际被驱动的引擎转向，避免与 GC 驱动打架（或让旧引擎 bot 不动）。 */
    private static void faceToward(Character actor, Character target) {
        if (actor == null || target == null) {
            return;
        }
        boolean left = target.getPosition().getX() < actor.getPosition().getX();
        if (GCMovement.isEnabled(actor)) {
            GCMovement.face(actor, left);
        } else {
            // gms 无旧引擎 MovementCommands.botFaceTowardsPoint；用 stance 翻转 + 广播近似。
            actor.broadcastStance(left ? 1 : 0);
        }
    }

    // ---- 参与注册表（并发关键） ----

    public static boolean isEngaged(Character chr) {
        if (chr == null) {
            return false;
        }
        Long deadline = ENGAGED.get(chr.getId());
        if (deadline == null) {
            return false;
        }
        if (System.currentTimeMillis() >= deadline) {
            ENGAGED.remove(chr.getId(), deadline);
            return false;
        }
        return true;
    }

    private static boolean engagePair(Character a, Character b) {
        long deadline = System.currentTimeMillis() + MAX_CHATTER_MS;
        if (!tryEngage(a.getId(), deadline)) {
            return false;
        }
        if (!tryEngage(b.getId(), deadline)) {
            release(a);
            return false;
        }
        return true;
    }

    private static boolean tryEngage(int id, long deadline) {
        boolean[] took = {false};
        ENGAGED.compute(id, (k, existing) -> {
            if (existing != null && existing > System.currentTimeMillis()) {
                return existing;
            }
            took[0] = true;
            return deadline;
        });
        return took[0];
    }

    public static void release(Character chr) {
        if (chr != null) {
            ENGAGED.remove(chr.getId());
        }
    }

    public static void forget(Character chr) {
        if (chr != null) {
            ENGAGED.remove(chr.getId());
            COOLDOWN.remove(chr.getId());
        }
    }

    private static boolean onCooldown(Character chr) {
        Long until = COOLDOWN.get(chr.getId());
        return until != null && System.currentTimeMillis() < until;
    }

    private static void armCooldown(Character chr) {
        long span = Math.max(1, CHATTER_COOLDOWN_MAX_MS - CHATTER_COOLDOWN_MIN_MS);
        long cd = CHATTER_COOLDOWN_MIN_MS + (long) (ThreadLocalRandom.current().nextDouble() * span);
        COOLDOWN.put(chr.getId(), System.currentTimeMillis() + cd);
    }

    // ── 等价实现（gms 无 SocialCommands，内联 PacketCreator） ────────────────

    private static void botSpeak(Character character, String message) {
        if (character == null || character.getMap() == null) {
            return;
        }
        character.getMap().broadcastMessage(
                PacketCreator.getChatText(character.getId(), message, character.getWhiteChat(), 0));
    }

    private static void botEmote(Character character, int emote) {
        if (character == null || character.getMap() == null) {
            return;
        }
        character.getMap().broadcastMessage(PacketCreator.facialExpression(character, emote));
    }
}
