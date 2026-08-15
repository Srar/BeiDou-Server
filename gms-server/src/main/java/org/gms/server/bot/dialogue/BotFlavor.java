package org.gms.server.bot.dialogue;

import org.gms.client.Character;
import org.gms.client.inventory.WeaponType;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.attack.BotAttackConfig;
import org.gms.server.bot.attack.BotAttackProfile;
import org.gms.server.bot.attack.BotBuffConfig;
import org.gms.server.bot.attack.BotBuffEffects;
import org.gms.server.bot.commands.BotAttack;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.util.PacketCreator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 空闲表达层：让一个空闲的城镇/社交 bot 偶尔做点什么（表情、摆个增益、朝空气挥个技能），
 * 这样它就不会在脚本交换之间冻成一尊雕像。入口 maybeExpress(bot)，由 bot 既有空闲 tick 调用——
 * 不新增线程、不新增 ticker。这里的一切都是单个 bot 决定做一个纯视觉动作，硬门控在
 * 「有真人能看见」上。热闹人群的幻觉来自许多独立的小动作者，而非任何协调。
 * <p>
 * 技能/增益选择复用既有按职业注册表（BotAttackConfig / BotBuffConfig），不再重复列技能。
 */
public final class BotFlavor {

    private BotFlavor() {}

    // ---- 调参旋钮 ----
    public static volatile double FLAVOR_CHANCE = 0.35;
    public static volatile long FLAVOR_COOLDOWN_MIN_MS = 15_000;
    public static volatile long FLAVOR_COOLDOWN_MAX_MS = 40_000;

    private static final int[] FRIENDLY_EMOTES = {1, 2, 5, 6};

    private static final Map<Integer, Long> cooldownUntil = new ConcurrentHashMap<>();

    public static void maybeExpress(BotSM bot) {
        if (bot == null) {
            return;
        }
        Character chr = bot.getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        if (!GCMovement.isMapObserved(chr.getMapId())) {
            return;
        }

        long now = System.currentTimeMillis();
        Long until = cooldownUntil.get(chr.getId());
        if (until != null && now < until) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= FLAVOR_CHANCE) {
            return; // 安静 tick；下一 tick 再试（不消耗冷却）
        }

        dispatch(chr, pickAction());

        long span = Math.max(1, FLAVOR_COOLDOWN_MAX_MS - FLAVOR_COOLDOWN_MIN_MS);
        long cd = FLAVOR_COOLDOWN_MIN_MS + (long) (ThreadLocalRandom.current().nextDouble() * span);
        cooldownUntil.put(chr.getId(), now + cd);
    }

    public static void forget(Character chr) {
        if (chr != null) {
            cooldownUntil.remove(chr.getId());
        }
    }

    public static void forceExpress(BotSM bot) {
        forceExpress(bot, pickAction());
    }

    public static void forceExpress(BotSM bot, FlavorAction action) {
        if (bot == null || action == null) {
            return;
        }
        Character chr = bot.getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        dispatch(chr, action);
    }

    private static FlavorAction pickAction() {
        int total = 0;
        for (FlavorAction a : FlavorAction.values()) {
            total += a.weight;
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (FlavorAction a : FlavorAction.values()) {
            roll -= a.weight;
            if (roll < 0) {
                return a;
            }
        }
        return FlavorAction.EMOTE;
    }

    private static void dispatch(Character chr, FlavorAction action) {
        switch (action) {
            case EMOTE -> doEmote(chr);
            case BUFF_FLEX -> doBuffFlex(chr);
            case SKILL_SWING -> doSkillSwing(chr);
        }
    }

    private static void doEmote(Character chr) {
        int id = FRIENDLY_EMOTES[ThreadLocalRandom.current().nextInt(FRIENDLY_EMOTES.length)];
        botEmote(chr, id);
    }

    private static void doBuffFlex(Character chr) {
        List<Integer> buffs = BotBuffConfig.buffsForJob(chr.getJob());
        if (buffs.isEmpty()) {
            doEmote(chr);
            return;
        }
        int skillId = buffs.get(ThreadLocalRandom.current().nextInt(buffs.size()));
        BotBuffEffects.showBuff(chr, skillId);
    }

    private static void doSkillSwing(Character chr) {
        WeaponType weapon = BotAttack.resolveEquippedWeaponType(chr);
        BotAttackConfig.JobAttacks atk = BotAttackConfig.resolve(chr.getJob(), weapon);
        BotAttackProfile profile = pickProfile(atk);
        GCMovement.markAlerted(chr);
        if (profile == null) {
            BotAttack.basicSwing(chr);
            return;
        }
        int skillId = profile.skillFor(weapon);
        if (profile.route == BotAttackProfile.Route.MAGIC) {
            BotAttack.magicSwing(chr, skillId);
        } else {
            BotAttack.skillSwing(chr, skillId);
        }
    }

    private static BotAttackProfile pickProfile(BotAttackConfig.JobAttacks atk) {
        List<BotAttackProfile> options = new ArrayList<>();
        if (atk.single() != null) {
            options.add(atk.single());
        }
        if (atk.aoe() != null) {
            options.add(atk.aoe());
        }
        if (atk.ultimate() != null) {
            options.add(atk.ultimate());
        }
        if (options.isEmpty()) {
            return null;
        }
        return options.get(ThreadLocalRandom.current().nextInt(options.size()));
    }

    private static void botEmote(Character chr, int emote) {
        if (chr == null || chr.getMap() == null) {
            return;
        }
        chr.getMap().broadcastMessage(PacketCreator.facialExpression(chr, emote));
    }
}
