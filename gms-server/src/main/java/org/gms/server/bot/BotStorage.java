package org.gms.server.bot;

import org.gms.client.Character;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全局 bot 注册表（对应 SoloMapling 的 CharacterStorage）：
 * botId → BotSM。由生成线程、tick 线程与 GM 命令线程并发读写，必须线程安全。
 * <p>
 * 另维护 respondant（应答者）/ inquirer（询问者）两个副表，语义 1:1 对齐
 * SoloMapling CharacterStorage 的 currentRespondants / inquirer。
 */
public final class BotStorage {

    private static final Map<Integer, BotSM> ACTIVE_BOTS = new ConcurrentHashMap<>();

    /** 应答者副表：由 dispatcher 池 / bot 生命周期并发读写（CopyOnWrite 保证遍历安全）。 */
    private static final List<Character> CURRENT_RESPONDANTS = new CopyOnWriteArrayList<>();

    /** 询问者副表：BotOptionMenu / 对话系统按 owner 匹配使用。 */
    private static final List<Character> CURRENT_INQUIRERS = new CopyOnWriteArrayList<>();

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

    // ── respondant 副表（1:1 对齐 SoloMapling CharacterStorage） ─────────────

    public static void addPlayer(Character player) {
        CURRENT_RESPONDANTS.add(player);
    }

    public static void removePlayer(Character player) {
        CURRENT_RESPONDANTS.remove(player);
    }

    public static boolean checkIfRespondant(Character player) {
        return CURRENT_RESPONDANTS.contains(player);
    }

    public static List<Character> getCurrentRespondants() {
        return CURRENT_RESPONDANTS;
    }

    // ── inquirer 副表 ───────────────────────────────────────────────────────

    public static void addInquirer(Character player) {
        CURRENT_INQUIRERS.add(player);
    }

    public static void removeInquirer(Character player) {
        CURRENT_INQUIRERS.remove(player);
    }

    public static boolean checkIfInquirer(Character player) {
        return CURRENT_INQUIRERS.contains(player);
    }
}
