package org.gms.server.bot.freemarket;

import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Item;
import org.gms.server.ItemInformationProvider;
import org.gms.server.bot.BotLogic;
import org.gms.util.Randomizer;

import java.util.ArrayList;
import java.util.List;

/**
 * 自由市场商店物品目录（逐行移植自 SoloMapling FreeMarket.ArtificialShopGenerator 中
 * 商业类 Bot 用到的子集：scrolls / darkScrolls / thiefStars / potions / commonEquip / generateItem）。
 * <p>
 * gms 底座差异：无 ItemSelector / QuantitySelector / YAML 物品池，改用硬编码的 v83 物品 ID
 * 子集，抽取数量与源保持一致（scrolls=4、darkScrolls=4、thiefStars=2、potions=3）。
 * TODO(物品池)：ItemSelector / YAML 物品池落地后恢复按 tier 加权抽取。
 */
public final class BotShopCatalog {

    private BotShopCatalog() {
    }

    // 代表性 v83 物品 ID（源从 scrolls.yaml / darkscrolls.yaml / thief.yaml / useables.yaml 抽取）。
    private static final List<Integer> SCROLLS = List.of(
            2040000, 2040002, 2040800, 2040805, 2041000, 2041002, 2043000, 2043001);
    private static final List<Integer> DARK_SCROLLS = List.of(
            2040100, 2040101, 2040803, 2041004);
    private static final List<Integer> THIEF_STARS = List.of(
            2070000, 2070001, 2070002, 2070003, 2070004, 2070005, 2070006);
    private static final List<Integer> POTIONS = List.of(
            2000000, 2000001, 2000002, 2000003, 2000005);
    private static final List<Integer> COMMON_EQUIPS = List.of(
            1002140, 1040000, 1060000, 1072001, 1082000);

    /** 源 ArtificialShopGenerator.generateItem：按给定 position/qty 构造 Item。 */
    public static Item generateItem(int itemId, int position, int qty) {
        return new Item(itemId, (short) position, (short) qty);
    }

    public static List<FMItem> generateScrollsList(String tier) {
        return pickItems(SCROLLS, 4);
    }

    public static List<FMItem> generateDarkScrollsList(String tier) {
        return pickItems(DARK_SCROLLS, 4);
    }

    public static List<FMItem> generateThiefStarsList(String tier) {
        return pickItems(THIEF_STARS, 2);
    }

    public static List<FMItem> generatePotionsList(String tier) {
        return pickItems(POTIONS, 3);
    }

    /**
     * 源 ArtificialShopGenerator.generateCommonEquipList：common.yaml + IIPU 装备清单。
     * gms 简化：返回常见初级装备的 FMEquip 清单（保证非空，ScrollingBot 依赖非空推进）。
     */
    public static List<FMEquip> generateCommonEquipList(String tier) {
        List<FMEquip> out = new ArrayList<>();
        for (int itemId : COMMON_EQUIPS) {
            Item item = BotLogic.generateCleanItemEquip(itemId);
            if (item instanceof Equip equip) {
                int price = ItemInformationProvider.getInstance().getWholePrice(itemId);
                out.add(new FMEquip(equip, price));
            }
        }
        return out;
    }

    private static List<FMItem> pickItems(List<Integer> pool, int count) {
        List<FMItem> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int itemId = pool.get(Randomizer.nextInt(pool.size()));
            int price = ItemInformationProvider.getInstance().getWholePrice(itemId);
            out.add(new FMItem(itemId, price, 1));
        }
        return out;
    }
}
