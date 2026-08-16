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

import java.util.List;
import java.util.Random;

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
        fakechar.equipChanged(); // Update fakechar avatar, doesn't work if they're not on screen
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
