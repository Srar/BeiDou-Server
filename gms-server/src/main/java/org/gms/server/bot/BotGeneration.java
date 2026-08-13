package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.gms.util.Randomizer;

import java.awt.Point;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Bot 创建/销毁生命周期（参考 SoloMapling 的 BotGeneration 移植）：
 * 基底角色 → 改 ID/IGN/Client → 注册 channel/world → 落图 → 异步出生编排。
 * <p>
 * 与参考实现的差异：BeiDou 版用 {@link Character#getDefault} 内存构造基底角色
 * （不克隆数据库 CID2、bot 不入库）；出生编排无录制回放系统，仅随机延迟后
 * 播放一个表情作为到场信号。
 */
@Slf4j
public final class BotGeneration {

    /**
     * 出生编排的最坏时长上限。任何必须等 bot 到场完毕的动作（FSM 首 tick 等）
     * 至少应延迟这么久。
     * <p>
     * 当前简化编排（延迟 500-1200ms 后一个表情）远小于该值；保留 2s 作缓冲与
     * 首 tick 错峰，未来若恢复完整到场编排（传送门落下+转身）再调大。
     */
    public static final long SPAWN_CHOREOGRAPHY_MAX_MS = 2000;

    /**
     * 原子计数：bot 并行生成时防止两个线程拿到同一 ID 静默互相覆盖。
     * 初值 100 → 首个 bot = BOT_BASE_ID + 100。
     */
    private static final AtomicInteger currentBotCount = new AtomicInteger(100);

    private static volatile BotServerAccess serverAccess = DefaultBotServerAccess.INSTANCE;

    /** 基底角色工厂（测试注入点：替换为 mock 角色，避免触碰 GameConfig/WZ）。 */
    private static Supplier<Character> baseCharacterSupplier = BotGeneration::createBaseCharacter;

    private BotGeneration() {
    }

    /** 测试注入接缝（传 null 恢复生产默认，与 BotStartupManager.setServerAccess 语义一致）。 */
    static void setServerAccess(BotServerAccess access) {
        serverAccess = access == null ? DefaultBotServerAccess.INSTANCE : access;
    }

    /** 测试注入接缝（传 null 恢复生产默认）。 */
    static void setBaseCharacterSupplier(Supplier<Character> supplier) {
        baseCharacterSupplier = supplier == null ? BotGeneration::createBaseCharacter : supplier;
    }

    /** 生产实现：内存构造默认角色（level 1 初心者），不复用数据库基底。 */
    private static Character createBaseCharacter() {
        int world = DefaultBotServerAccess.resolveBotWorld();
        int channel = DefaultBotServerAccess.resolveBotChannel();
        return Character.getDefault(BotClientHolder.getBotClient(world, channel));
    }

    /** 本次运行已创建的 bot 总数（诊断/测试用）。 */
    public static int getBotsCreatedCount() {
        return currentBotCount.get() - 100;
    }

    /**
     * 在指定地图/位置创建一个 bot 并注册进服务器（channel + world + 地图），
     * 返回新 bot 的角色 ID。方法本身快速返回（~毫秒级），出生编排跑异步。
     * 注册/落图任一步失败都会回滚已完成的注册，绝不残留「半注册幽灵 bot」。
     */
    public static int createBot(Point pos, MapleMap map) {
        Character bot = baseCharacterSupplier.get();
        int botId = BotHelpers.BOT_BASE_ID + currentBotCount.getAndIncrement();
        int world = DefaultBotServerAccess.resolveBotWorld();
        int channel = DefaultBotServerAccess.resolveBotChannel();

        bot.setClient(BotClientHolder.getBotClient(world, channel));
        bot.setId(botId);
        bot.setName(randomUniqueBotName());
        bot.setFame(botId); // 调试标记：fame 值 == botId
        bot.setWorld(world);
        bot.setLevel(Randomizer.rand(10, 40)); // 等级多样性（纯装饰；属性仍是初心者默认值）

        try {
            serverAccess.addBotToServer(bot);      // channel.addPlayer + world 玩家存储
            placeBotOnMap(bot, pos, map);
        } catch (RuntimeException e) {
            rollbackRegistration(bot);
            throw e;
        }

        Character finalBot = bot;
        BotExecutors.runAsync(() -> playSpawnChoreography(finalBot));
        log.info(I18nUtil.getLogMessage("BotGeneration.bot.created", botId, bot.getName()));
        return botId;
    }

    /** createBot 失败补偿：清理已完成的注册（地图 → channel/world 存储）。 */
    private static void rollbackRegistration(Character bot) {
        try {
            MapleMap current = bot.getMap();
            if (current != null) {
                current.removePlayer(bot);
                bot.setMap(null);
            }
        } catch (RuntimeException ignored) {
            // 回滚尽力而为
        }
        try {
            serverAccess.removeBotFromServer(bot);
        } catch (RuntimeException ignored) {
            // 回滚尽力而为
        }
        log.warn(I18nUtil.getLogMessage("BotGeneration.bot.create.rollback", bot.getId()));
    }

    /** 把 bot 放到地图与位置（先摘除旧图登记，无论同图异图，避免重复登记）。 */
    private static void placeBotOnMap(Character bot, Point pos, MapleMap map) {
        MapleMap current = bot.getMap();
        if (current != null) {
            current.removePlayer(bot);
        }
        bot.setMap(map);
        bot.setPosition(pos);
        bot.setStance(5);
        map.addPlayer(bot);
    }

    /**
     * 出生编排（简化版）：500-1200ms 后播放一个随机表情作为到场信号。
     * 异步执行，调用线程不被拖住；动作前按注册表判活（销毁后 map 字段
     * 已置 null 且移出注册表，双保险防对已销毁 bot 播放无主表情）。
     */
    private static void playSpawnChoreography(Character bot) {
        long delayMs = ThreadLocalRandom.current().nextLong(500, 1201);
        BotExecutors.schedule(() -> {
            if (!BotStorage.botLoggedIn(bot.getId()) || bot.getMap() == null) {
                return;
            }
            bot.getMap().broadcastMessage(PacketCreator.facialExpression(bot, Randomizer.rand(1, 7)));
        }, delayMs);
    }

    /**
     * 销毁 bot：地图 → channel/world 存储 → 注册表（与参考实现一致）的顺序清理，
     * 先停 FSM 与 tick 轮（在途 tick 允许跑完）。
     */
    public static void removeBotFromServer(Character bot) {
        BotSM sm = BotStorage.getBotById(bot.getId());
        if (sm != null) {
            sm.setRunning(false);
            sm.stopScheduledTask();
        }
        MapleMap map = bot.getMap();
        if (map != null) {
            map.removePlayer(bot);
            bot.setMap(null); // 显式摘除地图引用：map.removePlayer 不会清 Character.map，
                              // 残留会让出生编排的判活与 matchesFilter 继续按旧图工作
        }
        serverAccess.removeBotFromServer(bot);
        BotStorage.removeActiveBot(bot.getId());
        log.info(I18nUtil.getLogMessage("BotGeneration.bot.removed", bot.getId()));
    }

    /**
     * 随机且不重名的 bot IGN：名字池（i18n）随机取，与全服在线存储及在册 bot 查重；
     * 查重始终冲突时追加数字后缀兜底——绝不产出重名（重名会在 PlayerStorage 里
     * 静默互相覆盖，甚至顶掉同名真实玩家）。
     */
    private static String randomUniqueBotName() {
        String base;
        int attempts = 0;
        do {
            base = BotHelpers.randomBotName();
            attempts++;
        } while (nameInUse(base) && attempts < 20);
        if (!nameInUse(base)) {
            return base;
        }
        String suffixed;
        int suffix = 1;
        do {
            suffixed = base + suffix;
            suffix++;
        } while (nameInUse(suffixed) && suffix <= 1000);
        return suffixed;
    }

    private static boolean nameInUse(String name) {
        for (BotSM bot : BotStorage.getAllBots().values()) {
            if (name.equalsIgnoreCase(bot.getChr().getName())) {
                return true;
            }
        }
        return serverAccess.isNameTaken(name);
    }
}
