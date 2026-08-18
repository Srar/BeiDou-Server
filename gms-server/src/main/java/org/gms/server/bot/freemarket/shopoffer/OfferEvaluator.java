package org.gms.server.bot.freemarket.shopoffer;

import org.gms.util.Randomizer;

import java.util.function.DoubleSupplier;

/**
 * 报价评估器（移植自 SoloMapling FreeMarket.ShopOfferSystem.OfferEvaluator）。
 * <p>
 * 规则（与源一致）：
 * <ul>
 *   <li>报价低于标价 70% → 直接拒绝；</li>
 *   <li>否则按报价/标价比率区间掷接受概率（区间越高概率越高，附随机抖动）；
 *       命中即成交；</li>
 *   <li>未命中且比率 ≥ 80% → 还价；否则拒绝。</li>
 * </ul>
 * 还价公式：counter = (报价 + 标价) / 2。
 */
public class OfferEvaluator {

    public enum Decision {
        DECLINE,
        ACCEPT,
        COUNTER
    }

    public static Decision evaluate(long offerPrice, int listingPrice) {
        return evaluate(offerPrice, listingPrice, Randomizer::nextDouble, Randomizer::nextDouble);
    }

    /**
     * 注入随机源（测试专用）：jitter 用于接受概率区间抖动，roll 用于接受判定掷点，
     * 两者独立可控，保证 ACCEPT/COUNTER/DECLINE 分支确定性。
     */
    static Decision evaluate(long offerPrice, int listingPrice, DoubleSupplier jitter, DoubleSupplier roll) {
        if (listingPrice <= 0) {
            return Decision.DECLINE;
        }

        double ratio = offerPrice / (double) listingPrice;

        if (ratio < 0.70) {
            return Decision.DECLINE;
        }

        double acceptChance = getAcceptChance(ratio, jitter);

        if (roll.getAsDouble() < acceptChance) {
            return Decision.ACCEPT;
        }

        if (ratio >= 0.80) {
            return Decision.COUNTER;
        }

        return Decision.DECLINE;
    }

    private static double getAcceptChance(double ratio, DoubleSupplier jitter) {
        if (ratio >= 0.95) {
            return 0.75 + jitter.getAsDouble() * 0.25;
        } else if (ratio >= 0.90) {
            return 0.50 + jitter.getAsDouble() * 0.20;
        } else if (ratio >= 0.85) {
            return 0.40 + jitter.getAsDouble() * 0.20;
        } else if (ratio >= 0.80) {
            return 0.25 + jitter.getAsDouble() * 0.15;
        } else {
            return 0.05 + jitter.getAsDouble() * 0.10;
        }
    }

    public static long calculateCounterPrice(long offerPrice, int listingPrice) {
        return (offerPrice + listingPrice) / 2;
    }
}
