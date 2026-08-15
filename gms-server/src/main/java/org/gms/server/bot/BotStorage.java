package org.gms.server.bot;

import org.gms.client.Character;

import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * mapId 索引（F9 gms 增强）：mapId → 该图上的 bot id 集合。
     * <p>
     * SoloMapling 源的 {@code BotMapEntryResponder} 直接遍历 {@code map.getAllPlayers()}
     * 做进图 nudge（O(图上角色数)）；gms 移植初期简化为全量遍历本注册表 + 三重过滤
     * （O(全部活跃 bot)），2500+ bot 时真人每次进图都线性扫全表。本索引把 nudge 代价
     * 降回 O(该图 bot 数)，world/channel 校验保留在调用方防跨频道同图号误伤。
     * <p>
     * 与 ACTIVE_BOTS 并发维护；索引允许残留（销毁与 tick 刷新竞态窗口），消费方
     * 经 {@link #getBotById} 判空兜底，残留无害。
     */
    private static final Map<Integer, Set<Integer>> BOT_IDS_BY_MAP = new ConcurrentHashMap<>();

    /** botId → 已索引的 mapId（与 BOT_IDS_BY_MAP 配对维护，供 remove/refresh 反查旧图）。 */
    private static final Map<Integer, Integer> BOT_MAP_LOCATIONS = new ConcurrentHashMap<>();

    /** 应答者副表：由 dispatcher 池 / bot 生命周期并发读写（CopyOnWrite 保证遍历安全）。 */
    private static final List<Character> CURRENT_RESPONDANTS = new CopyOnWriteArrayList<>();

    /** 询问者副表：BotOptionMenu / 对话系统按 owner 匹配使用。 */
    private static final List<Character> CURRENT_INQUIRERS = new CopyOnWriteArrayList<>();

    private BotStorage() {
    }

    public static void addActiveBot(int id, BotSM bot) {
        ACTIVE_BOTS.put(id, bot);
        // F9 gms 增强：注册时即入 mapId 索引。getChr() 判空容忍 mock BotSM（测试用）。
        Character chr = bot.getChr();
        if (chr != null) {
            indexBot(id, chr.getMapId());
        }
    }

    public static void removeActiveBot(int id) {
        ACTIVE_BOTS.remove(id);
        unindexBot(id);
    }

    /** 把 botId 记入 mapId 索引（botId → 旧图反查同步更新）。 */
    private static void indexBot(int botId, int mapId) {
        BOT_MAP_LOCATIONS.put(botId, mapId);
        BOT_IDS_BY_MAP.computeIfAbsent(mapId, k -> ConcurrentHashMap.newKeySet()).add(botId);
    }

    /** 从索引摘除 botId（反查旧图）；空集合同时摘除防泄漏。 */
    private static void unindexBot(int botId) {
        Integer mapId = BOT_MAP_LOCATIONS.remove(botId);
        if (mapId == null) {
            return;
        }
        BOT_IDS_BY_MAP.computeIfPresent(mapId, (k, ids) -> {
            ids.remove(botId);
            return ids.isEmpty() ? null : ids;
        });
    }

    /**
     * 索引刷新（F9 gms 增强）：bot 换图后把 id 从旧图集合迁到新图集合。
     * 由 {@link BotSM} tick 轮在换图时调用（BotSM 侧有 lastIndexedMapId 短路，避免每拍空转）。
     * 并发安全：反查表 put 原子取旧值，集合操作走 CHM compute；残留由消费方判空兜底。
     */
    public static void refreshBotMapIndex(int botId, int newMapId) {
        // 审计修正（m5）：销毁与在途 tick 的刷新可能交错——已销毁 bot 的迟到刷新会把
        // 索引条目重新写回并永久残留。先查活跃注册表：不活跃则不写索引，从根上消除
        // 「removeActiveBot 之后 refresh 写回」的竞态残留（注册表判活为最终裁决）。
        if (!ACTIVE_BOTS.containsKey(botId)) {
            return;
        }
        Integer oldMapId = BOT_MAP_LOCATIONS.put(botId, newMapId);
        if (oldMapId != null && oldMapId != newMapId) {
            BOT_IDS_BY_MAP.computeIfPresent(oldMapId, (k, ids) -> {
                ids.remove(botId);
                return ids.isEmpty() ? null : ids;
            });
        }
        BOT_IDS_BY_MAP.computeIfAbsent(newMapId, k -> ConcurrentHashMap.newKeySet()).add(botId);
    }

    /** 该图上的 bot id 集合（索引视角；无则空集合，绝不返回 null）。 */
    public static Set<Integer> getBotIdsOnMap(int mapId) {
        return BOT_IDS_BY_MAP.getOrDefault(mapId, Set.of());
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
