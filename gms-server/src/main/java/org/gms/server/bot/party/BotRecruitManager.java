package org.gms.server.bot.party;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.util.Randomizer;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Shared recruit brain for the dialogue-driven party flow (SocialBot / TrainingBot / FollowerBot).
// Owns the RNG accept/decline roll, the per-(bot,player) decline cooldown, the armed-invite window
// ("I said yes - now only THAT player's invite gets accepted, briefly"), the global follower cap,
// and the small handoff maps that carry intent across convertBotType's Character-only factory
// (pending leader for a fresh FollowerBot, station-here for a fresh TrainingBot).
// OPQ's unconditional accept path (BotPartyLogic.checkPartyQueue) is deliberately untouched.
@Slf4j
public class BotRecruitManager {

    // Cap on simultaneous followers world-wide. Deliberately above the 6-man party cap - future
    // raid/PQ recruiting may follow without partying; today the invite flow is the practical limit.
    public static final int FOLLOWER_CAP = 30;
    public static final double SOCIAL_ACCEPT_CHANCE = 0.70;
    public static final double TRAINING_ACCEPT_CHANCE = 0.80;
    // Fallback accept chance for direct (non-dialogue) right-click invites to bot types that have
    // no recruit brain of their own (e.g. IdleBot / merchant bots).
    public static final double DIRECT_INVITE_DEFAULT_CHANCE = 0.30;
    // Kept comfortably above the InviteCoordinator's ~3-min silent timeout so the accept window no
    // longer races it - the coordinator now backstops staleness (a late accept just NOT_FOUNDs
    // harmlessly). This also keeps isArmed() true across the whole realistic invite window, which
    // gates recruitingNow()/priority speed and the follower-cap UI.
    private static final long INVITE_WINDOW_MS = 200_000;     // accept window after saying "invite me"
    private static final long DECLINE_COOLDOWN_MS = 180_000;  // per-player re-ask cooldown after a decline

    public enum RecruitAnswer { ACCEPTED, DECLINED, ON_COOLDOWN, FOLLOWERS_FULL }

    public enum InvitePoll { NONE, JOINED, REJECTED }

    private record Armed(int inviterId, long expiresAtMs) {
    }

    private static final Map<Integer, Armed> ARMED = new ConcurrentHashMap<>();          // bot char id
    private static final Map<Long, Long> DECLINED_UNTIL = new ConcurrentHashMap<>();     // (bot,player) key
    private static final Map<Integer, Integer> PENDING_LEADER = new ConcurrentHashMap<>(); // bot id -> leader id
    private static final Set<Integer> PENDING_STATION = ConcurrentHashMap.newKeySet();   // bot ids

    // The dialogue option was picked: roll accept/decline. On accept the invite window is armed;
    // on decline the (bot,player) pair goes on cooldown. willBecomeFollower gates the global cap.
    public static RecruitAnswer rollPartyAsk(Character botChr, Character player, double acceptChance,
                                             boolean willBecomeFollower) {
        if (botChr.getLevel() < 10) {
            // The party handler hard-blocks invites to sub-10 characters ("does not meet the
            // requirements") - decline organically instead of arming a window that can't be used.
            log.debug("rollPartyAsk: {} DECLINED {} (bot below lv10)", botChr.getName(), player.getName());
            return RecruitAnswer.DECLINED;
        }
        long pairKey = pairKey(botChr.getId(), player.getId());
        Long coolUntil = DECLINED_UNTIL.get(pairKey);
        if (coolUntil != null && System.currentTimeMillis() < coolUntil) {
            log.debug("rollPartyAsk: {} ON_COOLDOWN for {}", botChr.getName(), player.getName());
            return RecruitAnswer.ON_COOLDOWN;
        }
        if (willBecomeFollower && activeFollowerCount() >= FOLLOWER_CAP) {
            log.debug("rollPartyAsk: follower cap reached ({}), declining", FOLLOWER_CAP);
            return RecruitAnswer.FOLLOWERS_FULL;
        }
        if (Randomizer.nextDouble() < acceptChance) {
            ARMED.put(botChr.getId(), new Armed(player.getId(), System.currentTimeMillis() + INVITE_WINDOW_MS));
            log.debug("rollPartyAsk: {} ACCEPTED {} (invite window {}s)",
                    botChr.getName(), player.getName(), INVITE_WINDOW_MS / 1000);
            return RecruitAnswer.ACCEPTED;
        }
        DECLINED_UNTIL.put(pairKey, System.currentTimeMillis() + DECLINE_COOLDOWN_MS);
        log.debug("rollPartyAsk: {} DECLINED {} (rolled no, {}min cooldown)",
                botChr.getName(), player.getName(), DECLINE_COOLDOWN_MS / 60000);
        return RecruitAnswer.DECLINED;
    }

