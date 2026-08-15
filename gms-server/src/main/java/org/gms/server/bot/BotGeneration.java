package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.inventory.InventoryType;
import org.gms.client.SkinColor;
import org.gms.client.creator.MakeCharInfo;
import org.gms.client.creator.MakeCharInfoValidator;
import org.gms.server.bot.decorate.BotDecorate;
import org.gms.server.bot.party.BotRecruitManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.gms.util.Randomizer;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * 本次运行已分配（曾用）的 bot IGN 集合，小写存储（F8 gms 增强）。
     * <p>
     * SoloMapling 源直接 {@code getRandomCharacterIGN()} 随机取名、不查重；
     * gms 版为防 PlayerStorage 静默覆盖做了查重，但原实现每次查重都全量遍历
     * 活跃 bot 注册表 + 存储查重，生成 N 个 bot 总代价 O(N²)——5000 bot 生成期
     * CPU 尖峰来源之一。本集合把「已分配名字」判断降到 O(1)，遍历兜底仅在
     * 集合未命中时执行（正常路径被短路）。
     * <p>
     * 释放路径：createBot 失败回滚（rollbackRegistration）与 removeBotFromServer 销毁。
     * 若 bot 经其他路径被移除（直接调 BotStorage.removeActiveBot），名字条目会滞留；
     * 名字空间为 i18n 随机池的组合，空间足够大且 nameInUse 的注册表兜底仍会拦截
     * 真实冲突，少量滞留不影响唯一性，可接受。
     */
    private static final Set<String> ASSIGNED_BOT_NAMES = ConcurrentHashMap.newKeySet();

    private static volatile BotServerAccess serverAccess = DefaultBotServerAccess.INSTANCE;

    /** 懒加载的调试傀儡「Console」角色缓存；创建语义见 {@link #getConsoleBot()}。 */
    private static volatile Character consoleBot;

    /**
     * Console 傀儡的固定 id：普通 bot 从 BOT_BASE_ID + 100 起按自增计数分配，
     * 若 Console 落在低区段，生成第 900 个普通 bot 时必然撞车。放高区段
     * （BOT_BASE_ID + 100_000_000 = 2_100_000_000，仍小于 Integer.MAX_VALUE 无溢出），
     * 普通 bot 需生成 1 亿个才可能进入该区段，实际不可达。
     */
    private static final int CONSOLE_BOT_ID = BotHelpers.BOT_BASE_ID + 100_000_000;

    /** 基底角色工厂（测试注入点：替换为 mock 角色，避免触碰 GameConfig/WZ）。 */
    private static Supplier<Character> baseCharacterSupplier = BotGeneration::createBaseCharacter;

    /** 装饰函数（测试注入点：生产默认走 BotDecorate；测试可替换为 no-op 隔离静态副作用）。 */
    @FunctionalInterface
    interface BotDecorator {
        void decorate(Character bot, int baseClass, int minLevel, int maxLevel, int forcedJobId);
    }

    private static volatile BotDecorator decorator = BotGeneration::decorateBot;

    private BotGeneration() {
    }

    /** 测试注入接缝（传 null 恢复生产默认，与 BotStartupManager.setServerAccess 语义一致）。 */
    public static void setServerAccess(BotServerAccess access) {
        serverAccess = access == null ? DefaultBotServerAccess.INSTANCE : access;
    }

    /** 测试注入接缝（传 null 恢复生产默认）。 */
    public static void setBaseCharacterSupplier(Supplier<Character> supplier) {
        baseCharacterSupplier = supplier == null ? BotGeneration::createBaseCharacter : supplier;
    }

    /** 测试注入接缝（传 null 恢复生产默认）。 */
    static void setDecorator(BotDecorator decorator) {
        BotGeneration.decorator = decorator == null ? BotGeneration::decorateBot : decorator;
    }

    /** 生产装饰实现：对齐 SoloMapling BotGeneration.createBot 的装饰分支。 */
    private static void decorateBot(Character bot, int baseClass, int minLevel, int maxLevel, int forcedJobId) {
        if (baseClass <= 0) {
            BotDecorate.setBotVariables(bot);
        } else {
            BotDecorate.setBotVariables(bot, baseClass, minLevel, maxLevel, forcedJobId);
        }
    }

    /** 生产实现：内存构造默认角色（level 1 初心者）+ 随机合法外观，不复用数据库基底。 */
    private static Character createBaseCharacter() {
        int world = DefaultBotServerAccess.resolveBotWorld();
        int channel = DefaultBotServerAccess.resolveBotChannel();
        Character chr = Character.getDefault(BotClientHolder.getBotClient(world, channel));
        applyRandomAppearance(chr);
        return chr;
    }

    /**
     * 从 WZ 的 MakeCharInfo 合法池随机取外观。getDefault 的角色 face/hair 为 0，
     * 该 id 在客户端 WZ 中不存在——spawn 包里的无效外观 id 会直接崩掉 v83 客户端。
     */
    private static void applyRandomAppearance(Character chr) {
        boolean male = Randomizer.nextBoolean();
        MakeCharInfo pool = MakeCharInfoValidator.getAppearancePool(male);
        applyAppearance(chr, male, pool.getCharFaces(), pool.getCharHairs(), pool.getCharSkins());
    }

    /** 外观应用（拆出便于单测：注入显式池，测试无需触碰 WZ）。 */
    static void applyAppearance(Character chr, boolean male, Set<Integer> faces, Set<Integer> hairs, Set<Integer> skins) {
        chr.setGender(male ? 0 : 1);
        chr.setFace(randomPick(faces, 20000));
        chr.setHair(randomPick(hairs, 30000));
        chr.setSkinColor(SkinColor.getById(randomPick(skins, 0)));
    }

    static int randomPick(Set<Integer> pool, int fallback) {
        if (pool == null || pool.isEmpty()) {
            return fallback;
        }
        List<Integer> list = new ArrayList<>(pool);
        return list.get(Randomizer.nextInt(list.size()));
    }

    /** 本次运行已创建的 bot 总数（诊断/测试用）。 */
    public static int getBotsCreatedCount() {
        return currentBotCount.get() - 100;
    }

    /**
     * 懒加载调试傀儡角色「Console」（SoloMapling BotGeneration.getConsoleBot 移植）：
     * 首次调用创建 level 69 / job 420 的固定 bot 并缓存，之后每次返回同一实例；
     * 供 TestMethods.addMMC 把它拉进调试者 GM 的 messenger。
     * <p>
     * gms 增强：源实现用字面 id=999——那是 SoloMapling BOT_BASE_ID=20000 玩家空间内的
     * 固定值；gms 的真实玩家 id 由数据库自增分配（从 1 起），字面 999 会与真人撞车，
     * 且不在 bot 区段（&gt; {@link BotHelpers#BOT_BASE_ID}）、不被 {@link BotHelpers#isBot(int)}
     * 识别，故改用区段内固定 id {@link #CONSOLE_BOT_ID}（=2_100_000_000）。
     * 高区段隔离理由见 {@link #CONSOLE_BOT_ID}：普通 bot id 从 BOT_BASE_ID + 100
     * 起自增，低区段固定值会被第 900 个普通 bot 撞上，高区段则实际不可达。
     * <p>
     * 创建失败（异常）时 log.warn 并返回 null，绝不抛出：addMMC 是 GM 调试命令，
     * 不应因傀儡创建失败把异常甩回命令分发层。失败不写缓存（保持 null），下次调用自动重试。
     */
    public static Character getConsoleBot() {
        Character cached = consoleBot;
        if (cached != null) {
            return cached;
        }
        synchronized (BotGeneration.class) {
            if (consoleBot != null) {
                return consoleBot;
            }
            try {
                consoleBot = createConsoleBot();
            } catch (RuntimeException e) {
                // 字面量日志而非 I18nUtil 键：本移植未随附 i18n 资源，缺键会抛 NoSuchMessageException 掩盖根因。
                log.warn("Console bot (addMMC 调试傀儡) 创建失败，返回 null", e);
            }
            return consoleBot;
        }
    }

    /**
     * 停机复位：清空 Console 傀儡缓存。停机后旧角色对象已从 channel/world 摘除，
     * 若缓存滞留，in-place 重启（同 JVM）后 getConsoleBot 会返回指向已销毁世界的
     * 僵尸角色。下次调用时懒加载重建。
     */
    public static void resetConsoleBotForShutdown() {
        consoleBot = null;
    }

    /**
     * 仿 {@link #createBot} 骨架构造 Console 傀儡：基底角色 → 固定 id/IGN/Client →
     * 注册 channel/world → 落图 HENESYS。与 createBot 的差异：id 固定（不走自增计数）、
     * fame 固定 999（源实现 fame=botId=999，此处保留字面值作调试标记）、名字不参与
     * ASSIGNED_BOT_NAMES 查重（固定英文名与随机中文池无冲突）。
     */
    private static Character createConsoleBot() {
        Character bot = baseCharacterSupplier.get();
        int botId = CONSOLE_BOT_ID; // 固定 id：gms 增强（高区段隔离），理由见 getConsoleBot 注释
        int world = DefaultBotServerAccess.resolveBotWorld();
        int channel = DefaultBotServerAccess.resolveBotChannel();

        bot.setClient(BotClientHolder.getBotClient(world, channel));
        bot.setId(botId);
        bot.setName("Console");
        bot.setLevel(69);
        bot.setJob(Job.getById(420));
        bot.setFame(999); // 调试标记：对齐源的 fame=botId(999)
        bot.setWorld(world);
        // 标记已进入频道世界：awayFromWorld 默认 true，不置 false 会被
        // MapleMap.cleanupGhostPlayers 当「断线幽灵玩家」误杀（同 createBot）。
        bot.setEnteredChannelWorld();

        serverAccess.addBotToServer(bot); // channel.addPlayer + world 玩家存储

        // 落图尽力而为：失败仅告警，不影响返回角色（Console 只服务于 messenger 调试）。
        MapleMap henesys = serverAccess.getMap(world, channel, 100000000);
        if (henesys != null) {
            try {
                Portal spawn = henesys.getPortal("sp");
                placeBotOnMap(bot, spawn != null ? spawn.getPosition() : new Point(0, 0), henesys);
            } catch (RuntimeException e) {
                log.warn("Console bot 落图 HENESYS 失败（不影响返回角色）", e);
            }
        }
        return bot;
    }

    /** 无 class/等级约束的默认生成：委托 6 参版走完整随机装饰（tier/level/job/body/equip/NX）。 */
    public static int createBot(Point pos, MapleMap map) {
        return createBot(pos, map, 0, 0, 0, 0);
    }

    /** 指定 baseClass 与等级区间的生成：委托 6 参版（forcedJobId 置 0 = 随机职业）。 */
    public static int createBot(Point pos, MapleMap map, int baseClass, int minLevel, int maxLevel) {
        return createBot(pos, map, baseClass, minLevel, maxLevel, 0);
    }

    /**
     * 在指定地图/位置创建一个 bot 并注册进服务器（channel + world + 地图），
     * 返回新 bot 的角色 ID。方法本身快速返回（~毫秒级），出生编排跑异步。
     * 注册/落图任一步失败都会回滚已完成的注册，绝不残留「半注册幽灵 bot」。
     * <p>
     * 对齐 SoloMapling BotGeneration.createBot 语义：baseClass&lt;=0 走完整随机装饰
     * （{@link BotDecorate#setBotVariables(Character)}）；否则按 baseClass/等级区间/强制职业装饰
     * （{@link BotDecorate#setBotVariables(Character, int, int, int, int)}）。
     * forcedJobId &gt; 0 钉死精确职业（GM trainhere 测试 spawn）；0 = 随机该 class 对应职业。
     */
    public static int createBot(Point pos, MapleMap map, int baseClass, int minLevel, int maxLevel, int forcedJobId) {
        Character bot = baseCharacterSupplier.get();
        int botId = BotHelpers.BOT_BASE_ID + currentBotCount.getAndIncrement();
        int world = DefaultBotServerAccess.resolveBotWorld();
        int channel = DefaultBotServerAccess.resolveBotChannel();

        bot.setClient(BotClientHolder.getBotClient(world, channel));
        bot.setId(botId);
        String botName = randomUniqueBotName();
        bot.setName(botName);
        ASSIGNED_BOT_NAMES.add(botName.toLowerCase(Locale.ROOT)); // F8 gms 增强：生成期 O(1) 查重
        bot.setFame(botId); // 调试标记：fame 值 == botId
        bot.setWorld(world);
        // 标记已进入频道世界：awayFromWorld 默认 true，不置 false 的话
        // MapleMap.cleanupGhostPlayers 会把 bot 当「断线未移除的幽灵玩家」误杀
        bot.setEnteredChannelWorld();

        try {
            serverAccess.addBotToServer(bot);      // channel.addPlayer + world 玩家存储
            placeBotOnMap(bot, pos, map);
        } catch (RuntimeException e) {
            // 先把根因打进日志再回滚重抛——否则线上只会看到回滚警告而无从定位
            log.error(I18nUtil.getLogMessage("BotGeneration.bot.create.failed", botId), e);
            rollbackRegistration(bot, botName);
            throw e;
        }

        // 装饰在落图之后、出生编排之前执行（对齐源实现：bot 到场时已穿好装备）。
        decorator.decorate(bot, baseClass, minLevel, maxLevel, forcedJobId);

        Character finalBot = bot;
        BotExecutors.runAsync(() -> playSpawnChoreography(finalBot));
        // 生成摘要（含装备状态）：用户无需 GM 命令即可在日志确认"这个 bot 有装备/有职业"
        int level = bot.getLevel();
        int jobId = bot.getJob() != null ? bot.getJob().getId() : 0;
        boolean hasWeapon = bot.getInventory(InventoryType.EQUIPPED) != null
                && bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11) != null;
        log.info(I18nUtil.getLogMessage("BotGeneration.bot.created", botId, bot.getName(),
                level, jobId, hasWeapon));
        return botId;
    }

    /** createBot 失败补偿：清理已完成的注册（地图 → channel/world 存储）+ 释放已分配名字。 */
    private static void rollbackRegistration(Character bot, String assignedName) {
        // F8 gms 增强：回滚时释放本次分配的名字（名字在注册前已入集合，失败不得滞留）。
        // 用传入的 assignedName 而非 bot.getName()：mock/半初始化角色可能读不回名字。
        if (assignedName != null) {
            ASSIGNED_BOT_NAMES.remove(assignedName.toLowerCase(Locale.ROOT));
        }
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

    /**
     * 把 bot 放到地图与位置（先摘除旧图登记，无论同图异图，避免重复登记）。
     * <p>
     * 落地三件套（修复「卡在空中不动 / 浮空姿势」）：
     * <ol>
     *   <li>出生坐标修正到脚下地面（portal/传入点可能悬空；下方无 foothold 则保留原坐标）；</li>
     *   <li>stance 用站立帧 4/5（4=面向右站立、5=面向左站立，随机朝向；0 不是站立帧、
     *       5 亦非怪物出生姿态问题——客户端对 0 会渲染成悬空姿势）；</li>
     *   <li>落图后补发一次 MOVE_PLAYER 站立包——SPAWN_PLAYER 的进图帧写死
     *       y-42 + stance=6，客户端只对「有后续移动包」的角色完成落地，
     *       真实玩家靠客户端自发移动包，bot 没有客户端，必须服务端补发，
     *       否则先到玩家眼中 bot 永远定格在半空下落帧。</li>
     * </ol>
     */
    private static void placeBotOnMap(Character bot, Point pos, MapleMap map) {
        MapleMap current = bot.getMap();
        if (current != null) {
            current.removePlayer(bot);
        }
        bot.setMap(map);
        Point ground = map.getPointBelow(pos);
        bot.setPosition(ground != null ? ground : pos);
        int stance = Randomizer.nextBoolean() ? 4 : 5;
        bot.setStance(stance);
        map.addPlayer(bot);
        bot.broadcastStance(stance);
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
        // F8 gms 增强：销毁即释放名字（判空容忍 mock/未命名角色）。
        String botName = bot.getName();
        if (botName != null) {
            ASSIGNED_BOT_NAMES.remove(botName.toLowerCase(Locale.ROOT));
        }
        log.info(I18nUtil.getLogMessage("BotGeneration.bot.removed", bot.getId()));
        // 销毁即清理招募残留（ARMED 武装窗口 / PENDING 转换交接），防止泄漏拒掉后续合法邀请。
        BotRecruitManager.clearHandoffs(bot.getId());
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
        // F8 gms 增强：已分配名字集合 O(1) 短路。生成 N 个 bot 的查重总代价由此从
        // O(N²)（每次遍历全部活跃 bot + 存储查重）降为 O(N)；源实现（SoloMapling）
        // 不查重，本查重即 gms 增强，此处集合为 gms 侧进一步优化。
        if (ASSIGNED_BOT_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            return true;
        }
        // 兜底：注册表遍历 + 存储查重，覆盖集合遗漏窗口（外部直接注册的 bot、
        // 真实玩家同名、集合滞留条目的最终裁决）。正常路径已被上方集合短路，
        // 此分支仅偶发执行，成本可忽略。
        for (BotSM bot : BotStorage.getAllBots().values()) {
            Character chr = bot.getChr();
            if (chr != null && name.equalsIgnoreCase(chr.getName())) {
                return true;
            }
        }
        return serverAccess.isNameTaken(name);
    }
}
