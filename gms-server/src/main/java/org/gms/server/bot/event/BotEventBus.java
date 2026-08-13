package org.gms.server.bot.event;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bot 事件总线（参考 SoloMapling 的 EventBus 移植）。
 * <p>
 * publish 是<b>同步分发、无异常保护、不离载</b>：订阅者的 onEvent 直接跑在发布线程上。
 * 因此任何订阅者都必须自保——重活转虚拟线程，异常自行吞掉
 * （{@code BotMapEntryResponder} 即按此模式实现）。bot 本体只把事件塞进每-bot 缓冲，
 * 在自己的 tick 里消费。
 */
public final class BotEventBus {

    private static final BotEventBus INSTANCE = new BotEventBus();

    private final Map<EventType, CopyOnWriteArrayList<EventSubscriber>> subscribers = new ConcurrentHashMap<>();
    private final BotEventStore eventStore = BotEventStore.getInstance();

    private BotEventBus() {
    }

    public static BotEventBus getInstance() {
        return INSTANCE;
    }

    /** 同步发布：匹配过滤器的订阅者立即收到事件，同时写入全局历史。 */
    public void publish(GameEvent event) {
        eventStore.add(event);
        CopyOnWriteArrayList<EventSubscriber> list = subscribers.get(event.getType());
        if (list == null || list.isEmpty()) {
            return;
        }
        for (EventSubscriber subscriber : list) {
            if (subscriber.matchesFilter(event)) {
                subscriber.onEvent(event);
            }
        }
    }

    /** 幂等订阅：同一订阅者重复订阅同一类型不会重复收到。 */
    public void subscribe(EventType type, EventSubscriber subscriber) {
        subscribers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).addIfAbsent(subscriber);
    }

    public void unsubscribe(EventType type, EventSubscriber subscriber) {
        CopyOnWriteArrayList<EventSubscriber> list = subscribers.get(type);
        if (list != null) {
            list.remove(subscriber);
        }
    }

    /** 退订某订阅者的全部类型。 */
    public void unsubscribeAll(EventSubscriber subscriber) {
        subscribers.forEach((type, list) -> list.remove(subscriber));
    }

    /** 某类型当前订阅者数量（调试/测试用）。 */
    public int subscriberCount(EventType type) {
        CopyOnWriteArrayList<EventSubscriber> list = subscribers.get(type);
        return list == null ? 0 : list.size();
    }

    /** 重置总线状态（仅测试用；生产环境订阅者生命周期与服务器一致）。 */
    public void reset() {
        subscribers.clear();
    }
}
