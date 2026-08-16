package org.gms.server.bot.freemarket;

import org.gms.client.Job;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.constants.inventory.EquipType;
import org.gms.server.ItemInformationProvider;
import org.gms.server.bot.BotLogic;
import org.gms.server.bot.itempool.DesirableEquipList;
import org.gms.server.bot.itempool.EquipMetadataCache;
import org.gms.server.bot.itempool.ItemNode;
import org.gms.server.bot.itempool.QuantitySelector;
import org.gms.server.maps.PlayerShopItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import static org.gms.server.bot.freemarket.BotEconomy.adjustFMPrices;
import static org.gms.server.bot.freemarket.BotEconomy.adjustFMQuantity;
import static org.gms.server.bot.freemarket.BotRand.generateRandomNumber;
import static org.gms.server.bot.freemarket.BotRand.getRandomElement;
import static org.gms.server.bot.freemarket.HiredMerchantArtificial.shopTypes.*;
import static org.gms.server.bot.itempool.ItemInformationProviderUtilities.checkQuestEquip;
import static org.gms.server.bot.itempool.ItemInformationProviderUtilities.checkTradeable;
import static org.gms.server.bot.itempool.ItemInformationProviderUtilities.getJobStyleFromItemId;
import static org.gms.server.bot.itempool.ItemInformationProviderUtilities.getRandomEquipType;
import static org.gms.server.bot.itempool.ItemInformationProviderUtilities.getReqJobViaJobStyle;
import static org.gms.server.bot.itempool.ItemInformationProviderUtilities.getWzPrice;
import static org.gms.server.bot.itempool.ItemInformationProviderUtilities.selectWeightedRandom;
import static org.gms.server.bot.itempool.ItemSelector.getRandomItemFull;
import static org.gms.server.bot.itempool.ItemSelector.pickRandomVariantId;
import static org.gms.server.bot.itempool.QuantitySelector.distributedTierSelector;
import static org.gms.server.bot.itempool.UpgradeSimulator.ScrollGivenItem;
import static org.gms.server.bot.itempool.UpgradeSimulator.checkIfEquipIsScrollable;
import static org.gms.server.bot.itempool.UpgradeSimulator.getEquipMarketValue;

/**
 * 移植自 SoloMapling FreeMarket.ArtificialShopGenerator（逐行照搬）。
 *
 * <p>移植说明（gms 底座差异）：
 * <ul>
 *   <li>soloMapling.itemPool.* → org.gms.server.bot.itempool（ItemSelector/QuantitySelector/ItemNode 由
 *       itempool 依赖类组代理移植并已落盘，本类按 gms 接口调用）。</li>
 *   <li>FMEconomyManager.adjustFMPrices/adjustFMQuantity → BotEconomy（波 1 已就绪）。</li>
 *   <li>SoloMaplingUtilities.getRandomNumber/pickRandomItem → BotRand.generateRandomNumber/getRandomElement。</li>
 *   <li>MapleVersionManager.getItemPoolVersion()（源固定 55，暗卷池判断 &gt;= 39 恒真）→ 本地常量
 *       {@link #ITEM_POOL_VERSION}，保留判断结构。</li>
 *   <li>引擎差异：gms PlayerShopItem.price 为 private final 且无 setPrice（源 SoloMapling fork 有），
 *       「1 币店 / 整店折扣」无法原地改价，改为按原顺序重建条目（保留 item/bundles/存在标记），
 *       见 {@link #retagShopPrices}。</li>
 *   <li>源 soloMapling.FreeMarket.EquipListGenerator（摆摊管线本体，非 itempool 层）无人认领，
 *       且本波次受「只新建 2 个文件」约束，故其 generateEquipList/generateEquipListIIPU 及私有辅助
 *       内联于本类末尾（逻辑逐行照搬），见下方 EquipListGenerator 内联区块。</li>
 * </ul>
 */
public class ArtificialShopGenerator {

    static List<String> ALL_SCROLLS = List.of("Earring", "Overall", "Gloves", "Claw", "Dagger", "Shoes", "Gloves", "Cape", "ETC");
    static List<String> ALL_DARK_SCROLLS = List.of(
            "Earring", "Overall", "Shoes",
            "Gloves", "Cape", "Hat", "Top", "Bottom", "Shield", "ETC", "Special");
    static Random random = new Random();

