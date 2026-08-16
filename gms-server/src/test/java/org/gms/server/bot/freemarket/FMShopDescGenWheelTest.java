package org.gms.server.bot.freemarket;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 招牌取词无放回轮盘回归：同批生成时同一词只出现一次（修复「求带飞」连撞 3 摊）。
 */
class FMShopDescGenWheelTest {

    @Test
    void descriptionWheelDrawsWithoutReplacement() {
        // fmclan 池 540 条：连续取 50 次应全部不同（无放回）
        Set<String> clans = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String c = FMShopDescGen.getRandomStoreDescription("fmclan");
            assertFalse(c.isBlank(), "clan must not be blank");
            assertTrue(clans.add(c), "duplicate clan drawn: " + c);
        }
        // shortword 池 661 条：同样无放回
        Set<String> words = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String w = FMShopDescGen.getRandomStoreDescription("shortword");
            assertFalse(w.isBlank(), "shortword must not be blank");
            assertTrue(words.add(w), "duplicate shortword drawn: " + w);
        }
    }

    @Test
    void wheelExhaustionReshufflesWithoutException() {
        // 池耗尽（540 条）后继续取不抛异常（重洗语义）
        for (int i = 0; i < 560; i++) {
            assertNotNull(FMShopDescGen.getRandomStoreDescription("fmclan"));
        }
    }

    @Test
    void getRandomTopFMClanIsNonNull() {
        for (int i = 0; i < 20; i++) {
            String clan = FMShopDescGen.getRandomTopFMClan();
            assertNotNull(clan);
            assertFalse(clan.isBlank());
        }
    }
}
