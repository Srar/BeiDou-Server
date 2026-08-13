package org.gms.server.bot.event;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 每 bot 一个的事件缓冲：容量 100，满则丢最旧。
 * 底层 {@link ConcurrentLinkedQueue} 的 size() 是 O(n) 且 check-then-act 非原子，
 * 并发 add 下容量是「软上限」——功能无害，别当精确上限。
 */
public final class BotEventBuffer {

    private final int maxSize;
    private final ConcurrentLinkedQueue<GameEvent> queue = new ConcurrentLinkedQueue<>();

    public BotEventBuffer(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
    }

    /** 入队；满则先丢最旧再入队。 */
    public void add(GameEvent event) {
        while (queue.size() >= maxSize) {
            queue.poll();
        }
        queue.offer(event);
    }

    /** 出队一个；空返回 null。 */
    public GameEvent poll() {
        return queue.poll();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }
}
