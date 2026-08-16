package org.gms.server.bot.itempool;

import java.util.List;
import java.util.Map;

/**
 * 移植自 SoloMapling {@code soloMapling.itemPool.ScrollNode}。
 * 无外部依赖，仅换包名；逻辑与源完全一致。
 */
public class ScrollNode extends ItemNode {
    private int successRate;
    private int statBonus;

    public ScrollNode(String item, List<Integer> variantId, Map<Integer, String> tier, Map<Integer, Integer> price,
                      int successRate, int statBonus) {
        super(item, variantId, tier, price);
        this.successRate = successRate;
        this.statBonus = statBonus;
    }

    public int getSuccessRate() {
        return this.successRate;
    }

    public int getStatBonus() {
        return this.statBonus;
    }
}
