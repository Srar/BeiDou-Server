package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.BotClient;
import org.gms.client.Client;

/**
 * 共享无头 botClient 的唯一来源（对应 SoloMapling 的 BotClientHandler）：
 * 幂等构造一个真正的合成 headless Client，所有 bot 复用它做路由与包发放。
 */
@Slf4j
public final class BotClientHolder {

    private static volatile Client botClient;

    private BotClientHolder() {
    }

    public static synchronized Client getBotClient(int world, int channel) {
        if (botClient == null) {
            botClient = new BotClient(world, channel);
        } else if (botClient.getWorld() != world || botClient.getChannel() != channel) {
            // 保持单例语义：忽略新参数，仅记录可观测性告警。
            log.warn("shared bot client cached for world/channel {}/{}, requested {}/{} — 忽略新参数",
                    botClient.getWorld(), botClient.getChannel(), world, channel);
        }
        return botClient;
    }

    /**
     * 关停复位：置空共享单例，允许 in-place 重启后按新的 world/channel 重建。
     * 由 BotExecutors.resetForShutdown() 在停机路径调用。
     */
    public static void reset() {
        botClient = null;
    }
}