    /**
     * 直接右键邀请（非对话）的掷骰入口（gms 增强，用户拍板）：无冷却每次重掷；
     * 命中写入 ARMED（200s 武装窗口），未命中不写 DECLINED_UNTIL。
     * 对话流程 rollPartyAsk 的冷却 gate 保持不变（两条路径互不影响）。
     * <p>
     * 武装短路（对话承诺兑现，兑现率 100%）：若 bot 正处于 rollPartyAsk 写入的
     * 有效武装窗口内，直接邀请不再二次掷骰——同玩家（inviterId 匹配）直接返回 true
     * 兑现对话承诺（不覆盖窗口），其他玩家返回 false（保护已武装窗口，不被后来者
     * 覆盖/偷走，也不会被本轮掷骰重写）。
     * <p>
     * 冷却旁路（有意行为）：对话被拒后走右键直接邀请可绕过对话 180s 冷却——直接
     * 邀请路径不读 DECLINED_UNTIL，未命中也不写冷却。用户拍板的有意设计。
     */
    public static boolean rollDirectPartyInvite(Character botChr, Character player) {
        Armed armed = ARMED.get(botChr.getId());
        if (armed != null && System.currentTimeMillis() <= armed.expiresAtMs()) {
            // 对话承诺兑现：同玩家不二次掷骰、不覆盖窗口；他人不覆盖、不偷走。
            return armed.inviterId() == player.getId();
        }
        if (botChr.getLevel() < 10) {
            // 防御：PartyOperationHandler 已挡 sub-10 邀请，这里双保险，避免武装一个用不了的窗口。
            return false;
        }
        BotSM bot = BotStorage.getBotById(botChr.getId());
        String type = bot == null ? null : bot.getBotType();
        if ("FollowerBot".equals(type)) {
            // 放行入队（true）但不写 ARMED：接受与否交 FollowerBot.pollLeaderInvite
            // （750ms tick）按 leader 裁决——leader 邀请接受、非 leader 被 poll 礼貌拒绝。
            return true;
        }
        if ("OPQBot".equals(type)) {
            // 无条件接受，与 BotPartyLogic.checkPartyQueue 语义一致。
            return true;
        }
        final double chance;
        final boolean willFollow;
        if ("SocialBot".equals(type)) {
            chance = SOCIAL_ACCEPT_CHANCE;
            willFollow = true;
        } else if ("TrainingBot".equals(type)) {
            chance = TRAINING_ACCEPT_CHANCE;
            willFollow = false;
        } else {
            chance = DIRECT_INVITE_DEFAULT_CHANCE;
            willFollow = false;
        }
        if (willFollow && activeFollowerCount() >= FOLLOWER_CAP) {
            log.debug("rollDirectPartyInvite: follower cap reached ({}), declining", FOLLOWER_CAP);
            return false;
        }
        if (Randomizer.nextDouble() < chance) {
            ARMED.put(botChr.getId(), new Armed(player.getId(), System.currentTimeMillis() + INVITE_WINDOW_MS));
            log.debug("rollDirectPartyInvite: {} ACCEPTED {} (no cooldown, invite window {}s)",
                    botChr.getName(), player.getName(), INVITE_WINDOW_MS / 1000);
            return true;
        }
        // 无冷却：未命中不写 DECLINED_UNTIL，玩家被拒后立刻再邀仍有完整机会。
        log.debug("rollDirectPartyInvite: {} DECLINED {} (rolled no, no cooldown)",
                botChr.getName(), player.getName());
        return false;
    }

