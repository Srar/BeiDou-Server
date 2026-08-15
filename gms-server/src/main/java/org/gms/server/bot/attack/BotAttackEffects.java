package org.gms.server.bot.attack;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.constants.inventory.ItemConstants;
import org.gms.net.packet.Packet;
import org.gms.net.server.world.World;
import org.gms.server.ItemInformationProvider;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.life.Monster;
import org.gms.server.life.MonsterDropEntry;
import org.gms.server.life.MonsterInformationProvider;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;
import org.gms.util.Randomizer;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * Lands a real bot hit (melee / ranged / magic): broadcast the attack packet with the
 * damage line(s) so players see it, then apply the summed damage directly - skipping the
 * vanilla applyAttack path, which is keyed off stats a bot lacks. The bot is always passed
 * explicitly (never the shared client).
 *
 * On a kill we credit EXP + death via monster.damage + killMonster(withDrops=false), then
 * drop loot ourselves: the vanilla path only drops for damagers passing isLoggedinWorld(),
 * which a headless bot fails, so a bot kill would otherwise drop nothing. Loot keeps
 * vanilla ownership (owner = bot; party-shared if grouped, else owner ~15s then FFA) at
 * world drop rates.
 */
@Slf4j
public final class BotAttackEffects {

    private BotAttackEffects() {}

    /*
     * Credits to NutNNut for Packet IDs
     * Land a melee hit on one or more mobs: broadcast the swing carrying each mob's damage
     * line(s), apply the summed damage per mob, and drop loot for any it kills. Returns
     * true if at least one mob died. skillId 0 = a no-skill basic swing.
     *
     * @param bot          the attacking bot (passed explicitly - shared-client-safe)
     * @param hits         each target mob -> its rolled damage line(s); all same line count
     * @param skillId      the attack skill that renders, or 0 for a basic swing
     * @param bodyActionId the per-weapon swing animation id (from BotAttackData)
     * @param facingMask   the stance/facing byte (0x00 right / 0x80 left)
     * @param speed        the attack-speed byte (2..9)
     * @param hitDelay     ms before the damage numbers land (also the drop delay)
     */
    public static boolean meleeStrike(Character bot, Map<Monster, List<Integer>> hits, int skillId,
                                      int skillLevel, int bodyActionId, int facingMask, int speed, short hitDelay) {
        if (notReady(bot, hits)) {
            return false;
        }
        Packet packet = PacketCreator.closeRangeAttack(bot, skillId, skillLevel, facingMask,
                numAttackedAndDamage(hits), toTargets(hits), speed, bodyActionId, 0);
        return broadcastAndApply(bot, packet, hits, hitDelay);
    }

    /* Ranged version (bow/crossbow/gun/claw): like melee, plus the flying projectile. */
    public static boolean rangedStrike(Character bot, Map<Monster, List<Integer>> hits, int skillId,
                                       int skillLevel, int projectile, int bodyActionId, int facingMask,
                                       int speed, short hitDelay) {
        if (notReady(bot, hits)) {
            return false;
        }
        Packet packet = PacketCreator.rangedAttack(bot, skillId, skillLevel, facingMask,
                numAttackedAndDamage(hits), projectile, toTargets(hits), speed, bodyActionId, 0);
        return broadcastAndApply(bot, packet, hits, hitDelay);
    }

    /*
     * Magic version (wand/staff): a skill is mandatory (mages have no basic attack). The charge
     * int is -1 for normal skills but a real value for keydown CHARGE skills (Big Bang) - the
     * client over-reads the packet and crashes if a charge skill is sent without it.
     */
    public static boolean magicStrike(Character bot, Map<Monster, List<Integer>> hits, int skillId,
                                      int skillLevel, int bodyActionId, int facingMask, int speed, short hitDelay) {
        if (notReady(bot, hits)) {
            return false;
        }
        Packet packet = PacketCreator.magicAttack(bot, skillId, skillLevel, facingMask,
                numAttackedAndDamage(hits), toTargets(hits),
                BotAttackData.magicChargeFor(skillId), speed, bodyActionId, 0);
        return broadcastAndApply(bot, packet, hits, hitDelay);
    }