    // 源 MapleVersionManager.getItemPoolVersion() 固定返回 55（gms 无对应版本管理器），
    // generateDarkScrollsListInternal 中 >= 39 的版本判断恒真；保留判断结构，将来恢复版本控制只需改此常量。
    private static final int ITEM_POOL_VERSION = 55;

    public static void generateShop(HiredMerchantArtificial merchant, Job classType) {
        boolean hotRoom = BotEconomy.isHotRoom(merchant.getMapId());
        for (FMEquip equipEntry : generateEquipListByClassType(classType, merchant.getTier(), hotRoom)) {
            addEquipToShop(merchant, equipEntry);
        }

        // Handle special cases
        switch (classType) {
            case THIEF:
                double starsChance = switch (merchant.getTier().toUpperCase()) {
                    case "S" -> 0.70;
                    case "A" -> 0.40;
                    default -> 0.10;
                };
                if (Math.random() < starsChance) {
                    generateSecondaryShop(merchant, Stars);
                }
                break;
            case WARRIOR:
            case MAGICIAN:
            case BOWMAN:
                // TODO: Add class-specific scrolls/weapons here
                break;
            case BEGINNER:
                break;
            default:
                break;
        }
    }

    private static List<FMEquip> generateEquipListByClassType(Job jobType, String tier, boolean hotRoom) {
        return switch (jobType) {
            case BEGINNER -> generateEquipListByJob(tier, ClassStyle.BEGINNER, hotRoom);
            case WARRIOR -> generateEquipListByJob(tier, ClassStyle.WARRIOR, hotRoom);
            case MAGICIAN -> generateEquipListByJob(tier, ClassStyle.MAGICIAN, hotRoom);
            case BOWMAN -> generateEquipListByJob(tier, ClassStyle.BOWMAN, hotRoom);
            case THIEF -> generateEquipListByJob(tier, ClassStyle.THIEF, hotRoom);
            default -> Collections.emptyList();
        };
    }

    private static final Map<HiredMerchantArtificial.shopTypes, Function<String, List<FMItem>>> SECONDARY_SHOP_GENERATORS = Map.of(
            Stars, ArtificialShopGenerator::generateThiefStarsList,
            Scroll, ArtificialShopGenerator::generateScrollsList,
            DarkScroll, ArtificialShopGenerator::generateDarkScrollsList,
            Potion, ArtificialShopGenerator::generatePotionsList,
            ETC, ArtificialShopGenerator::generateETCList,
            Mastery, ArtificialShopGenerator::generateMasteryBookList,
            Chair, ArtificialShopGenerator::generateChairList
    );

    public static void generateSecondaryShop(HiredMerchantArtificial merchant, HiredMerchantArtificial.shopTypes shopType) {
        List<FMItem> items = SECONDARY_SHOP_GENERATORS.getOrDefault(shopType, t -> {
                    throw new IllegalArgumentException("Unsupported ShopType: " + shopType);
                })
                .apply(merchant.getTier());

        if (items != null) {
            addUseableItemsToShop(merchant, items);
        }
    }

    private static void addUseableItemsToShop(HiredMerchantArtificial merchant, List<FMItem> items) {
        for (FMItem item : items) {
            addUseableToShop(merchant, item);
        }
    }

    private enum ClassStyle {
        BEGINNER("common.yaml", List.of("Earring", "Overall", "Gloves", "Shoes", "Cape", "Hat", "Bottom", "Top")),
        THIEF("thief.yaml", List.of("Weapon", "Hat", "Top", "Pants", "Shield")),
        MAGICIAN("mage.yaml", List.of("Weapon", "Hat", "Top", "Shield")),
        BOWMAN("bowman.yaml", List.of("Weapon", "Hat", "Top", "Pants")),
        WARRIOR("warrior.yaml", List.of("Weapon", "Hat", "Top", "Pants"));

        private final String yamlFile;
        private final List<String> equipTypes;

        ClassStyle(String yamlFile, List<String> equipTypes) {
            this.yamlFile = yamlFile;
            this.equipTypes = equipTypes;
        }

        public String getYamlFile() {
            return yamlFile;
        }

        public List<String> getEquipTypes() {
            return equipTypes;
        }
    }

    private static List<FMEquip> generateEquipListByJob(String tier, ClassStyle classStyle) {
        return generateEquipListByJob(tier, classStyle, false);
    }

