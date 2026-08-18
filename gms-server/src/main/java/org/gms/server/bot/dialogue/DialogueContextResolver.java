package org.gms.server.bot.dialogue;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.Pet;
import org.gms.constants.game.GameConstants;
import org.gms.constants.inventory.ItemConstants;
import org.gms.net.server.guild.Guild;
import org.gms.server.ItemInformationProvider;
import org.gms.server.bot.grind.MapMobIndex;
import org.gms.server.bot.itempool.DesirableEquipList;
import org.gms.server.life.Monster;
import org.gms.server.life.MonsterDropEntry;
import org.gms.server.life.MonsterInformationProvider;
import org.gms.server.maps.MapleMap;
import org.gms.util.Randomizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把对话行里的 {TOKEN} 占位符解析成实时游戏状态。
 * <p>
 * 两个主体：
 * <ul>
 *   <li>SELF（说话 bot）：{MAP} {REGION} {MOB} {DROP} {JOB} {LEVEL} {WEAPON}</li>
 *   <li>PLAYER（bot 所回应的角色）：{PLAYER_NAME} {PLAYER_LEVEL} {PLAYER_JOB}
 *       {PLAYER_FAME} {PLAYER_WEAPON} {PLAYER_GEAR} {PLAYER_PET} {PLAYER_GUILD} {PLAYER_NX}</li>
 * </ul>
 * 每个解析器返回 Optional；empty 表示「此处无法解析」，调用方据此丢弃该行，
 * 保证原始 {TOKEN} 绝不泄漏到聊天。
 */
@Slf4j
public final class DialogueContextResolver {

    private DialogueContextResolver() {
    }

    private static final Pattern TOKEN = Pattern.compile("\\{([A-Z_]+)\\}");

    // 现金外观装备槽位 <= 该值；常规可见装备位于该值与 0 之间。
    private static final int CASH_SLOT_CEILING = -100;

    public static boolean hasTokens(String line) {
        return line != null && TOKEN.matcher(line).find();
    }

    public static Optional<String> fill(String line, Character self) {
        return fill(line, self, null);
    }

    /**
     * 以 self（bot）与 player（可空）填充整行的每个 token。任一 token 无法解析即返回 empty，
     * 调用方据此丢弃该行，而不是说出一个半填充的模板。
     */
    public static Optional<String> fill(String line, Character self, Character player) {
        if (line == null) {
            return Optional.empty();
        }
        Matcher m = TOKEN.matcher(line);
        if (!m.find()) {
            return Optional.of(line);
        }
        Resolution res = new Resolution(self, player);
        StringBuffer sb = new StringBuffer();
        m.reset();
        while (m.find()) {
            Optional<String> val = res.resolve(m.group(1));
            if (val.isEmpty() || val.get().isBlank()) {
                return Optional.empty();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(val.get()));
        }
        m.appendTail(sb);
        return Optional.of(sb.toString());
    }

    /** 每次 fill 一个解析器。缓存焦点怪，使同一行的 {MOB} 与 {DROP} 一致。 */
    private static final class Resolution {
        private final Character self;
        private final Character player;
        private boolean mobResolved;
        private Integer focusMobId;

        Resolution(Character self, Character player) {
            this.self = self;
            this.player = player;
        }

        Optional<String> resolve(String token) {
            try {
                return switch (token) {
                    // self（说话 bot）
                    case "MAP" -> mapName(self);
                    case "REGION" -> region(self);
                    case "JOB" -> jobName(self);
                    case "LEVEL" -> levelStr(self);
                    case "WEAPON" -> weaponName(self);
                    case "MOB" -> mobName();
                    case "DROP" -> dropName();
                    // player（bot 回应的角色）
                    case "PLAYER_NAME" -> player == null ? Optional.empty() : nonBlank(player.getName());
                    case "PLAYER_LEVEL" -> player == null ? Optional.empty() : levelStr(player);
                    case "PLAYER_JOB" -> player == null ? Optional.empty() : jobName(player);
                    case "PLAYER_FAME" -> player == null ? Optional.empty() : Optional.of(String.valueOf(player.getFame()));
                    case "PLAYER_WEAPON" -> player == null ? Optional.empty() : weaponName(player);
                    case "PLAYER_GEAR" -> player == null ? Optional.empty() : notableGear(player);
                    case "PLAYER_PET" -> player == null ? Optional.empty() : petName(player);
                    case "PLAYER_GUILD" -> player == null ? Optional.empty() : guildName(player);
                    case "PLAYER_NX" -> player == null ? Optional.empty() : cashItem(player);
                    default -> Optional.empty();
                };
            } catch (RuntimeException e) {
                log.debug("DialogueContextResolver token '{}' resolution failed for bot {}",
                        token, self != null ? self.getId() : "null", e);
                return Optional.empty();
            }
        }