    private static boolean notReady(Character bot, Map<Monster, List<Integer>> hits) {
        return bot == null || bot.getMap() == null || hits == null || hits.isEmpty();
    }

    /* numAttacked (mobs, high nibble) | numDamage (lines per mob, low nibble). */
    private static int numAttackedAndDamage(Map<Monster, List<Integer>> hits) {
        int numDamage = hits.values().iterator().next().size();
        return (hits.size() << 4) | numDamage;
    }

    /*
     * Packet target map: each mob's object id -> its damage line(s).
     * gms 移植：SoloMapling 的 PacketCreator.*Attack 接受 Map<Integer, AttackTarget>（每目标带
     * hitDelay + lines）；gms 只接受 Map<Integer, List<Integer>>（每怪伤害线）。此处去掉
     * AbstractDealDamageHandler.AttackTarget 依赖，只保留 lines 列表，语义逐行对齐。
     */
    private static Map<Integer, List<Integer>> toTargets(Map<Monster, List<Integer>> hits) {
        Map<Integer, List<Integer>> targets = new HashMap<>();
        for (Map.Entry<Monster, List<Integer>> hit : hits.entrySet()) {
            targets.put(hit.getKey().getObjectId(), hit.getValue());
        }
        return targets;
    }

    /* Broadcast once, then apply each mob's summed damage + loot. True if any mob died. */
    private static boolean broadcastAndApply(Character bot, Packet packet,
                                             Map<Monster, List<Integer>> hits, short hitDelay) {
        bot.getMap().broadcastMessage(bot, packet, /* repeatToSource */ false);
        GCMovement.markAlerted(bot); // hold the 5s ALERT pose so the bot's own idle/move broadcasts don't cancel it
        boolean anyKilled = false;
        for (Map.Entry<Monster, List<Integer>> hit : hits.entrySet()) {
            int total = 0;
            for (int line : hit.getValue()) {
                total += BotAttackData.decodeDamageLine(line); // crit lines arrive negative-encoded
            }
            if (applyDamageAndLoot(bot, hit.getKey(), total, hitDelay)) {
                anyKilled = true;
            }
        }
        return anyKilled;
    }

    /* Apply HP damage; on death, credit EXP + the death broadcast (no vanilla drops) and spawn our own loot. */
    private static boolean applyDamageAndLoot(Character bot, Monster target, int damage, short hitDelay) {
        MapleMap map = bot.getMap();

        boolean killed = target.damage(bot, damage, false); // register damage; false = allow death
        if (killed) {
            // withDrops=false: keep the EXP distribution + death broadcast, skip the
            // vanilla drop step (which yields nothing for a bot-only kill). We spawn the
            // loot ourselves below with proper ownership.
            // gms 移植：gms killMonster 的第 4 参语义是 animation（SoloMapling 为 dropDelay 且
            // 动画固定=1）；hitDelay(300-380) 若直接传入会被当成死亡动画 id，故此处固定为 1，
            // hitDelay 仅作为掉落延迟传给 dropMobLoot。
            map.killMonster(target, bot, false, 1);
            dropMobLoot(bot, target, hitDelay);
            // No vacuum here: the dropped loot is collected organically by the bot itself - TrainingBot
            // walks over the pile and picks drops up one at a time (own + free-for-all), and any drop it
            // abandons expires via the normal map item lifetime.
        }
        return killed;
    }