    private static List<FMEquip> generateEquipListByJob(String tier, ClassStyle classStyle, boolean hotRoom) {
        List<FMEquip> equipList = generateEquipList(classStyle.getYamlFile(), classStyle.getEquipTypes(), tier, hotRoom);
        equipList.addAll(generateEquipListIIPU(tier, Job.valueOf(classStyle.name())));
        return equipList;
    }

    public static List<FMEquip> generateCommonEquipList(String tier) {
        return generateEquipListByJob(tier, ClassStyle.BEGINNER);
    }

    private static List<FMEquip> generateThiefEquipList(String tier) {
        return generateEquipListByJob(tier, ClassStyle.THIEF);
    }

    private static List<FMEquip> generateMageEquipList(String tier) {
        return generateEquipListByJob(tier, ClassStyle.MAGICIAN);
    }

    private static List<FMEquip> generateBowmanEquipList(String tier) {
        return generateEquipListByJob(tier, ClassStyle.BOWMAN);
    }

    private static List<FMEquip> generateWarriorEquipList(String tier) {
        return generateEquipListByJob(tier, ClassStyle.WARRIOR);
    }

    private static List<FMEquip> generateSpecialEquipList(String tier) {
        // todo Gacha, holiday, random,
        return null;
    }

    public static List<FMItem> generateThiefStarsList(String tier) {
        List<FMItem> itemList = new ArrayList<>() {
        };
        for (int i = 0; i < 2; i++) {
            tier = distributedTierSelector(tier);
            ItemNode item = getRandomItemFull("thief.yaml", "Stars", tier);
            int itemId = pickRandomVariantId(item.getVariantId());
            int price = item.getCurrentPrice();
            itemList.add(new FMItem(itemId, price, 1));
        }
        return itemList;
    }

    public static List<FMItem> generateScrollsList(String tier) {
        return generateScrollsListInternal(ALL_SCROLLS, tier);
    }

    public static List<FMItem> generateDarkScrollsList(String tier) {
        return generateDarkScrollsListInternal(ALL_DARK_SCROLLS, tier);
    }

    private static List<FMItem> generateScrollsListInternal(List<String> items, String tier) {
        return generateScrollsList(items, "scrolls.yaml", tier);
    }

    private static List<FMItem> generateDarkScrollsListInternal(List<String> items, String tier) {
        if (ITEM_POOL_VERSION >= 39) {
            return generateScrollsList(items, "darkscrolls.yaml", tier);
        }
        return null;
    }

    private static List<FMItem> generateScrollsList(List<String> items, String scrollList, String tier) {
        List<FMItem> itemList = new ArrayList<>() {
        };
        for (int i = 0; i < 4; i++) {
            String randomItem = getRandomElement(items);
            tier = distributedTierSelector(tier);
            ItemNode item = getRandomItemFull(scrollList, randomItem, tier);
            if (item != null) {
                int itemId = pickRandomVariantId(item.getVariantId());
                int price = item.getCurrentPrice();
                int qty = quantitySelector("Scroll", tier); // getRandomIntInRange(5, 13);
                itemList.add(new FMItem(itemId, price, qty));
            }
        }
        removeDuplicates(itemList);
        return itemList;
    }

    public static List<FMItem> generatePotionsList(String tier) {
        List<FMItem> itemList = new ArrayList<>() {
        };
        String useablesList = "useables.yaml";
        List<String> items = List.of("Potions");
        for (int i = 0; i < 3; i++) {
            String randomItem = getRandomElement(items);
            tier = distributedTierSelector(tier);
            ItemNode item = getRandomItemFull(useablesList, randomItem, tier);
            if (item != null) {
                int itemId = pickRandomVariantId(item.getVariantId());
                int price = item.getCurrentPrice();
                int qty = quantitySelector("Potion", tier); // getRandomIntInRange(5, 13);
                itemList.add(new FMItem(itemId, price, qty));
            }
        }
        removeDuplicates(itemList);
        return itemList;
    }

