package org.gms.server.bot.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件总线：同步分发、过滤器匹配、幂等订阅、退订、全量退订与历史环形缓冲。
 */
class BotEventBusTest {

    @AfterEach
    void resetBus() {
        BotEventBus.getInstance().reset();
    }

    private static class CollectingSubscriber implements EventSubscriber {
        final List<GameEvent> received = new CopyOnWriteArrayList<>();
        private final boolean match;

        CollectingSubscriber(boolean match) {
            this.match = match;
        }

        @Override
        public void onEvent(GameEvent event) {
            received.add(event);
        }

        @Override
        public boolean matchesFilter(GameEvent event) {
            return match;
        }
    }

    @Test
    void matchedSubscriberReceivesEvent() {
        CollectingSubscriber sub = new CollectingSubscriber(true);
        BotEventBus bus = BotEventBus.getInstance();
        bus.subscribe(EventType.CHAT, sub);

        GameEvent event = GameEvent.chat(0, 1, 100000000, 42, "hi");
        bus.publish(event);

        assertEquals(1, sub.received.size());
        assertEquals("hi", sub.received.get(0).getText());
        assertEquals(42, sub.received.get(0).getSourceCharacterId());
    }

    @Test
    void nonMatchingSubscriberIsSkipped() {
        CollectingSubscriber sub = new CollectingSubscriber(false);
        BotEventBus bus = BotEventBus.getInstance();
        bus.subscribe(EventType.CHAT, sub);

        bus.publish(GameEvent.chat(0, 1, 100000000, 42, "hi"));

        assertTrue(sub.received.isEmpty(), "non-matching subscriber must not receive events");
    }

    @Test
    void eventOfOtherTypeIsNotDelivered() {
        CollectingSubscriber sub = new CollectingSubscriber(true);
        BotEventBus bus = BotEventBus.getInstance();
        bus.subscribe(EventType.CHAT, sub);

        bus.publish(GameEvent.mapEntered(0, 1, 100000000, 7));

        assertTrue(sub.received.isEmpty(), "CHAT subscriber must not receive MAP_ENTERED");
    }

    @Test
    void subscribeIsIdempotent() {
        CollectingSubscriber sub = new CollectingSubscriber(true);
        BotEventBus bus = BotEventBus.getInstance();
        bus.subscribe(EventType.CHAT, sub);
        bus.subscribe(EventType.CHAT, sub);

        assertEquals(1, bus.subscriberCount(EventType.CHAT), "duplicate subscription registered twice");
        bus.publish(GameEvent.chat(0, 1, 100000000, 42, "hi"));
        assertEquals(1, sub.received.size(), "duplicate subscription delivered twice");
    }

    @Test
    void unsubscribeStopsDelivery() {
        CollectingSubscriber sub = new CollectingSubscriber(true);
        BotEventBus bus = BotEventBus.getInstance();
        bus.subscribe(EventType.CHAT, sub);
        bus.unsubscribe(EventType.CHAT, sub);

        bus.publish(GameEvent.chat(0, 1, 100000000, 42, "hi"));

        assertTrue(sub.received.isEmpty(), "unsubscribed subscriber still received events");
        assertEquals(0, bus.subscriberCount(EventType.CHAT));
    }

    @Test
    void unsubscribeAllRemovesEveryType() {
        CollectingSubscriber sub = new CollectingSubscriber(true);
        BotEventBus bus = BotEventBus.getInstance();
        bus.subscribe(EventType.CHAT, sub);
        bus.subscribe(EventType.MAP_ENTERED, sub);

        bus.unsubscribeAll(sub);

        assertEquals(0, bus.subscriberCount(EventType.CHAT));
        assertEquals(0, bus.subscriberCount(EventType.MAP_ENTERED));
    }

    @Test
    void publishWithoutSubscribersIsSafe() {
        BotEventBus bus = BotEventBus.getInstance();
        bus.publish(GameEvent.chat(0, 1, 100000000, 42, "hi"));
        // 无订阅者：不抛异常即通过
        assertTrue(true);
    }

    @Test
    void eventStoreKeepsRecentHistory() {
        BotEventBus bus = BotEventBus.getInstance();
        AtomicInteger seq = new AtomicInteger();
        CollectingSubscriber sub = new CollectingSubscriber(true) {
            @Override
            public void onEvent(GameEvent event) {
                seq.set(event.getSourceCharacterId());
            }
        };
        bus.subscribe(EventType.CHAT, sub);

        for (int i = 1; i <= 10; i++) {
            bus.publish(GameEvent.chat(0, 1, 100000000, i, "m" + i));
        }

        GameEvent[] recent = BotEventStore.getInstance().recent(10);
        assertTrue(recent.length >= 1);
        // 最近一条是最后发布的 10 号事件
        assertTrue(recent[0] != null, "store should hold the latest event");
        assertEquals(10, recent[0].getSourceCharacterId());
        assertFalse(seq.get() == 0, "subscriber should have received events");
    }
}