    /*
     * Roll the mob's real drop table and spawn each result with vanilla ownership: owner =
     * the bot, drop-type 1 (party-shared) when grouped else 0 (owner-protected ~15s, then
     * FFA). Rates come from the world config (drop_rate/boss_drop_rate/meso_rate), not the
     * bot's own field (a cold bot never runs setWorldRates(), so its rate is a flat 1).
     * Drops are sprayed horizontally like vanilla instead of stacking on one tile.
     */
    private static void dropMobLoot(Character bot, Monster mob, short delay) {
        MapleMap map = bot.getMap();
        if (mob.dropsDisabled()) {
            return;
        }

        List<MonsterDropEntry> drops = MonsterInformationProvider.getInstance().retrieveEffectiveDrop(mob.getId());
        if (drops == null || drops.isEmpty()) {
            return;
        }

        // Vanilla drop-type: party loot when grouped, else owner-own (15s) -> FFA.
        final byte dropType = (byte) (bot.getParty() != null ? 1 : 0);
        // World rates (config drop_rate/boss_drop_rate/meso_rate), matching a real player.
        // gms 移植：gms 的 World 通过 lombok @Getter 暴露 getDropRate()/getBossDropRate()/getMesoRate()，
        // 与 SoloMapling 相同；仍用 World 而非 Character 的 getRawDropRate()/getBossDropRate()/getRawMesoRate()，
        // 因为冷启动 bot 从不执行 setWorldRates()，自身 rate 字段恒为基准值。
        final World world = bot.getWorldServer();
        // gms 移植：gms World 的 getDropRate()/getBossDropRate()/getMesoRate() 经 lombok 返回 float，
        // 而 SoloMapling 为 int；此处显式转 int 并对下界取 1。
        final int dropRate = (int) Math.max(1f, mob.isBoss() ? world.getBossDropRate() : world.getDropRate());
        final int mesoRate = (int) Math.max(1f, world.getMesoRate());
        final ItemInformationProvider ii = ItemInformationProvider.getInstance();
        final int mobX = mob.getPosition().x;
        final int mobY = mob.getPosition().y;

        // Spray successful drops horizontally (0, +25, -25, +50, -50 ... px), the same
        // index-based fan-out vanilla uses, so loot spreads out instead of stacking.
        int index = 1;
        for (MonsterDropEntry de : drops) {
            int dropChance = (int) Math.min((long) de.chance * dropRate, Integer.MAX_VALUE);
            if (Randomizer.nextInt(999999) >= dropChance) {
                continue;
            }

            int spreadX = mobX + ((index % 2 == 0) ? (25 * ((index + 1) / 2)) : -(25 * (index / 2)));
            Point pos = new Point(spreadX, mobY);

            if (de.itemId == 0) { // meso
                int mesos = rollAmount(de.Minimum, de.Maximum);
                if (mesos > 0) {
                    mesos = Math.max(1, mesos * mesoRate);
                    // gms 移植：gms 的 spawnMesoDrop 有 7 参重载（含 delay），内部暂未使用 delay
                    // （PacketCreator.dropItemFromMapObject 无 delay 重载），与 6 参版本等价；保留 7 参
                    // 以对齐 SoloMapling 调用语义。
                    map.spawnMesoDrop(mesos, pos, mob, bot, /* playerDrop */ false, dropType, delay);
                    index++;
                }
            } else {
                Item item;
                if (ItemConstants.getInventoryType(de.itemId) == InventoryType.EQUIP) {
                    Equip equip = (Equip) ii.getEquipById(de.itemId);
                    if (equip == null) {
                        log.warn("BotAttackEffects.dropMobLoot: no equip data for item {}; skipping drop", de.itemId);
                        continue;
                    }
                    item = ii.randomizeStats(equip);
                } else {
                    short qty = (short) (de.Maximum != 1 ? rollAmount(de.Minimum, de.Maximum) : 1);
                    item = new Item(de.itemId, (short) 0, qty);
                }
                // gms 移植：SoloMapling 的 spawnItemDrop 参数顺序为 (mob, bot, item, pos, dropType, false)；
                // gms 为 (dropper, owner, item, pos, dropType, playerDrop)，顺序一致，直接对应。
                map.spawnItemDrop(mob, bot, item, pos, dropType, /* playerDrop */ false);
                index++;
            }
        }
    }

    /* Inclusive-ish amount roll matching vanilla (min..max), guarded against nextInt(0). */
    private static int rollAmount(int min, int max) {
        int span = max - min;
        return min + (span > 0 ? Randomizer.nextInt(span) : 0);
    }
}
