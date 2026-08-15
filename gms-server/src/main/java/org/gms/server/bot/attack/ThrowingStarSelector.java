package org.gms.server.bot.attack;

import org.gms.client.Character;
import org.gms.client.inventory.WeaponType;
import org.gms.constants.id.ItemId;
import org.gms.server.bot.commands.BotAttack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

// Picks a level-appropriate throwing star for a claw-thief bot once at creation and caches it on the
// bot so it never recomputes and never touches the Cosmic Character. Only claw throwers get one -
// assassins and rogues who took the claw path; daggers/bandits are melee and get none. The star is a
// cosmetic packet projectile (the flying-star sprite), not an equipped item.
// Ours (SoloMapling), not a GreenCatMS port.
public final class ThrowingStarSelector {

    private ThrowingStarSelector() {}

    // gms 移植：SoloMapling 把选中的星镖缓存在 BotSM.chosenStarId（由 BotSM 构造流程写入）；
    // gms 的 BotSM 没有该字段，改用静态 Map<Integer,Integer>（botId -> starId）实现等价缓存语义。
    // selectFor 计算后即写入本缓存；chosenStar 从本缓存读取。gms 的 BotHelpers.isBot(Character) 可用，
    // 但星镖缓存只服务于攻击投射物渲染，无需再校验 bot 身份。
    private static final Map<Integer, Integer> CHOSEN_STAR_BY_BOT = new ConcurrentHashMap<>();

    // Only subi/crystal-ilbi are public ItemId constants; the mid tiers are raw v83 ids here rather
    // than editing the ItemId table (mod boundary - we don't touch base for our own data).
    private static final int SUBI         = ItemId.SUBI_THROWING_STARS;         // 2070000
    private static final int WOLBI        = 2070001;
    private static final int MOKBI        = 2070002;
    private static final int KUMBI        = 2070003;
    private static final int TOBI         = 2070004;
    private static final int STEELY       = 2070005;
    private static final int ILBI         = 2070006;
    private static final int WOODEN_TOP   = 2070009;
    private static final int ICICLE       = 2070010;
    private static final int CRYSTAL_ILBI = ItemId.CRYSTAL_ILBI_THROWING_STARS; // 2070016

    private record Band(int minLevel, int maxLevel, int starId) {}

    // Bands are half-open [min, max): a level that falls in more than one band rolls one at random, so
    // the population shows variety (a level-45 throws tobi or steely; an 85 throws steely or ilbi).
    // Subi is stretched down to level 1 so a sub-10 rogue still has a star. Tune the table here.
    private static final Band[] BANDS = {
            new Band(1,   15,                SUBI),
            new Band(15,  25,                WOLBI),
            new Band(25,  30,                MOKBI),
            new Band(25,  30,                WOODEN_TOP),
            new Band(30,  40,                KUMBI),
            new Band(30,  40,                ICICLE),
            new Band(40,  50,                TOBI),
            new Band(40,  100,               STEELY),
            new Band(70,  200,               ILBI),
            new Band(120, Integer.MAX_VALUE, CRYSTAL_ILBI),
    };

    // The star id a claw-thief bot should throw, or 0 if the bot is not a claw thrower
    // (dagger/bandit/non-thief). Called once from the bot creation flow (originally the BotSM
    // constructor), by which point the Character is fully decorated (weapon equipped, level/job set).
    // gms 移植：计算后写入 CHOSEN_STAR_BY_BOT 缓存（等价 SoloMapling 的 BotSM.chosenStarId）。
    public static int selectFor(Character chr) {
        if (chr == null || BotAttack.resolveEquippedWeaponType(chr) != WeaponType.CLAW) {
            return 0; // only claw throwers throw stars
        }
        int starId = rollForLevel(chr.getLevel());
        CHOSEN_STAR_BY_BOT.put(chr.getId(), starId);
        return starId;
    }

    // The star this bot chose at creation, or 0 if it isn't a claw thrower or hasn't selected one.
    // Read from the attack path so the projectile matches.
    public static int chosenStar(Character bot) {
        if (bot == null) {
            return 0;
        }
        return CHOSEN_STAR_BY_BOT.getOrDefault(bot.getId(), 0);
    }

    /* Drop a despawned bot's cached star so the map doesn't grow unbounded (gms addition). */
    public static void clearBot(int botId) {
        CHOSEN_STAR_BY_BOT.remove(botId);
    }

    private static int rollForLevel(int level) {
        List<Integer> eligible = new ArrayList<>(4);
        for (Band b : BANDS) {
            if (level >= b.minLevel() && level < b.maxLevel()) {
                eligible.add(b.starId());
            }
        }
        if (eligible.isEmpty()) {
            return SUBI; // below the lowest band or a gap -> the beginner star
        }
        return eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
    }
}
