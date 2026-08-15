package org.gms.server.bot.messaging;

import lombok.extern.slf4j.Slf4j;
import org.gms.server.TimerManager;
import org.gms.server.bot.BotExecutors;

import java.util.Collection;
import java.util.concurrent.ScheduledFuture;

@Slf4j
public class QueueCleaner implements Runnable {

    // per-queue 过期时间（ms）：此前三队列统一 10s 一刀切。
    // primary 保持 10s（点名即时性：陈旧点名消息不配启动会话）；
    // secondary 30s（会话 follow-up 窗口，SocialBot 以 1s timeout 轮询消费）；
    // tertiary 45s（对齐 BotOptionMenu 30s 菜单窗口 + 余量，菜单有效期内选项可被消费）。
    private static final long PRIMARY_EXPIRATION_MS = 10_000;
    private static final long SECONDARY_EXPIRATION_MS = 30_000;
    private static final long TERTIARY_EXPIRATION_MS = 45_000;

    private static final QueueCleaner cleaner = new QueueCleaner(MessageQueue.getInstance());
    private final MessageQueue messageQueue;
    private final ScheduledFuture<?> scheduledTask;

    public QueueCleaner(MessageQueue messageQueue) {
        log.info("QueueCleaner object created");
        this.messageQueue = messageQueue;
        BotExecutors.ensureStarted();
        this.scheduledTask = TimerManager.getInstance().register(this, 2000);
    }

    // Static method to access the singleton instance
    public static QueueCleaner getInstance() {
        return cleaner;
    }

    @Override
    public void run() {
        long currentTime = System.currentTimeMillis();
        cleanQueue("primary", currentTime, PRIMARY_EXPIRATION_MS);
        cleanQueue("secondary", currentTime, SECONDARY_EXPIRATION_MS);
        cleanQueue("tertiary", currentTime, TERTIARY_EXPIRATION_MS);
    }

    private void cleanQueue(String queueName, long currentTime, long expirationTime) {
        Collection<ChatMessage> messages = messageQueue.getQueue(queueName);
        messages.removeIf(message -> currentTime - message.getTimestamp() > expirationTime);
    }

    // Method to stop the scheduler gracefully
    public void stop() {
        if (scheduledTask != null) {
            TimerManager.getInstance().stop(scheduledTask);
        }
    }
}
