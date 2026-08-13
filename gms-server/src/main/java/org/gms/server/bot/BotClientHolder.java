package org.gms.server.bot;

import org.gms.client.BotClient;
import org.gms.client.Client;

/**
 * 共享无头 botClient 的唯一来源（对应 SoloMapling 的 BotClientHandler）：
 * 幂等构造一个真正的合成 headless Client，所有 bot 复用它做路由与包发放。
 */
public final class BotClientHolder {

    private static volatile Client botClient;

    private BotClientHolder() {
    }

    public static synchronized Client getBotClient(int world, int channel) {
        if (botClient == null) {
            botClient = new BotClient(world, channel);
        }
        return botClient;
    }
}