    // Per-tick invite drain for recruit-enabled bots. BotPartyQueue is last-wins per bot, so a
    // pending invite must always be answered: accept if it's the armed inviter (id match),
    // politely reject everything else (frees the slot; the inviter gets the normal declined notice).
    public static InvitePoll pollInvites(Character botChr) {
        if (!BotPartyQueue.getInstance().hasPendingInvite(botChr)) {
            return InvitePoll.NONE;
        }
        BotPartyQueue.PartyInviteEntry entry = BotPartyQueue.getInstance().getPartyInvite(botChr);
        if (entry == null) {
            return InvitePoll.NONE;
        }
        Armed armed = ARMED.get(botChr.getId());
        Character inviter = entry.getInviter();
        // Accept on inviter-id match alone (no window expiry check): the id proves this is the player
        // the bot agreed to, and the InviteCoordinator's own ~3-min timeout bounds staleness. A truly
        // stale accept just NOT_FOUNDs at the coordinator and no join happens - harmless. This stops a
        // legitimate-but-late invite from being guillotined into a "declined" reject.
        boolean armedMatch = armed != null && inviter != null
                && inviter.getId() == armed.inviterId();
        if (armedMatch) {
            boolean joined = BotPartyCommands.botAcceptPartyInvite(botChr);
            if (joined) {
                ARMED.remove(botChr.getId());
                return InvitePoll.JOINED;
            }
            return InvitePoll.NONE; // coordinator-side expiry; queue already cleared by the accept call
        }
        BotPartyCommands.botRejectPartyInvite(botChr);
        return InvitePoll.REJECTED;
    }

    // A party invite for this bot just landed in the queue. Wake its macro brain so the next tick
    // drains it via pollInvites within ~300ms, instead of waiting out the slow observation/governor
    // cadence (60-120s while deep-grinding unobserved) and letting the invite go stale. Same
    // immediate-nudge mechanism the map-entry responder uses; benefits every recruit-enabled bot.
    // nudgeSoon self-guards a not-running / trading / unregistered bot, so no extra check here.
    public static void wakeBotForInvite(Character botChr) {
        if (botChr == null) {
            return;
        }
        BotSM bot = BotStorage.getAllBots().get(botChr.getId());
        if (bot != null) {
            bot.nudgeSoon(300);
        }
    }

    public static boolean isArmed(int botId) {
        Armed a = ARMED.get(botId);
        return a != null && System.currentTimeMillis() <= a.expiresAtMs();
    }

    // Who the bot said yes to (-1 = nobody). Read this BEFORE pollInvites - a JOINED poll clears it.
    public static int armedInviterId(int botId) {
        Armed a = ARMED.get(botId);
        return a == null ? -1 : a.inviterId();
    }

    public static void clearArmed(int botId) {
        ARMED.remove(botId);
    }

    // ── Conversion handoffs (convertBotType constructs from a bare Character) ──

    public static void setPendingLeader(int botCharId, int leaderId) {
        PENDING_LEADER.put(botCharId, leaderId);
    }

    public static int consumePendingLeader(int botCharId) {
        Integer id = PENDING_LEADER.remove(botCharId);
        return id == null ? -1 : id;
    }

    public static void markStationHere(int botCharId) {
        PENDING_STATION.add(botCharId);
    }

    public static boolean consumeStationHere(int botCharId) {
        return PENDING_STATION.remove(botCharId);
    }

    public static void clearHandoffs(int botCharId) {
        PENDING_LEADER.remove(botCharId);
        PENDING_STATION.remove(botCharId);
        ARMED.remove(botCharId);
    }

    // Live scan by type string (no BotTypes import, no counter to keep in sync across conversions).
    public static int activeFollowerCount() {
        int n = 0;
        for (BotSM bot : BotStorage.getAllBots().values()) {
            if ("FollowerBot".equals(bot.getBotType())) {
                n++;
            }
        }
        return n;
    }

    private static long pairKey(int botId, int playerId) {
        return ((long) botId << 32) | (playerId & 0xffffffffL);
    }
}
