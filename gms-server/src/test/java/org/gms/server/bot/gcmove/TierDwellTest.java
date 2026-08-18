package org.gms.server.bot.gcmove;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 门禁（离线，不碰 WZ）：层级滞回是纯时钟注入状态机，因此晋升（立即）、
 * 降级（滞留期满后）以及 pacing 玩家不抖动的性质都能用假时钟确定性验证。
 * 移植自 SoloMapling 的 TierDwellTest，断言语义不变；被测的 TierDwell 是包私有类，
 * 故测试与之同包。
 */
class TierDwellTest {

    @Test
    void promotesImmediatelyAndDemotesAfterDwell() {
        TierDwell d = new TierDwell(4000);

        d.observe(Set.of(100, 200), 0);
        assertTrue(d.isActive(100));
        assertTrue(d.isActive(200));
        assertFalse(d.isActive(300), "未被观察的地图不活跃");

        // 200 在 t=1000 停止被观察；它保持活跃直到截止时间（0 + 4000）。
        d.observe(Set.of(100), 1000);
        assertTrue(d.isActive(200), "滞留期内 -> 仍活跃");

        d.observe(Set.of(100), 3999);
        assertTrue(d.isActive(200), "距截止时间 1ms -> 仍活跃");

        d.observe(Set.of(100), 4000);
        assertFalse(d.isActive(200), "截止时间到 -> 降级");
        assertTrue(d.isActive(100), "仍被观察 -> 保持活跃");

        // 晋升是即时的：全新地图在被观察的瞬间就活跃。
        d.observe(Set.of(100, 500), 4000);
        assertTrue(d.isActive(500));
    }

    @Test
    void pacingPlayerDoesNotFlap() {
        // 只在偶数秒被观察（玩家在传送门之间踱步）。间隔（1s）< 滞留期（4s），
        // 因此地图绝不能掉到不活跃。
        TierDwell d = new TierDwell(4000);
        for (long t = 0; t <= 20_000; t += 1000) {
            Set<Integer> observed = (t / 1000) % 2 == 0 ? Set.of(100) : Set.of();
            d.observe(observed, t);
            assertTrue(d.isActive(100), "t=" + t + " 处不应抖动");
        }
    }
}
