package org.gms.server.bot.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每-bot 事件缓冲：FIFO、容量上限丢最旧、空缓冲 poll 语义。
 */
class BotEventBufferTest {

    private static GameEvent event(int n) {
        return GameEvent.chat(0, 1, 100000000, n, "msg" + n);
    }

    @Test
    void fifoOrder() {
        BotEventBuffer buffer = new BotEventBuffer(10);
        buffer.add(event(1));
        buffer.add(event(2));
        buffer.add(event(3));

        assertEquals(1, buffer.poll().getSourceCharacterId());
        assertEquals(2, buffer.poll().getSourceCharacterId());
        assertEquals(3, buffer.poll().getSourceCharacterId());
        assertNull(buffer.poll(), "empty buffer should poll null");
        assertTrue(buffer.isEmpty());
    }

    @Test
    void dropsOldestWhenFull() {
        BotEventBuffer buffer = new BotEventBuffer(3);
        for (int i = 1; i <= 5; i++) {
            buffer.add(event(i));
        }

        assertEquals(3, buffer.size());
        assertEquals(3, buffer.poll().getSourceCharacterId(), "oldest surviving event must be 3");
        assertEquals(4, buffer.poll().getSourceCharacterId());
        assertEquals(5, buffer.poll().getSourceCharacterId());
        assertTrue(buffer.isEmpty());
    }

    @Test
    void sizeReflectsContents() {
        BotEventBuffer buffer = new BotEventBuffer(100);
        assertTrue(buffer.isEmpty());
        buffer.add(event(1));
        assertEquals(1, buffer.size());
        assertFalse(buffer.isEmpty());
        buffer.poll();
        assertEquals(0, buffer.size());
    }

    @Test
    void capacityBelowOneClamped() {
        BotEventBuffer buffer = new BotEventBuffer(0);
        buffer.add(event(1));
        assertEquals(1, buffer.size(), "capacity 0 must be clamped to at least 1");
    }
}
