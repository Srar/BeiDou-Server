package org.gms.server.bot.freemarket.shopoffer;

import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HaggleSession 单测：3 次上限、60 秒过期（注入时钟可控推进）、counter 状态。
 */
class HaggleSessionTest {

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    private static HaggleSession sessionWithClock(AtomicLong clock) {
        return new HaggleSession(5, 100, clock::get);
    }

    @Test
    void threeAttemptsExceedLimit() {
        HaggleSession session = new HaggleSession(5, 100);
        assertFalse(session.hasExceededAttempts(), "新会话不应超限");

        session.incrementAttempt();
        session.incrementAttempt();
        assertEquals(2, session.getAttempts());
        assertFalse(session.hasExceededAttempts(), "2 次报价不应超限");

        session.incrementAttempt();
        assertEquals(3, session.getAttempts());
        assertTrue(session.hasExceededAttempts(), "第 3 次报价即触发踢人上限");
    }

    @Test
    void counterPriceTracksPendingState() {
        HaggleSession session = new HaggleSession(5, 100);
        assertEquals(-1L, session.getCounterPrice(), "初始无还价");
        assertFalse(session.hasCounterPending());

        session.setCounterPrice(70_000_000L);
        assertEquals(70_000_000L, session.getCounterPrice());
        assertTrue(session.hasCounterPending(), "设置还价后应处于待定状态");
    }

    @Test
    void sessionExpiresAfterSixtySeconds() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        HaggleSession session = sessionWithClock(clock);

        clock.set(1_000_000L + HaggleSession.EXPIRY_MS);
        assertFalse(session.isExpired(), "恰满 60 秒未过期（严格大于）");

        clock.set(1_000_000L + HaggleSession.EXPIRY_MS + 1);
        assertTrue(session.isExpired(), "超过 60 秒应过期");
    }

    @Test
    void activityResetsExpiry() {
        AtomicLong clock = new AtomicLong(0L);
        HaggleSession session = sessionWithClock(clock);

        clock.set(HaggleSession.EXPIRY_MS - 1);
        session.incrementAttempt(); // 活动更新 lastActivityTime
        clock.set(HaggleSession.EXPIRY_MS);
        assertFalse(session.isExpired(), "incrementAttempt 续期后不应立即过期");

        clock.set(HaggleSession.EXPIRY_MS + 1);
        session.touch();
        clock.set(2 * HaggleSession.EXPIRY_MS);
        assertFalse(session.isExpired(), "touch 续期后 60 秒内不应过期");
        clock.set(2 * HaggleSession.EXPIRY_MS + 2);
        assertTrue(session.isExpired());
    }

    @Test
    void idsArePreserved() {
        HaggleSession session = new HaggleSession(42, 99);
        assertEquals(42, session.getPlayerId());
        assertEquals(99, session.getShopOwnerId());
    }
}