        private Optional<String> mobName() {
            OptionalInt id = focusMob();
            if (id.isEmpty()) {
                return Optional.empty();
            }
            return nonBlank(MonsterInformationProvider.getInstance().getMobNameFromId(id.getAsInt()));
        }

        private Optional<String> dropName() {
            OptionalInt mobId = focusMob();
            if (mobId.isEmpty()) {
                return Optional.empty();
            }
            OptionalInt dropId = pickNotableDrop(mobId.getAsInt(), self);
            if (dropId.isEmpty()) {
                return Optional.empty();
            }
            return nonBlank(ItemInformationProvider.getInstance().getName(dropId.getAsInt()));
        }

        private OptionalInt focusMob() {
            if (!mobResolved) {
                OptionalInt id = representativeMobId(self);
                focusMobId = id.isPresent() ? id.getAsInt() : null;
                mobResolved = true;
            }
            return focusMobId == null ? OptionalInt.empty() : OptionalInt.of(focusMobId);
        }
    }

    // ── 与主体无关的字段解析器（bot 或 player 通用） ──

    private static Optional<String> mapName(Character c) {
        MapleMap map = c.getMap();
        return map == null ? Optional.empty() : nonBlank(map.getMapName());
    }

    private static Optional<String> region(Character c) {
        MapleMap map = c.getMap();
        if (map == null) {
            return Optional.empty();
        }
        Optional<String> street = nonBlank(map.getStreetName());
        return street.isPresent() ? street : nonBlank(map.getMapName());
    }

    private static Optional<String> jobName(Character c) {
        return nonBlank(GameConstants.getJobName(c.getJob().getId()));
    }

    private static Optional<String> levelStr(Character c) {
        return Optional.of(String.valueOf(c.getLevel()));
    }

    private static Optional<String> weaponName(Character c) {
        Inventory equipped = c.getInventory(InventoryType.EQUIPPED);
        if (equipped == null) {
            return Optional.empty();
        }
        Item w = equipped.getItem((short) -11);
        if (w == null) {
            return Optional.empty();
        }
        return nonBlank(ItemInformationProvider.getInstance().getName(w.getItemId()));
    }

