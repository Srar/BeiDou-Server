package org.gms.server.bot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局 bot 注册表（对应 SoloMapling 的 CharacterStorage）：
 * botId → BotSM。由生成线程、tick 线程与 GM 命令线程并发读写，必须线程安全。
 */
public final class BotStorage {

    private static final Map<Integer, BotSM> ACTIVE_BOTS = new ConcurrentHashMap<>();

    private BotStorage() {
    }

    public static void addActiveBot(int id, BotSM bot) {
        ACTIVE_BOTS.put(id, bot);
    }

    public static void removeActiveBot(int id) {
        ACTIVE_BOTS.remove(id);
    }

    /** bot 是否「在线」：注册表中存在即视为已登录（tick 骨架的存活判据）。 */
    public static boolean botLoggedIn(int id) {
        return ACTIVE_BOTS.containsKey(id);
    }

    public static BotSM getBotById(int id) {
        return ACTIVE_BOTS.get(id);
    }

    public static Map<Integer, BotSM> getAllBots() {
        return ACTIVE_BOTS;
    }

    public static int getActiveBotCount() {
        return ACTIVE_BOTS.size();
    }
}
