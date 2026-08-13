package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.util.I18nUtil;
import org.gms.util.Randomizer;

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
}
