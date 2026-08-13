package org.gms.server.bot.event;

/**
 * Bot 事件订阅者。发布是同步的：{@code onEvent} 里不要做重活，
 * 需要异步时自己转虚拟线程；也绝不能向外抛异常（会炸掉发布线程）。
 */
public interface EventSubscriber {

    /** 事件入站。实现应保持极轻（bot 基类只入缓冲队列）。 */
    void onEvent(GameEvent event);

    /** 该订阅者是否关心此事件。不匹配的事件不会调用 {@link #onEvent}。 */
    boolean matchesFilter(GameEvent event);
}
