package org.gms.server.bot.messaging;

import lombok.extern.slf4j.Slf4j;
import org.gms.server.TimerManager;
import org.gms.server.bot.BotExecutors;

import java.util.Collection;
import java.util.concurrent.ScheduledFuture;

@Slf4j
public class QueueCleaner implements Runnable {

    private static final QueueCleaner cleaner = new QueueCleaner(MessageQueue.getInstance(), 10000);
    private final MessageQueue messageQueue;
    private final long expirationTime;
    private final ScheduledFuture<?> scheduledTask;

    public QueueCleaner(MessageQueue messageQueue, long expirationTime) {
        log.info("QueueCleaner object created");
        this.messageQueue = messageQueue;
        this.expirationTime = expirationTime;
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
        cleanQueue("primary", currentTime);
        cleanQueue("secondary", currentTime);
        cleanQueue("tertiary", currentTime);
    }

    private void cleanQueue(String queueName, long currentTime) {
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
