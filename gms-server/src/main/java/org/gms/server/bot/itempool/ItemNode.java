package org.gms.server.bot.itempool;

import java.util.List;
import java.util.Map;

import static org.gms.server.bot.itempool.ItemSelector.getPriceForVersion;

/**
 * 移植自 SoloMapling {@code soloMapling.itemPool.ItemNode}。
 * 无外部依赖，仅换包名；逻辑与源完全一致。
 */
public class ItemNode {
    private String item;
    private List<Integer> variantId;
    private Map<Integer, String> tier;
    private Map<Integer, Integer> price;

    // Constructor to initialize fields directly
    public ItemNode(String item, List<Integer> variantId, Map<Integer, String> tier, Map<Integer, Integer> price) {
        this.item = item;
        this.variantId = variantId;
        this.tier = tier;
        this.price = price;
    }

    public String getItem() {
        return this.item;
    }

    public List<Integer> getVariantId() {
        return this.variantId;
    }

    public Map<Integer, String> getTier() {
        return this.tier;
    }

    public Map<Integer, Integer> getPriceMap() {
        return this.price;
    }

    public int getCurrentPrice() {
        return getPriceForVersion(this.getPriceMap());
    }
}
