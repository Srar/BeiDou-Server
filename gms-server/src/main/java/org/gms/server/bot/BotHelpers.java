package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import org.gms.util.I18nUtil;
import org.gms.util.Randomizer;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bot 通用判定与工具。
 */
public final class BotHelpers {

    /**
     * bot 角色 ID 起点：远离 characters 表自增区间（自增从 1 起），
     * 保证真实玩家 ID 与 bot ID 空间不发生碰撞（与 SoloMapling 的 20000 约定同构，但拉高区段）。
     */
    public static final int BOT_BASE_ID = 2_000_000_000;

    /** 批量出生点之间的最小水平间距（与 SoloMapling BotSpotPicker.MIN_SPACING 对齐）。 */
    private static final int MIN_SPACING = 30;

    private static final String NAME_POOL_KEY = "bot.name.pool";

    private BotHelpers() {
    }

    /**
     * 区段初筛（快速路径，供高频发布点使用）：id 是否落在 bot 区段。
     * 真实玩家 ID 由数据库自增分配，不可能到达该区段；bot 分配必然在该区段内。
     */
    public static boolean isBotId(int characterId) {
        return characterId > BOT_BASE_ID;
    }

    /**
     * 是否为 bot：区段初筛（id 落在 bot 区段）<b>且</b>注册表精确命中。
     * 双判据：即使真实玩家 ID 意外越过区段下界，只要未注册进 BotStorage
     * 就不会被误判为 bot（反之 bot 必然已注册）。
     */
    public static boolean isBot(int characterId) {
        return characterId > BOT_BASE_ID && BotStorage.botLoggedIn(characterId);
    }

    /** 是否为 bot 角色。 */
    public static boolean isBot(Character chr) {
        return chr != null && isBot(chr.getId());
    }

    /**
     * 从 i18n 名字池随机取一个 bot 名字（逗号分隔池，过滤空白项；池缺失/全空时
     * 回退默认名）。查重由调用方（BotGeneration）负责。
     */
    public static String randomBotName() {
        String pool = I18nUtil.getMessage(NAME_POOL_KEY);
        List<String> names = parsePool(pool);
        if (names.isEmpty()) {
            return "Bot" + Randomizer.nextInt(100000);
        }
        return names.get(Randomizer.nextInt(names.size()));
    }

    private static List<String> parsePool(String pool) {
        if (pool == null || pool.isBlank()) {
            return List.of();
        }
        return Arrays.stream(pool.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 批量出生点选择（参考 SoloMapling 的 BotSpotPicker.pickGroundSpots 语义，
     * 无导航图版）：以 anchor（出生 portal）为中心沿 X 均匀分桶，桶内随机抖动，
     * 每个候选点经 {@link MapleMap#getPointBelow} 修正到脚下地面，并保证点间
     * 最小水平间距——避免批量生成的 bot 全部挤在同一个 portal 出生点上。
     * 某桶拿不到合法地面点时回退 anchor 地面点（尽力而为）。
     */
    public static List<Point> pickGroundSpots(MapleMap map, Point anchor, int count) {
        List<Point> out = new ArrayList<>();
        if (map == null || anchor == null || count <= 0) {
            return out;
        }
        int spread = Math.max(400, count * 50);
        int lo = anchor.x - spread / 2;
        int hi = anchor.x + spread / 2;
        for (int i = 0; i < count; i++) {
            int bucketLo = lo + (hi - lo) * i / count;
            int bucketHi = lo + (hi - lo) * (i + 1) / count;
            out.add(pickInBucket(map, anchor, bucketLo, bucketHi, out));
        }
        return out;
    }

    private static Point pickInBucket(MapleMap map, Point anchor, int bucketLo, int bucketHi, List<Point> taken) {
        // 桶中心 + 受限抖动：相邻桶中心的距离 ≥ 桶宽，抖动半径收窄后理论上保证 MIN_SPACING
        int center = (bucketLo + bucketHi) / 2;
        int radius = Math.max(0, (bucketHi - bucketLo) / 2 - MIN_SPACING / 2);
        for (int attempt = 0; attempt < 12; attempt++) {
            int x = center + (radius > 0 ? Randomizer.nextInt(2 * radius + 1) - radius : 0);
            Point ground = map.getPointBelow(new Point(x, anchor.y));
            if (ground == null) {
                continue;
            }
            if (overlaps(ground, taken)) {
                continue;
            }
            return ground;
        }
        Point fallback = map.getPointBelow(anchor);
        return fallback != null ? fallback : new Point(anchor);
    }

    private static boolean overlaps(Point p, List<Point> taken) {
        for (Point q : taken) {
            if (Math.abs(p.x - q.x) < MIN_SPACING && Math.abs(p.y - q.y) < MIN_SPACING) {
                return true;
            }
        }
        return false;
    }
}
