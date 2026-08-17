package org.gms.server.bot.attack;

import org.gms.client.Character;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.net.server.Server;
import org.gms.net.server.world.Party;
import org.gms.server.StatEffect;
import org.gms.server.bot.BotHelpers;
import org.gms.util.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/*
 * Buff-visual packet ids here are vanilla Cosmic (server.StatEffect), not GreenCatMS-sourced.
 * The "show a buff" broadcast for bots. Vanilla StatEffect.applyTo does far more than the
 * visual (HP/MP, consumption, morphs, effect registration) and bails out on many branches,
 * so for cosmetic bots we fire just the two broadcasts that make a buff look like it happened:
 * the cast animation (showBuffEffect) and the persistent aura (giveForeignBuff). No stats are
 * changed and no effect is registered.
 */
public final class BotBuffEffects {

    // effectId 1 + direction 3 = the standard self-cast buff effect (matches the
    // primary-cast broadcast in StatEffect).
    private static final int CAST_EFFECT_ID = 1;

    private BotBuffEffects() {}

    /*
     * Broadcast the buff visual for skillId on the bot to everyone on its
     * map. Returns the buff's WZ duration in ms (for recast cadence), or 0 if the
     * skill/effect can't be resolved. Does NOT apply any stat.
     *
     * Observer-gated: when the map has no real player watching it, both visual
     * broadcasts (showBuffEffect + giveForeignBuff) are skipped - the bot's own
     * client isn't on the map and nobody else would see the aura, so an idle map
     * stays packet-free. The WZ duration is still resolved and returned, so the
     * caller's recast timers stay on cadence (a skipped broadcast is not a failed
     * cast).
     */
    public static int showBuff(Character bot, int skillId) {
        return showBuffInternal(bot, skillId, false);
    }

    /*
     * Unchecked variant of showBuff: always broadcasts, observer or not. Backs the
     * GM debug paths (!bot castbuff / givebuff) so a forced visual check is never
     * silently dropped by the observer gate.
     */
    public static int showBuffUnchecked(Character bot, int skillId) {
        return showBuffInternal(bot, skillId, true);
    }

    private static int showBuffInternal(Character bot, int skillId, boolean unchecked) {
        if (bot == null || bot.getMap() == null) return 0;

        // 观察门控：无人观察且非强制路径时跳过全部视觉广播。duration 仍照常
        // 解析返回，保证 castBuff 的 recast 计时不受门控影响。
        boolean skipped = !unchecked && !BotHelpers.hasRealPlayerObserver(bot.getMap());

        if (!skipped) {
            // The cast animation only needs the skill id - fire it regardless of
            // whether the StatEffect resolves, so the visual is never skipped.
            bot.getMap().broadcastMessage(bot,
                    PacketCreator.showBuffEffect(bot.getId(), skillId, CAST_EFFECT_ID), false);
        }

        // The persistent aura needs the buff's stat list (cheap memoized lookup,
        // no applyTo). Skip silently if the skill has no stat ups.
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) return 0;
        StatEffect effect = skill.getEffect(skill.getMaxLevel());
        if (effect == null) return 0;

        if (!skipped && !effect.getStatups().isEmpty()) {
            bot.getMap().broadcastMessage(bot,
                    PacketCreator.giveForeignBuff(bot.getId(), effect.getStatups()), false);
        }

