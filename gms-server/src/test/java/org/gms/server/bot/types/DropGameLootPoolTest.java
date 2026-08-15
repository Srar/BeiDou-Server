package org.gms.server.bot.types;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DropGameLootPool 资源加载回归测试：验证 YAML 资源路径（绝对 classpath 路径）能命中，
 * 而非解析到包相对路径导致返回空池。
 */
class DropGameLootPoolTest {

    @Test
    void loadMediumTierResolvesClasspathResource() {
        DropGameLootPool pool = DropGameLootPool.load("medium");
        assertFalse(pool.isEmpty(), "medium tier loot pool must not be empty");
        assertTrue(pool.size() > 0, "medium tier must contain loot entries");
    }

    @Test
    void loadEliteTierResolvesClasspathResource() {
        DropGameLootPool pool = DropGameLootPool.load("elite");
        assertFalse(pool.isEmpty(), "elite tier loot pool must not be empty");
        assertTrue(pool.size() > 0, "elite tier must contain loot entries");
    }
}
