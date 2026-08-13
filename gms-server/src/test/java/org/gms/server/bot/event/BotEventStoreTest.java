package org.gms.server.bot.event;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BotEventStore 专项：环形回绕、负序号（AtomicInteger 溢出后索引必须仍非负）、
 * 历史不足时的 null 槽。
 */
class BotEventStoreTest {

    private static GameEvent event(int n) {
        return GameEvent.chat(0, 1, 100000000, n, "m" + n);
    }

    @Test
    void recentReturnsLatestInReverseOrder() {
        BotEventStore store = BotEventStore.getInstance();
        long seqBefore = currentCursor(store);
        for (int i = 1; i <= 10; i++) {
            store.add(event(i));
        }

        GameEvent[] recent = store.recent(3);
        assertEquals(3, recent.length);
        assertEquals(10, recent[0].getSourceCharacterId());
        assertEquals(9, recent[1].getSourceCharacterId());
        assertEquals(8, recent[2].getSourceCharacterId());
        assertTrue(currentCursor(store) >= seqBefore + 10);
    }

    @Test
    void ringWrapsAroundWithoutCrashing() {
        BotEventStore store = BotEventStore.getInstance();
        // 写入超过容量两倍的事件：回绕必须平稳（floorMod 索引恒非负）
        for (int i = 1; i <= 12000; i++) {
            store.add(event(i));
        }

        GameEvent[] recent = store.recent(1);
        assertNotNull(recent[0]);
        assertEquals(12000, recent[0].getSourceCharacterId());
    }

    @Test
    void negativeSequenceKeepsIndexNonNegative() throws Exception {
        BotEventStore store = BotEventStore.getInstance();
        // 把 cursor 推到溢出边缘（Integer.MAX_VALUE - 2），再写 5 个事件越过溢出点：
        // 修复前 (int)(seq % CAPACITY) 在 seq 为负时抛 AIOOBE，修复后 floorMod 恒非负
        setCursor(store, Integer.MAX_VALUE - 2L);
        for (int i = 1; i <= 5; i++) {
            store.add(event(i));
        }

        GameEvent[] recent = store.recent(5);
        assertEquals(5, recent.length);
        assertNotNull(recent[0], "events written across the overflow point must be readable");
        assertEquals(5, recent[0].getSourceCharacterId());
    }

    @Test
    void recentAlwaysReturnsRequestedLength() {
        // recent 契约：恒返回 min(n, CAPACITY) 长度的数组；历史不足时未写槽为 null，
        // 消费方必须判空（这里只锁接口契约本身）
        GameEvent[] recent = BotEventStore.getInstance().recent(5000);
        assertEquals(5000, recent.length, "recent must always return the requested length");
    }

    private static long currentCursor(BotEventStore store) {
        try {
            Field f = BotEventStore.class.getDeclaredField("cursor");
            f.setAccessible(true);
            return ((AtomicInteger) f.get(store)).get();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setCursor(BotEventStore store, long value) {
        try {
            Field f = BotEventStore.class.getDeclaredField("cursor");
            f.setAccessible(true);
            ((AtomicInteger) f.get(store)).set((int) value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