        return effect.getDuration();
    }

    /* The buff's WZ duration (ms) at max level, or 0 if unresolvable. Cheap memoized lookup. */
    public static int durationOf(int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) return 0;
        StatEffect effect = skill.getEffect(skill.getMaxLevel());
        return effect != null ? effect.getDuration() : 0;
    }

    // Range a party buff reaches around the casting bot (split x/y, since MapleStory
    // maps are wide and short). Members further than this aren't buffed.
    private static final int PARTY_BUFF_RANGE_X = 700;
    private static final int PARTY_BUFF_RANGE_Y = 350;

    /* A sensible "extended" buff length for bots: 10 minutes. */
    public static final int EXTENDED_DURATION_MS = 600_000;

    /*
     * Full buff cast for a bot. Always shows the cast animation on the bot; and if
     * the skill is a PARTY buff (has an area-of-effect box), also grants it to the
     * bot's nearby party members - real players get the working buff, party-member
     * bots get the cosmetic aura. Self-only buffs stay on the bot. Returns the
     * buff's WZ duration (ms) for recast cadence. The two-arg form is
     * observer-gated (the normal recast tick path); pass force=true to bypass the
     * gate (GM !bot buff force path).
     */
    public static int castBuff(Character bot, int skillId) {
        return castBuff(bot, skillId, false);
    }

    public static int castBuff(Character bot, int skillId, boolean force) {
        if (force) {
            showBuffUnchecked(bot, skillId); // GM 强制路径：无视观察门控，全量广播
        } else {
            showBuff(bot, skillId);          // 普通 recast tick：无人观察的图跳过广播
        }

        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) return 0;
        StatEffect effect = skill.getEffect(skill.getMaxLevel());
        if (effect == null) return 0;

        if (effect.isPartyBuff()) {
            applyToTargets(bot, effect, skillId, nearbyPartyMembers(bot));
        }
        return effect.getDuration();
    }

    /*
     * A bot grants a buff to an explicit target list: shows the cast animation on
     * the bot, then applies the REAL buff to each (real players get the actual
     * working effect with no MP cost / no cast pose; bots get the cosmetic aura).
     * Used by the !bot givebuff command.
     */
    public static void givePartyBuff(Character bot, int skillId, Collection<Character> targets) {
        showBuffUnchecked(bot, skillId); // GM 命令路径：保持视觉完整，不走观察门控
        if (targets == null || targets.isEmpty()) return;

        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) return;
        StatEffect effect = skill.getEffect(skill.getMaxLevel());
        if (effect == null) return;

        applyToTargets(bot, effect, skillId, targets);
    }

    private static void applyToTargets(Character bot, StatEffect effect, int skillId, Collection<Character> targets) {
        for (Character target : targets) {
            if (target == null || target == bot) continue;
            if (BotHelpers.isBot(target)) {
                showBuff(target, skillId);            // party-member bots stay cosmetic
            } else {
                effect.applyToTarget(bot, target);    // real player gets the working buff
                showReceivedBuff(target, skillId);    // + the "received a party buff" particle
            }
        }
    }

    /*
     * Show the "received a party buff" particle on target. applyToTarget
     * uses the non-primary apply path, which skips the cast effect, so we fire the exact
     * two packets the engine sends for each affected party member (StatEffect.java:1172-1173):
     * showOwnBuffEffect to the target's own client (they see it on themselves) and a
     * showBuffEffect broadcast to the rest of the map (others see it on them). effectId
     * 2 = the party-receive variant. Works regardless of party membership - it's just packets.
     */
    private static void showReceivedBuff(Character target, int skillId) {
        target.sendPacket(PacketCreator.showOwnBuffEffect(skillId, 2));
        target.getMap().broadcastMessage(target, PacketCreator.showBuffEffect(target.getId(), skillId, 2), false);
    }

    /*
     * Like .givePartyBuff but forces a custom duration (e.g. 10 min) on the
     * real buff given to player targets, instead of the WZ duration. Bots stay
     * cosmetic. Useful for extending key party buffs (Holy Symbol, Maple Warrior, ...).
     */
    public static void giveExtendedBuff(Character bot, int skillId, Collection<Character> targets, int durationMs) {
        showBuffUnchecked(bot, skillId); // GM 命令路径：保持视觉完整，不走观察门控
        if (targets == null || targets.isEmpty()) return;

        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) return;
        StatEffect effect = skill.getEffect(skill.getMaxLevel());
        if (effect == null) return;

        for (Character target : targets) {
            if (target == null || target == bot) continue;
            if (BotHelpers.isBot(target)) {
                showBuffUnchecked(target, skillId); // GM 命令路径：目标 bot 视觉同样不走门控
            } else {
                applyBuffWithDuration(target, effect, skillId, durationMs);
            }
        }
    }

    /*
     * Apply effect to target with a custom duration - the lean core
     * of StatEffect.applyTo's buff path with our own length: the giveBuff
     * packet (client countdown), registerEffect with an explicit expiry
     * (server stat + expiry; confirmed via Character.registerEffect ->
     * addItemEffectHolder(expirationtime) + updateLocalStats), the foreign-buff aura,
     * and the receive particle. No MP cost, no cast pose on the target.
     */
    private static void applyBuffWithDuration(Character target, StatEffect effect, int skillId, int durationMs) {
        long start = Server.getInstance().getCurrentTime();
        target.sendPacket(PacketCreator.giveBuff(effect.getBuffSourceId(), durationMs, effect.getStatups()));
        target.registerEffect(effect, start, start + durationMs, false);
        target.getMap().broadcastMessage(target,
                PacketCreator.giveForeignBuff(target.getId(), effect.getStatups()), false);
        showReceivedBuff(target, skillId);
    }

    /* The bot's party members on the same map within party-buff range (excludes the bot). */
    private static List<Character> nearbyPartyMembers(Character bot) {
        Party party = bot.getParty();
        if (party == null || bot.getMap() == null) return Collections.emptyList();

        Point botPos = bot.getPosition();
        List<Character> result = new ArrayList<>();
        for (Character chr : bot.getMap().getCharacters()) {
            if (chr == bot) continue;
            Party other = chr.getParty();
            if (other == null || other.getId() != party.getId()) continue;

            Point p = chr.getPosition();
            if (botPos != null && p != null) {
                if (Math.abs(p.x - botPos.x) > PARTY_BUFF_RANGE_X) continue;
                if (Math.abs(p.y - botPos.y) > PARTY_BUFF_RANGE_Y) continue;
            }
            result.add(chr);
        }
        return result;
    }
}
