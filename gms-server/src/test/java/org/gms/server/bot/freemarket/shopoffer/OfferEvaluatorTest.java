package org.gms.server.bot.freemarket.shopoffer;

import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OfferEvaluator 阈值单测：70% 拒绝线、counter 公式、ACCEPT/COUNTER/DECLINE
 * 随机分支经注入 DoubleSupplier 确定性覆盖。
 */
class OfferEvaluatorTest {

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    /** jitter=0 → 各区间接受概率取下界；roll=1.0 → 永不命中接受判定。 */
    private static OfferEvaluator.Decision evaluateNoAccept(long offer, int listing) {
        return OfferEvaluator.evaluate(offer, listing, () -> 0.0, () -> 1.0);
    }

    private static OfferEvaluator.Decision evaluateWithRoll(long offer, int listing, double roll) {
        return OfferEvaluator.evaluate(offer, listing, () -> 0.0, () -> roll);
    }

    // ─────────────────────────── 阈值与拒绝 ───────────────────────────

    @Test
    void offerBelowSeventyPercentIsDeclined() {
        assertEquals(OfferEvaluator.Decision.DECLINE, OfferEvaluator.evaluate(69, 100, () -> 0.0, () -> 0.0));
        assertEquals(OfferEvaluator.Decision.DECLINE, evaluateNoAccept(699_999, 1_000_000));
        assertEquals(OfferEvaluator.Decision.DECLINE, evaluateNoAccept(50_000_000, 100_000_000), "50% 报价必拒");
    }

    @Test
    void zeroOrNegativeListingIsDeclined() {
        assertEquals(OfferEvaluator.Decision.DECLINE, evaluateNoAccept(100, 0));
        assertEquals(OfferEvaluator.Decision.DECLINE, evaluateNoAccept(100, -5));
    }

    @Test
    void exactlySeventyPercentIsNotAutoDeclined() {
        // 比率 0.70 不触发 <0.70 拒绝线；jitter=0 → acceptChance=0.05，roll=1.0 不中；
        // 比率 <0.80 → 最终 DECLINE（但走的是随机分支而非阈值分支）
        assertEquals(OfferEvaluator.Decision.DECLINE, evaluateNoAccept(70, 100));
    }

    // ─────────────────────────── ACCEPT/COUNTER/DECLINE 可控分支 ───────────────────────────

    @Test
    void lowBallBetweenSeventyAndEightyCanAcceptOrDecline() {
        // 0.75 区间：jitter=0 → acceptChance=0.05
        assertEquals(OfferEvaluator.Decision.ACCEPT, evaluateWithRoll(75, 100, 0.01), "roll 0.01 < 0.05 → ACCEPT");
        assertEquals(OfferEvaluator.Decision.DECLINE, evaluateWithRoll(75, 100, 0.90), "roll 0.90 不中且比率 <0.80 → DECLINE");
    }

    @Test
    void offerAtEightyPercentCountersWhenRollMisses() {
        // 0.80 区间：jitter=0 → acceptChance=0.25
        assertEquals(OfferEvaluator.Decision.ACCEPT, evaluateWithRoll(80, 100, 0.10), "roll 0.10 < 0.25 → ACCEPT");
        assertEquals(OfferEvaluator.Decision.COUNTER, evaluateWithRoll(80, 100, 0.90), "roll 不中且比率 ≥0.80 → COUNTER");
    }

    @Test
    void highOfferCountersWhenRollMisses() {
        assertEquals(OfferEvaluator.Decision.COUNTER, evaluateNoAccept(95, 100), "0.95 比率 roll 不中 → COUNTER");
        assertEquals(OfferEvaluator.Decision.COUNTER, evaluateWithRoll(95, 100, 0.99), "jitter=0 时 acceptChance=0.75 下界，roll 0.99 不中 → COUNTER");
        assertEquals(OfferEvaluator.Decision.ACCEPT, OfferEvaluator.evaluate(95, 100, () -> 1.0, () -> 0.99),
                "jitter=1.0 → acceptChance=1.0，roll 0.99 → ACCEPT");
    }

    // ─────────────────────────── counter 公式 ───────────────────────────

    @Test
    void counterPriceIsMidpoint() {
        assertEquals(75_000_000L, OfferEvaluator.calculateCounterPrice(50_000_000, 100_000_000));
        assertEquals(75L, OfferEvaluator.calculateCounterPrice(50, 100));
        assertEquals(87L, OfferEvaluator.calculateCounterPrice(75, 100), "奇数中点向下截断");
    }

    @Test
    void publicEvaluateMatchesInjectedDeterministicResult() {
        // 公开入口在比率 <0.70 时不消耗随机数，结果与注入版一致（拒绝线为纯阈值）
        assertEquals(OfferEvaluator.Decision.DECLINE, OfferEvaluator.evaluate(1, 100));
    }
}