    private static List<FMItem> generateETCList(String tier) {
        List<FMItem> itemList = new ArrayList<>() {
        };
        String useablesList = "etc.yaml";
        List<String> items = List.of("CrystalRefined", "CrystalOre", "MineralRefined", "MineralOre",
                "JewelRefined", "JewelOre", "SkillRocks", "BossItems", "RandomETC");
        for (int i = 0; i < 3; i++) {
            String randomItem = getRandomElement(items);
            tier = distributedTierSelector(tier);
            ItemNode item = getRandomItemFull(useablesList, randomItem, tier);
            if (item != null) {
                int itemId = pickRandomVariantId(item.getVariantId());
                int price = item.getCurrentPrice();
                int qty = quantitySelector("ETC", tier); // getRandomIntInRange(5, 10);
                itemList.add(new FMItem(itemId, price, qty));
            }
        }
        removeDuplicates(itemList);
        return itemList;
    }

    private static List<FMItem> generateMasteryBookList(String tier) {
        List<FMItem> itemList = new ArrayList<>() {
        };
        String useablesList = "masteryBook.yaml";
        List<String> items = List.of("MasteryBooks");
        for (int i = 0; i < 3; i++) {
            String randomItem = getRandomElement(items);
            tier = distributedTierSelector(tier);
            ItemNode item = getRandomItemFull(useablesList, randomItem, tier);
            if (item != null) {
                int itemId = pickRandomVariantId(item.getVariantId());
                int price = item.getCurrentPrice();
                int qty = quantitySelector("Mastery", tier);
                itemList.add(new FMItem(itemId, price, qty));
            }
        }
        removeDuplicates(itemList);
        return itemList;
    }

    private static List<FMItem> generateChairList(String tier) {
        List<FMItem> itemList = new ArrayList<>() {
        };
        String chairList = "chair.yaml";
        List<String> items = List.of("Chairs");
        for (int i = 0; i < 1; i++) {
            String randomItem = getRandomElement(items);
            tier = distributedTierSelector(tier);
            ItemNode item = getRandomItemFull(chairList, randomItem, tier);
            if (item != null) {
                int itemId = pickRandomVariantId(item.getVariantId());
                int price = item.getCurrentPrice();
                int qty = 1;
                itemList.add(new FMItem(itemId, price, qty));
            }
        }
        removeDuplicates(itemList);
        return itemList;
    }

    private static void addEquipToShop(HiredMerchantArtificial merchant, FMEquip fmEquip) {
        addItemToShop(merchant, fmEquip.getEquip(), 1, fmEquip.getPrice());
    }

    public static Item generateItem(int itemId, int position, int qty) {
        return new Item(itemId, (short) position, (short) qty);
    }

    private static void addUseableToShop(HiredMerchantArtificial merchant, FMItem item) {
        Item itemToSell = new Item(item.getItemId(), (short) 1, (short) 1);
        short perBundle = (short) 1; // num per bundle
        itemToSell.setQuantity(perBundle);
        addItemToShop(merchant, itemToSell, item.getQty(), item.getPrice());
    }

    private static void addUseableToShop(HiredMerchantArtificial merchant, int itemId, int quantity, int price) {
        Item itemToSell = new Item(itemId, (short) 1, (short) 1);
        short perBundle = (short) 1; // num per bundle
        itemToSell.setQuantity(perBundle);
        addItemToShop(merchant, itemToSell, quantity, price);
    }

    private static void addItemToShop(HiredMerchantArtificial merchant, Item sellItem, int quantity, int price) {
        short bundles = (short) adjustFMQuantity(merchant, quantity);
        int adjustedPrice = adjustFMPrices(merchant, price);
        PlayerShopItem shopItem = new PlayerShopItem(sellItem, bundles, adjustedPrice);
        merchant.addItem(shopItem);
    }

    private static int quantitySelector(String type, String tier) {
        return QuantitySelector.quantitySelector(type, tier);
    }

    private static void removeDuplicates(List<FMItem> items) {
        Set<Integer> seenItemIds = new HashSet<>();
        Iterator<FMItem> iterator = items.iterator();

        while (iterator.hasNext()) {
            FMItem currentItem = iterator.next();
            if (!seenItemIds.add(currentItem.getItemId())) {
                // If itemId is already in the set, remove the duplicate
                iterator.remove();
            }
        }
    }

    public static void setOneMesoShop(HiredMerchantArtificial merchant) {
        retagShopPrices(merchant, price -> 1);
    }

    public static void applyQuittingSaleDiscount(HiredMerchantArtificial merchant) {
        applyDiscountWholeStore(merchant, 0.7);
    }