    private static Optional<String> petName(Character c) {
        Pet[] pets = c.getPets();
        if (pets == null) {
            return Optional.empty();
        }
        for (Pet p : pets) {
            if (p != null) {
                Optional<String> name = nonBlank(p.getName());
                if (name.isPresent()) {
                    return name;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> guildName(Character c) {
        Guild g = c.getGuild();
        return g == null ? Optional.empty() : nonBlank(g.getName());
    }

    /**
     * 玩家穿戴的一件值得注意的可见装备：优先白名单/标志性装备，否则任意常规（非现金）装备。
     * 让 bot 能夸真正的好装备，而不是随便一双袜子。
     */
    private static Optional<String> notableGear(Character c) {
        Inventory equipped = c.getInventory(InventoryType.EQUIPPED);
        if (equipped == null) {
            return Optional.empty();
        }
        List<Integer> desirable = new ArrayList<>();
        List<Integer> any = new ArrayList<>();
        for (Item it : equipped.list()) {
            if (it.getPosition() <= CASH_SLOT_CEILING) {
                continue; // 跳过现金外观，那是 {PLAYER_NX}
            }
            int id = it.getItemId();
            any.add(id);
            if (isDesirable(id)) {
                desirable.add(id);
            }
        }
        List<Integer> tier = !desirable.isEmpty() ? desirable : any;
        if (tier.isEmpty()) {
            return Optional.empty();
        }
        return nonBlank(ItemInformationProvider.getInstance().getName(tier.get(Randomizer.nextInt(tier.size()))));
    }

    /** 一件穿戴中的现金商城外观（现金外观槽），用于「你的 NX 哪来的？」这类氛围。 */
    private static Optional<String> cashItem(Character c) {
        Inventory equipped = c.getInventory(InventoryType.EQUIPPED);
        if (equipped == null) {
            return Optional.empty();
        }
        List<Integer> cash = new ArrayList<>();
        for (Item it : equipped.list()) {
            if (it.getPosition() <= CASH_SLOT_CEILING) {
                cash.add(it.getItemId());
            }
        }
        if (cash.isEmpty()) {
            return Optional.empty();
        }
        return nonBlank(ItemInformationProvider.getInstance().getName(cash.get(Randomizer.nextInt(cash.size()))));
    }

    // ── 怪 / 掉落辅助（bot 所在图） ──

    /**
     * 主体所处环境的一只代表性怪：优先图里存活的敌对怪；否则（当前无刷出）——
     * 回退到 WZ 数据（MapMobIndex.mobIds）里该图定义的怪。
     */
    private static OptionalInt representativeMobId(Character chr) {
        MapleMap map = chr.getMap();
        if (map == null) {
            return OptionalInt.empty();
        }
        List<Monster> live = new ArrayList<>();
        for (Monster m : map.getAllMonsters()) {
            if (isHostile(m)) {
                live.add(m);
            }
        }
        if (!live.isEmpty()) {
            return OptionalInt.of(live.get(Randomizer.nextInt(live.size())).getId());
        }
        List<Integer> wz = MapMobIndex.mobIds(map.getId());
        if (wz != null && !wz.isEmpty()) {
            return OptionalInt.of(wz.get(Randomizer.nextInt(wz.size())));
        }
        return OptionalInt.empty();
    }

    private static boolean isHostile(Monster m) {
        return m != null && m.isAlive() && m.getStats() != null && !m.getStats().isFriendly(); // gms 适配：缺怪数据跳过
    }

    /**
     * 从一只怪的掉落里挑一件值得注意的装备：排序 desirable & 职业匹配 > desirable >
     * 职业匹配 > 任意装备。卷轴/使用/任务掉落被排除（只保留装备），让台词引用真实装备。
     */
    private static OptionalInt pickNotableDrop(int mobId, Character chr) {
        List<MonsterDropEntry> drops = MonsterInformationProvider.getInstance().retrieveEffectiveDrop(mobId);
        if (drops == null || drops.isEmpty()) {
            return OptionalInt.empty();
        }
        int botJobBit = getReqJobViaJobStyle(chr.getJobStyle());

        List<Integer> desirableMatched = new ArrayList<>();
        List<Integer> desirable = new ArrayList<>();
        List<Integer> classMatched = new ArrayList<>();
        List<Integer> anyEquip = new ArrayList<>();

        for (MonsterDropEntry d : drops) {
            int item = d.itemId;
            if (d.questid != 0 || !ItemConstants.isEquipment(item)) {
                continue;
            }
            anyEquip.add(item);
            boolean matched = jobAllows(item, botJobBit);
            boolean hot = isDesirable(item);
            if (hot && matched) {
                desirableMatched.add(item);
            } else if (hot) {
                desirable.add(item);
            } else if (matched) {
                classMatched.add(item);
            }
        }

        List<Integer> tier =
                !desirableMatched.isEmpty() ? desirableMatched
                : !desirable.isEmpty() ? desirable
                : !classMatched.isEmpty() ? classMatched
                : anyEquip;
        if (tier.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(tier.get(Randomizer.nextInt(tier.size())));
    }

    /** bot 职业能否穿戴该装备：无职业限制，或 reqJob 位掩码包含 bot 职业时成立。 */
    private static boolean jobAllows(int itemId, int botJobBit) {
        Map<String, Integer> stats = ItemInformationProvider.getInstance().getEquipStats(itemId);
        Integer reqJob = stats == null ? null : stats.get("reqJob");
        if (reqJob == null || reqJob == 0) {
            return true;
        }
        return botJobBit != 0 && (reqJob & botJobBit) != 0;
    }

    /**
     * 等价 SoloMapling ItemInformationProviderUtilities.getReqJobViaJobStyle：
     * jobStyle -> reqJob 位（1=Warrior 2=Mage 4=Bowman 8=Thief 16=Pirate），
     * 十字弓手归一到弓手（reqJob 4）。该工具类已移植（org.gms.server.bot.itempool
     * .ItemInformationProviderUtilities），此处保留内联版本，可择机改调。
     */
    private static int getReqJobViaJobStyle(Job jobStyle) {
        if (jobStyle == Job.CROSSBOWMAN) {
            jobStyle = Job.BOWMAN;
        }
        return switch (jobStyle) {
            case WARRIOR -> 1;
            case MAGICIAN -> 2;
            case BOWMAN -> 4;
            case THIEF -> 8;
            case PIRATE -> 16;
            default -> 0;
        };
    }

    /** 等价 SoloMapling DesirableEquipList.isDesirable：白名单命中。 */
    private static boolean isDesirable(int itemId) {
        return DesirableEquipList.isDesirable(itemId);
    }

    private static Optional<String> nonBlank(String s) {
        return (s == null || s.isBlank()) ? Optional.empty() : Optional.of(s);
    }
}
