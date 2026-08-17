package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;
import org.gms.server.bot.itempool.EquipMetadataCache;
import org.gms.server.maps.MapleMap;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class BotCustomization {

    // bot customization - Handles decorating the bots. Hairs, equips. Visible stuff
    static List<Integer> v83_chair_ids = List.of(
            3010000, 3010001, 3010002, 3010003, 3010004, 3010005, 3010006, 3010007,
            3010008, 3010009, 3010010, 3010011, 3010012, 3010013, 3010014, 3010015,
            3010016, 3010017, 3010018, 3010019, 3010022, 3010023, 3010024, 3010025,
            3010026, 3010028, 3010040, 3010041, 3010043, 3010045, 3010046, 3010047,
            3010057, 3010058, 3010060, 3010061, 3010062, 3010063, 3010064, 3010065,
            3010066, 3010067, 3010069, 3010071, 3010072, 3010073, 3010080, 3010081,
            3010082, 3010083, 3010084, 3010085, 3010092, 3010098, 3010099, 3010101,
            3010106, 3010111, 3010116, 3011000, 3012005, 3012010, 3012011
    );

    static int[][] v83_store_permit_ids = {
            {5140000, 85}, // 85% Chance for basic store
            {5140001, 3}, // 15% for other season style, 3% each
            {5140002, 3},
            {5140003, 3},
            {5140004, 3},
            {5140006, 3}
    };

    public static void EquipBot(Character fakechar, Integer itemId) {
        if (itemId == null) {
            return;
        }
        // wz 存在性兜底校验：v83 客户端渲染 spawn 包 addCharLook 里不存在的装备 id 会崩。
        // 快速路径为 O(1) HashSet（EquipMetadataCache 索引，装饰热路径恒命中、零 WZ 访问）；
        // 索引未收录但 WZ 中真实存在（如 170xxxx cash 武器，超出既有装备区间）的 id 放行，
        // 真正不存在的 id 直接跳过（绝不抛异常、绝不影响其余装饰）。
        if (!EquipMetadataCache.equipExists(itemId)
                && ItemInformationProvider.getInstance().getEquipStats(itemId) == null) {
            log.warn("BotCustomization.EquipBot: item {} does not exist in WZ; skipping", itemId);
            return;
        }
        short dst = getDestinationEquipSlot(itemId);
//        if (dst == -12 || dst == -13 || dst == -15 || dst == -16 || dst == -112 || dst == -113 || dst == -115 | dst == -116) {
//            EquipBotRing(fakechar, itemId, dst);
//            return;
//        }
        EquipItem(fakechar, itemId, dst);
    }

    public static short getDestinationEquipSlot(int itemID) {
        int itemSlot = getEquipSlotType(itemID);
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        boolean isCash = ii.getEquipStats(itemID).get("cash") == 1;

        // Upstream ItemConstants.isWeapon() only recognises the regular 1302000-1493000
        // range, so cash weapons (v83 "170xxxx") fall through and get mis-slotted into
        // CASH_BASE (-100) instead of CASH_WEAPON (-111). Detect and correct here,
        // scoped to bot equipping so we don't touch upstream Cosmic code.
        if (isCash && isCashWeaponId(itemID)) {
            return (short) -CASH_WEAPON_SLOT;
        }

        if (isCash) {
            if (itemSlot == WEAPON_SLOT) {
                itemSlot = CASH_WEAPON_SLOT;
            } else {
                itemSlot = itemSlot + CASH_BASE_SLOT;
            }
        }
        return (short) -itemSlot;
    }

    /**
     * Matches the v83 cash-weapon ID range. Cash weapons use the 170xxxx prefix
     * (1702xxx sword overrides, 1703xxx axe, etc.), which upstream ItemConstants
     * doesn't know about. Range is kept broad to cover every cash weapon subtype
     * without enumerating each prefix.
     */
    private static boolean isCashWeaponId(int itemId) {
        return itemId >= 1700000 && itemId < 1800000;
    }

    // ── 换装广播节流（2026-08-17 客户端崩溃事故的降载修复） ──────────────────
    // bot 波次性集体换装会在同帧刷出几十条 UPDATE_CHAR_LOOK，且无人观察的图也全量广播，
    // 是客户端 error 5 崩溃的高危来源。此处对每 bot 的换装广播做最小间隔节流：
    // 装备照常穿上（不影响装备状态），仅「广播侧」被节流——窗口内多次换装只发首条，
    // 窗口结束后补发一次最终状态；无人观察的图直接跳过广播（进图/spawn 时自然同步）。
    static final long MIN_LOOK_BROADCAST_INTERVAL_MS = 3000;

    /** botId → 上次换装广播时间戳（毫秒）。 */
    private static final Map<Integer, Long> LAST_LOOK_BROADCAST_MS = new ConcurrentHashMap<>();

    /** botId → 窗口内是否存在尚未广播的换装变更（待补发）。 */
    private static final Map<Integer, Boolean> DIRTY_LOOK_BOTS = new ConcurrentHashMap<>();

    public static void EquipItem(Character fakechar, Integer itemId, short dst) {
        if (itemId == null) {
            return;
        }
        Item sourceItem = ItemInformationProvider.getInstance().getEquipById(itemId); // Item you are equipping, clean stats
        if (!(sourceItem instanceof Equip source)) {
            log.warn("BotCustomization.EquipItem: no equip data for item {}; skipping", itemId);
            return;
        }
        Inventory equipped = fakechar.getInventory(InventoryType.EQUIPPED);
        if (equipped == null) {
            log.warn("BotCustomization.EquipItem: no equipped inventory for bot {}; skipping", fakechar.getId());
            return;
        }
        Item target = equipped.getItem(dst); // Currently equipped item
        if (target != null) { // if equip already in, remove that.
            equipped.removeSlot(dst);
        }
        source.setPosition(dst); // set the position of Equip to dst (see body part)
        equipped.addItemFromDB(source); // Actually equip the item
        // Update fakechar avatar, doesn't work if they're not on screen.
        // 广播侧节流：装备已落库，这里只决定「立即广播 / 延迟补发 / 跳过」。
        scheduleLookBroadcast(fakechar);
    }

    /**
     * 换装广播节流入口（装备已落库，仅决定广播侧行为）：
     * <ol>
     *   <li>无人观察（无真实玩家）的图：跳过广播且不记录时间——bot 换装状态会在后续
     *       spawn/进图广播中自然同步，无需补发；</li>
     *   <li>距上次广播未满 {@link #MIN_LOOK_BROADCAST_INTERVAL_MS}：标记 dirty 并调度
     *       窗口结束后的补发（已有待补发任务则跳过重复调度）；</li>
     *   <li>否则立即广播并记录时间戳。</li>
     * </ol>
     * 包级可见（非 private）：ItemInformationProvider 离线单测无法初始化，单测直接
     * 以本方法为断言面（等价于「装备落库后的一次换装广播决策」），不经过 EquipItem 全路径。
     */
    static void scheduleLookBroadcast(Character fakechar) {
        int botId = fakechar.getId();
        MapleMap map = fakechar.getMap();
        if (!BotHelpers.hasRealPlayerObserver(map)) {
            // 无人图：跳过广播（含清掉此前遗留的 dirty——若已有补发任务，其执行时会发现 dirty
            // 已清空而直接放弃，不产生广播）。
            DIRTY_LOOK_BOTS.remove(botId);
            return;
        }
        if (shouldBroadcastNow(botId)) {
            noteBroadcast(botId);
            fakechar.equipChanged();
            return;
        }
        // 节流窗口内：标记 dirty 并调度窗口结束后的补发；dirty 已存在说明补发任务已排定，
        // 不重复调度（补发时广播的是届时最终状态，窗口内后续换装都会被它覆盖）。
        if (DIRTY_LOOK_BOTS.putIfAbsent(botId, Boolean.TRUE) != null) {
            return;
        }
        long waitMs = MIN_LOOK_BROADCAST_INTERVAL_MS
                - (System.currentTimeMillis() - LAST_LOOK_BROADCAST_MS.get(botId));
        int mapId = map.getId();
        BotExecutors.schedule(() -> flushLookBroadcast(fakechar, botId, mapId), waitMs);
    }

    /**
     * 节流窗口结束后的补发：bot 仍在原图且仍有未同步变更时才广播最终状态。
     * 防泄漏：bot 已销毁（getMap()==null）或已换图则只清 dirty 不广播（换图/spawn 广播
     * 携带当前外观）；补发时重新检查观察者（期间真人可能已离开）。
     */
    private static void flushLookBroadcast(Character fakechar, int botId, int mapId) {
        // 先认领 dirty：清除失败说明状态已被换图/销毁等路径清理，直接放弃。
        if (!Boolean.TRUE.equals(DIRTY_LOOK_BOTS.remove(botId))) {
            return;
        }
        MapleMap map = fakechar.getMap();
        if (map == null || map.getId() != mapId) {
            return; // bot 已销毁或换图：无需补发
        }
        if (!BotHelpers.hasRealPlayerObserver(map)) {
            return; // 补发时真人已离开：无人图不广播
        }
        noteBroadcast(botId);
        fakechar.equipChanged();
    }

    /**
     * 时间窗口判定（包级可见，供单测直接覆盖）：距该 bot 上次广播是否已满
     * {@link #MIN_LOOK_BROADCAST_INTERVAL_MS}。首次换装（无记录）恒为 true。
     */
    static boolean shouldBroadcastNow(int botId) {
        return shouldBroadcastNow(botId, System.currentTimeMillis());
    }

    /** 时间注入版（供单测覆盖时间边界，生产代码不直接使用）。 */
    static boolean shouldBroadcastNow(int botId, long now) {
        Long last = LAST_LOOK_BROADCAST_MS.get(botId);
        return last == null || now - last >= MIN_LOOK_BROADCAST_INTERVAL_MS;
    }

    /** 记录一次已发生的换装广播时间戳。 */
    static void noteBroadcast(int botId) {
        LAST_LOOK_BROADCAST_MS.put(botId, System.currentTimeMillis());
    }

    /** 仅供单测：清空节流状态，避免用例间串扰。 */
    static void resetLookBroadcastState() {
        LAST_LOOK_BROADCAST_MS.clear();
        DIRTY_LOOK_BOTS.clear();
    }

    public static int getRandomChairId() {
        return getRandomNumber(v83_chair_ids);
    }

    public static int getRandomStorePermitId() {
        return pickWeighted(v83_store_permit_ids);
    }

    public static int pickWeighted(int[][] items) {
        int total = 0;

        // sum weights
        for (int[] pair : items) {
            total += pair[1];
        }

        int random = new Random().nextInt(total);
        int running = 0;

        for (int[] pair : items) {
            running += pair[1];
            if (random < running) {
                return pair[0]; // return the ID
            }
        }

        throw new IllegalStateException("Should not happen");
    }

    // BodyPart slot values (1:1 with SoloMapling client.inventory.BodyPart).
    // gms 无 BodyPart 枚举，也无 CASH_* 常量；此处按原数值常量内联，避免硬造缺失 API。
    private static final int WEAPON_SLOT = 11;       // BodyPart.WEAPON
    private static final int CASH_WEAPON_SLOT = 111; // BodyPart.CASH_WEAPON
    private static final int CASH_BASE_SLOT = 100;   // BodyPart.CASH_BASE

    /**
     * Port of SoloMapling ItemConstants.getEquipSlotType (gms 无同名方法，内联移植，
     * 数值与源实现 1:1)：按 item 前缀映射到正向 body-part 槽位，武器优先。
     */
    private static int getEquipSlotType(int itemId) {
        int itemPrefix = itemId / 10000;

        if (ItemConstants.isWeapon(itemId)) {
            return WEAPON_SLOT; // BodyPart.WEAPON
        }

        switch (itemPrefix) {
            case 100:
                return 1; // BodyPart.CAP
            case 101:
                return 2; // BodyPart.FACE_ACCESSORY
            case 102:
                return 3; // BodyPart.EYE_ACCESSORY
            case 103:
                return 4; // BodyPart.EAR_ACCESSORY
            case 104:
                return 5; // BodyPart.COAT
            case 105:
                return 5; // BodyPart.LONGCOAT
            case 106:
                return 6; // BodyPart.PANTS
            case 107:
                return 7; // BodyPart.SHOES
            case 108:
                return 8; // BodyPart.GLOVE
            case 109:
            case 119:
            case 134:
                return 10; // BodyPart.SHIELD
            case 110:
                return 9; // BodyPart.CAPE
            case 111:
                return 12; // BodyPart.RING_1
            case 112:
                return 17; // BodyPart.PENDANT
            case 113:
                return 50; // BodyPart.BELT
            case 114:
                return 49; // BodyPart.MEDAL
            case 115:
                return 51; // BodyPart.SHOULDER
            case 118:
                return 56; // BodyPart.BADGE
        }
        return 0;
    }

    // Port of SoloMaplingUtilities.getRandomNumber(List<Integer>)：随机取列表元素。
    private static int getRandomNumber(List<Integer> numbers) {
        Random rand = new Random();
        int index = rand.nextInt(numbers.size());
        return numbers.get(index);
    }

}