    public static void applyCheapSaleDiscount(HiredMerchantArtificial merchant) {
        applyDiscountWholeStore(merchant, 0.85);
    }

    public static void applyDiscountWholeStore(HiredMerchantArtificial merchant, double percentage) {
        retagShopPrices(merchant, price -> (int) (price * percentage));
    }

    /**
     * 引擎差异适配：gms PlayerShopItem.price 为 private final 且无 setPrice（源 SoloMapling fork 有 setPrice），
     * 无法原地改价。等价实现：按原顺序重建条目（保留 item/bundles/存在标记），清空后重新入店。
     * 仅在生成阶段调用（商店尚未广播），此时不存在其他引用者，重建安全。
     */
    private static void retagShopPrices(HiredMerchantArtificial merchant, Function<Integer, Integer> priceFn) {
        List<PlayerShopItem> originals = merchant.getItems();
        List<PlayerShopItem> rebuilt = new ArrayList<>(originals.size());
        for (PlayerShopItem psItem : originals) {
            PlayerShopItem retagged = new PlayerShopItem(psItem.getItem(), psItem.getBundles(), priceFn.apply(psItem.getPrice()));
            if (!psItem.isExist()) {
                retagged.setDoesExist(false);
            }
            rebuilt.add(retagged);
        }
        merchant.clearItems();
        for (PlayerShopItem psItem : rebuilt) {
            merchant.addItem(psItem);
        }
    }

    public static void buyoutSomeItems(HiredMerchantArtificial merchant) {
        for (PlayerShopItem psItem : merchant.getItems()) {
            boolean sold = Math.random() < 0.09; // 9% chance to sell out item
            if (sold) {
                psItem.setBundles((short) 0);
                if (psItem.getBundles() < 1) {
                    psItem.setDoesExist(false);
                }
            }
        }
    }

    public static void applyAdditionalShopMods(HiredMerchantArtificial merchant) {
        increaseQtyForUseEtcInHotrooms(merchant);
    }

    private static void increaseQtyForUseEtcInHotrooms(HiredMerchantArtificial merchant) {
        if (BotEconomy.isHotRoom(merchant.getMapId())) {
            for (PlayerShopItem psItem : merchant.getItems()) {
                if (psItem.getItem().getInventoryType() == InventoryType.USE ||
                        psItem.getItem().getInventoryType() == InventoryType.ETC) {
                    psItem.setBundles((short) (psItem.getBundles() * generateRandomNumber(5, 8)));
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EquipListGenerator 内联（源 soloMapling.FreeMarket.EquipListGenerator，逐行照搬）。
    // 说明：itempool 依赖类组代理不负责此类（源位于 FreeMarket 包，属摆摊管线本体），
    // 且本波次受「只新建 2 个文件」约束，故内联于此。gms 适配：
    //   - EquipMetadataCache.get().getByType → get().nonCash（gms 缓存已排除 cash 装备）
    //   - EquipEntry 无 untradeable/quest/wzPrice 字段 → checkTradeable/checkQuestEquip/getWzPrice 适配
    //   - generateCleanItemEquip → BotLogic.generateCleanItemEquip
    //   - multiplyWzPriceByJobStyle（源 FMEconomyManager）→ 本地私有照搬
    // ═══════════════════════════════════════════════════════════════════════

    private static List<FMEquip> generateEquipList(String itemPool, List<String> equipCategories, String tier) {
        return generateEquipList(itemPool, equipCategories, tier, false);
    }

    private static List<FMEquip> generateEquipList(String itemPool, List<String> equipCategories, String tier, boolean hotRoom) {
        List<FMEquip> equipList = new ArrayList<>();
        List<Integer> selectionPattern = generatePattern(tier, hotRoom);

        // Generate a list of equips based on the pattern
        List<String> selectedEquipCategories = selectEquipCategoriesByPattern(equipCategories, selectionPattern);
        String distTier = distributedTierSelector(tier);
        for (String equipType : selectedEquipCategories) {
            ItemNode item = getRandomItemFull(itemPool, equipType, distTier); // og tier

            if (item == null) {
                continue;
            }

            int itemId = pickRandomVariantId(item.getVariantId());
            int price = item.getCurrentPrice();
            Equip sellItem = (Equip) BotLogic.generateCleanItemEquip(itemId);

            if (checkIfEquipIsScrollable(sellItem)) {
                Equip scrolledItem = ScrollGivenItem(sellItem);
                price = getEquipMarketValue(scrolledItem);
                equipList.add(new FMEquip(scrolledItem, price));
            } else {
                equipList.add(new FMEquip(sellItem, price));
            }
        }
        return equipList;
    }

    private static List<FMEquip> generateEquipListIIPU(String tier, Job jobStyle) {
        return generateEquipListIIPU(tier, jobStyle, false);
    }

    private static List<FMEquip> generateEquipListIIPU(String tier, Job jobStyle, boolean scrollThisItem) {
        if (!EquipMetadataCache.isInitialized()) return Collections.emptyList();

        List<FMEquip> equipList = new ArrayList<>();
        int maxLevel = getLevelOnTier(tier);
        int listSize = 4;
        int maxAttempts = 15;

        for (int i = 0; i < maxAttempts && equipList.size() < listSize; i++) {
            EquipType eqType = getRandomEquipType(jobStyle);
            Integer itemId = getRandomEquipCached(eqType, maxLevel, jobStyle, tier);
            if (itemId != null) {
                FMEquip equip = processItemIdToFMEquip(itemId, scrollThisItem);
                if (equip != null) {
                    equipList.add(equip);
                }
            }
        }
        return equipList;
    }

    private static int getPriceFloor(String tier) {
        return switch (tier.toUpperCase()) {
            case "S" -> 100_000;
            case "A" -> 50_000;
            case "B" -> 20_000;
            default -> 10_000;
        };
    }

    private static int getMinLevelForClass(int maxLevel, boolean classSpecific) {
        if (classSpecific) {
            return Math.max((int) (maxLevel * 0.6), 15);
        }
        return Math.max((int) (maxLevel * 0.25), 10);
    }

    private static Integer getRandomEquipCached(EquipType eqType, int maxLevel, Job jobStyle, String tier) {
        int gender = Math.random() < 0.5 ? 0 : 1;
        int reqJob = getReqJobViaJobStyle(jobStyle);
        boolean classSpecific = reqJob != 0;
        int minLevel = getMinLevelForClass(maxLevel, classSpecific);
        int priceFloor = getPriceFloor(tier);

        // gms 适配：源 getByType 的 EquipEntry 含 untradeable/quest/wzPrice 字段，gms nonCash 缓存
        // 只排除 cash、无这三字段；untradeable/quest 改用 WZ 属性查询，wzPrice 改用 getWzPrice。
        List<EquipMetadataCache.EquipEntry> candidates = EquipMetadataCache.get().nonCash(eqType);
        List<Integer> valid = new ArrayList<>();
        for (EquipMetadataCache.EquipEntry e : candidates) {
            if (isQuestOrUntradeable(e.id)) continue;
            if (e.reqJob != 0 && e.reqJob != reqJob) continue;
            if (reqJob == 0 && e.reqJob != 0) continue;
            if (e.gender != 2 && e.gender != gender) continue;

            if (DesirableEquipList.isDesirable(e.id)) {
                valid.add(e.id);
                continue;
            }

            if (e.reqLevel < minLevel || e.reqLevel > maxLevel) continue;
            if (getWzPrice(e.id) < priceFloor) continue;

            valid.add(e.id);
        }

        if (valid.isEmpty()) return null;
        Collections.sort(valid);
        return selectWeightedRandom(valid);
    }

    /**
     * gms 适配：EquipMetadataCache.EquipEntry 无源中 untradeable/quest 字段，
     * 用 itempool 层已就绪助手等价判定（checkTradeable = 非 untradeableRestricted、
     * checkQuestEquip = 非 quest 装备）。nonCash 缓存构建时已过滤 stats==null 的 id，
     * 助手查询不会命中不存在的装备。
     */
    private static boolean isQuestOrUntradeable(int itemId) {
        return !checkTradeable(itemId) || !checkQuestEquip(itemId);
    }

    private static FMEquip processItemIdToFMEquip(Integer itemId, boolean scrollThisItem) {
        if (itemId != null) {
            Job jobStyle = getJobStyleFromItemId(itemId);
            int price = multiplyWzPriceByJobStyle(getWzPrice(itemId), jobStyle);

            Equip sellItem = (Equip) BotLogic.generateCleanItemEquip(itemId);

            if (Math.random() > 0.5) { // Above average item
                ItemInformationProvider ii = ItemInformationProvider.getInstance();
                ii.randomizeUpgradeStats(sellItem);
                price = (int) (price * 1.5);
            }

            if (checkIfEquipIsScrollable(sellItem) && scrollThisItem) {
                Equip scrolledItem = ScrollGivenItem(sellItem);
                Map<Integer, Integer> priceMap = Map.of(1, price);
                ItemNode item = new ItemNode(null, null, null, priceMap);
                price = getEquipMarketValue(scrolledItem);
                return (new FMEquip(scrolledItem, price));
            } else {
                return (new FMEquip(sellItem, price));
            }
        }
        return null; // failed to process
    }

    // 源 FMEconomyManager.multiplyWzPriceByJobStyle 逐行照搬（gms BotEconomy 未收录此方法）。
    private static int multiplyWzPriceByJobStyle(int price, Job jobStyle) {
        int adjustedPrice = (int) (price * 1.5);
        if (jobStyle == Job.WARRIOR) {
            adjustedPrice = (int) (adjustedPrice * 1.4);
        } else if (jobStyle == Job.MAGICIAN) {
            adjustedPrice = (int) (adjustedPrice * 1.35);
        } else if (jobStyle == Job.BOWMAN) {
            adjustedPrice = (int) (adjustedPrice * 1.30);
        } else if (jobStyle == Job.THIEF) {
            adjustedPrice = (int) (adjustedPrice * 1.5);
        } else if (jobStyle == Job.PIRATE) {
            adjustedPrice = (int) (adjustedPrice * 1.25);
        } else {
            adjustedPrice = (int) (adjustedPrice * 1.30);
        }
        return adjustedPrice;
    }

    private static List<String> selectEquipCategoriesByPattern(List<String> equipCategories, List<Integer> pattern) {
        List<String> selectedEquipCategories = new ArrayList<>();

        // Select unique elements from equipCategories based on the number of items in the pattern
        List<String> randomSelection = getRandomUniqueSelection(equipCategories, pattern.size());

        // Add each selected element to the list according to the pattern count
        for (int i = 0; i < pattern.size(); i++) {
            int count = pattern.get(i);
            String equipType = randomSelection.get(i);

            // Add 'count' copies of equipType to the final list
            selectedEquipCategories.addAll(Collections.nCopies(count, equipType));
        }

        return selectedEquipCategories;
    }

    private static List<String> getRandomUniqueSelection(List<String> equipCategories, int count) {
        if (count > equipCategories.size()) {
            count = equipCategories.size();
        }

        // Generate a list of indices to shuffle
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < equipCategories.size(); i++) {
            indices.add(i);
        }

        // Shuffle and pick the first 'count' indices
        Collections.shuffle(indices);
        List<Integer> selectedIndices = indices.subList(0, count);

        // Sort indices to maintain original order of elements
        Collections.sort(selectedIndices);

        // Collect selected elements based on sorted indices
        List<String> selectedItems = new ArrayList<>();
        for (int index : selectedIndices) {
            selectedItems.add(equipCategories.get(index));
        }

        return selectedItems;
    }

    private static Integer getLevelOnTier(String tier) {
        int maxLevel = 70;
        if ("S".equals(tier)) {
            int min = 80;
            int max = 120;
            maxLevel = new java.util.Random().nextInt(max - min + 1) + min;
        } else if ("A".equals(tier)) {
            int min = 60;
            int max = 80;
            maxLevel = new java.util.Random().nextInt(max - min + 1) + min;
        } else if ("B".equals(tier)) {
            int min = 30;
            int max = 70;
            maxLevel = new java.util.Random().nextInt(max - min + 1) + min;
        } else {
            int min = 0;
            int max = 40;
            maxLevel = new java.util.Random().nextInt(max - min + 1) + min;
        }

        return maxLevel;
    }

    private static List<Integer> generatePattern(String tier, boolean hotRoom) {
        if (hotRoom && "S".equalsIgnoreCase(tier)) {
            return List.of(2, 2, 1, 1);
        }
        return switch (tier.toUpperCase()) {
            case "S" -> List.of(2, 1, 1);
            case "A", "B" -> List.of(2, 2);
            default -> List.of(2, 1, 1);
        };
    }
}
