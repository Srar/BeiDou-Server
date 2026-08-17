package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.server.ItemInformationProvider;
import org.gms.server.bot.gcmove.LodCounts;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.util.I18nUtil;
import org.gms.util.Randomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Bot 通用判定与工具。
 */
public final class BotHelpers {

    private static final Logger log = LoggerFactory.getLogger(BotHelpers.class);

    /**
     * bot 角色 ID 起点：远离 characters 表自增区间（自增从 1 起），
     * 保证真实玩家 ID 与 bot ID 空间不发生碰撞（与 SoloMapling 的 20000 约定同构，但拉高区段）。
     */
    public static final int BOT_BASE_ID = 2_000_000_000;

    /** 批量出生点之间的最小水平间距（与 SoloMapling BotSpotPicker.MIN_SPACING 对齐）。 */
    private static final int MIN_SPACING = 30;

    private static final String NAME_POOL_KEY = "bot.name.pool";

    /** 中国游戏风名字池资源（每行一个名字，生成器见 scripts/genBotNames.py 同源说明）。 */
    private static final String NAME_POOL_RESOURCE = "/org/gms/server/bot/namepool/bot_names.txt";

    /**
     * 名字编码字符集：服务端 addCharStats（PacketCreator.java:173-175）写 13 字节定长 GBK
     * 字段（writeFixedString(rightPadded(name,13))），无 short 前缀，
     * 因此名字长度安全必须按 GBK 字节数判定，而不是字符数。
     */
    private static final Charset GBK = Charset.forName("GBK");

    /**
     * GBK 严格可编码判定器：getBytes(GBK) 会把不可映射字符（emoji/韩文等）静默替换为
     * 0x3F '?' 放行，必须先经 {@link #GBK_ENCODER} 的 canEncode 严格校验。
     * CharsetEncoder 本身非线程安全，但 canEncode 是只读操作（不改变 encoder 内部状态），
     * 并发调用安全；且 isNameSafe 仅由 synchronized 的 randomBotName 加载路径与测试调用。
     */
    private static final CharsetEncoder GBK_ENCODER = GBK.newEncoder();

    /**
     * C 字符串危险尾字节：GBK 字节流中出现这些字节会干扰客户端对名字的 C 字符串解析
     * （0x5C '\' 转义、0x7C '|'、0x7B '{'、0x7D '}'、0x5B '['、0x5D ']'、0x40 '@'）。
     * 中文的 GBK 第二字节范围为 0x40-0xFE（除 0x7F），因此部分汉字（如「聖」「驚」）
     * 的编码第二字节恰好是这些值——审计发现的增量风险，需在字节流层面过滤。
     */
    static final byte[] UNSAFE_TAIL_BYTES = {0x5C, 0x7C, 0x7B, 0x7D, 0x5B, 0x5D, 0x40};

    /**
     * 无放回名字轮盘（对齐 SoloMapling FMShopDescGen.getRandomIGN 语义）：
     * 池加载后洗牌，按顺序逐个发放，发完整个池才重新洗牌——同一轮内绝不重名，
     * 避免旧实现「20 个小池有放回随机 + 数字后缀避重」造成的满屏豆豆2/云朵11。
     */
    private static final List<String> NAME_POOL = new ArrayList<>();
    private static int namePoolIndex = 0;
    private static String lastIssuedName = null;

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
     * 图上是否有真实玩家在观察（bot 广播门控用）。
     * <p>
     * 观察轮运行时走 LodCounts O(1) 查询（FULL tier = 有真人）；未运行时回退
     * 线性扫描（先拷贝快照，防与进出图并发抛 ConcurrentModificationException）。
     * 供换装/buff 等纯视觉广播使用：无人观察的图跳过广播，显著降低客户端
     * 接收包量与坏数据触达概率（2026-08-17 客户端崩溃事故的预防性降载）。
     */
    public static boolean hasRealPlayerObserver(MapleMap map) {
        if (map == null) {
            return false;
        }
        if (LodCounts.trackerRunning()) {
            return LodCounts.isMapFull(map.getId());
        }
        for (Character chr : new ArrayList<>(map.getCharacters())) {
            if (!isBot(chr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 发一个 bot 名字：优先走大池无放回轮盘（洗牌顺序发放，不重复），
     * 资源缺失时回退 i18n 小池 + 随机后缀。synchronized：波内并行 spawn 并发取名。
     * 查重由调用方（BotGeneration）负责（兜底真人同名/异常重名）。
     */
    public static synchronized String randomBotName() {
        if (NAME_POOL.isEmpty()) {
            loadNamePool();
        }
        if (!NAME_POOL.isEmpty()) {
            if (namePoolIndex >= NAME_POOL.size()) {
                Collections.shuffle(NAME_POOL);
                namePoolIndex = 0;
                // 跨周期防重：重洗后队首若与刚发过的名字相同则跳过，保证任意连续两次发放不重名
                if (NAME_POOL.get(0).equals(lastIssuedName) && NAME_POOL.size() > 1) {
                    namePoolIndex = 1;
                }
            }
            String name = NAME_POOL.get(namePoolIndex++);
            lastIssuedName = name;
            return name;
        }
        return fallbackName();
    }

    /** 一次性加载并洗牌名字池（每行一个；按编码安全过滤空白、超长与含危险尾字节的名字）。 */
    private static void loadNamePool() {
        try (InputStream in = BotHelpers.class.getResourceAsStream(NAME_POOL_RESOURCE)) {
            if (in == null) {
                return;
            }
            int totalLines = 0;
            int filtered = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    totalLines++;
                    String name = line.trim();
                    if (isNameSafe(name)) {
                        NAME_POOL.add(name);
                    } else {
                        filtered++;
                    }
                }
            }
            log.info("Bot 名字池加载完成: 总行数 {}, 过滤 {} 个, 剩余 {} 个", totalLines, filtered, NAME_POOL.size());
            Collections.shuffle(NAME_POOL);
        } catch (IOException e) {
            // 资源缺失/损坏：保留空池，randomBotName 走 i18n 回退
        }
    }

    /**
     * 名字是否通过全部编码安全过滤（空白、字符数、GBK 可编码性、GBK 字节数、
     * GB2312 客户端字库范围、危险尾字节）。
     * <p>
     * 审计依据：服务端 PacketCreator.addCharStats 用 {@code writeFixedString(rightPadded(name, 13))}
     * 写 13 字节定长 GBK 字段。全角字符 GBK 占 2 字节，若名字
     * GBK 字节数超过 12，经右填充后仍会溢出 13 字节字段——客户端对名字字段未设防
     * 阈值，存在栈溢出风险。因此过滤条件为「字符数 ≤12 且 GBK 字节数 ≤12」双条件。
     * <p>
     * GB2312 字库过滤（客户端崩溃防护，事故实锤）：客户端中文版字库基于 GB2312
     * （含 01-09 区符号），GBK 扩展区字符（繁体/生僻字/日文符号，如「菂」0xC785、
     * 「頹」0xEE6A、「ゞ」0xA967）在客户端无字形映射，渲染角色名时查表越界崩溃
     * （客户端崩溃报告 error code 5 拒绝访问）。2026-08-17 事故中，崩溃前最后一条
     * 服务端发包为 SPAWN_PLAYER，bot 名「頹廢菂愛」全部落在 GBK 扩展区。
     * <p>
     * 另在字节流层面过滤 {@link #UNSAFE_TAIL_BYTES} 危险尾字节（如 0x5C '\'），
     * 防止客户端对名字做 C 字符串解析时被干扰。
     */
    private static boolean isNameSafe(String name) {
        if (name == null || name.isBlank() || name.length() > 12) {
            return false;
        }
        // 严格 GBK 可编码校验必须先于 getBytes：不可映射字符会被静默替换为 0x3F '?'，
        // 否则 emoji/韩文等名字会绕过长度与尾字节过滤（审计 A3-Low1）。
        if (!GBK_ENCODER.canEncode(name)) {
            return false;
        }
        byte[] gbk = name.getBytes(GBK);
        if (gbk.length > 12) {
            return false;
        }
        if (!isGb2312Safe(gbk)) {
            return false;
        }
        for (byte b : gbk) {
            for (byte unsafe : UNSAFE_TAIL_BYTES) {
                if (b == unsafe) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 客户端字库安全判定（公共工具）：字符串全部字符可被客户端 GB2312 字库渲染。
     * 规则：ASCII 可视字符（0x20-0x7E）放行；GBK 双字节对必须落在 GB2312 编码空间
     * （高位 0xA1-0xF7 且低位 0xA1-0xFE，含 01-09 区符号与一二级汉字）。
     * 落在 GBK 扩展区（高位 0x81-0xA0 或低位 0x40-0xA0）的繁体/生僻字/日文符号
     * 一律拦截——客户端无字形映射，渲染即崩（2026-08-17 事故：bot 名「頹廢菂愛」
     * 的 SPAWN_PLAYER 包触发客户端崩溃，error code 5 拒绝访问）。
     * 不可 GBK 编码（emoji/韩文等）同样判不安全；本方法供名字/店名/公会名/招牌
     * 等所有「发往客户端的字符串」统一过滤。
     */
    public static boolean isClientFontSafe(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (!GBK_ENCODER.canEncode(text)) {
            return false;
        }
        byte[] gbk = text.getBytes(GBK);
        for (int i = 0; i < gbk.length; i++) {
            int first = gbk[i] & 0xFF;
            if (first < 0x80) {
                if (first < 0x20) {
                    return false; // 控制字符不进入客户端可见文本
                }
                continue;
            }
            if (i + 1 >= gbk.length) {
                return false; // GBK 不存在孤立高位字节；防御性拦截
            }
            int hi = first;
            int lo = gbk[++i] & 0xFF;
            if (hi < 0xA1 || hi > 0xF7 || lo < 0xA1 || lo > 0xFE) {
                return false;
            }
        }
        return true;
    }

    /**
     * GB2312 客户端字库范围过滤：ASCII 单字节直接放行；双字节对必须落在
     * GB2312 编码空间（高位 0xA1-0xF7 且低位 0xA1-0xFE，含 01-09 区符号与一二级汉字）。
     * 落在 GBK 扩展区（高位 0x81-0xA0 或低位 0x40-0xA0）的繁体/生僻字/日文符号
     * 一律拦截——客户端无字形映射，渲染即崩。
     */
    private static boolean isGb2312Safe(byte[] gbk) {
        for (int i = 0; i < gbk.length; i++) {
            int first = gbk[i] & 0xFF;
            if (first < 0x80) {
                continue; // ASCII：空白/控制字符已由 isBlank 与字符数检查挡掉
            }
            if (i + 1 >= gbk.length) {
                return false; // GBK 不存在孤立高位字节；防御性拦截
            }
            int hi = first;
            int lo = gbk[++i] & 0xFF;
            if (hi < 0xA1 || hi > 0xF7 || lo < 0xA1 || lo > 0xFE) {
                return false;
            }
        }
        return true;
    }

    /** i18n 小池兜底（资源缺失场景）：随机取一个并加随机数字后缀降低撞名率。 */
    private static String fallbackName() {
        String pool = I18nUtil.getMessage(NAME_POOL_KEY);
        List<String> names = parsePool(pool);
        if (names.isEmpty()) {
            return "Bot" + Randomizer.nextInt(100000);
        }
        return names.get(Randomizer.nextInt(names.size())) + Randomizer.nextInt(1000);
    }

    /** 供测试断言：名字是否来自大池资源（资源缺失返回空集）。 */
    static java.util.Set<String> loadedNamePoolSnapshot() {
        return NAME_POOL.isEmpty() ? java.util.Set.of() : new java.util.HashSet<>(NAME_POOL);
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

    // ── BotLogic 依赖的公共工具（1:1 对齐 SoloMapling BotHelpers） ──────────

    /** 物品 ID → 名称（WZ 缺失时回退 "NULL"，与参考实现一致）。 */
    public static String convertItemIdToName(int itemId) {
        String itemName = ItemInformationProvider.getInstance().getName(itemId);
        if (itemName == null) {
            return "NULL";
        }
        return itemName;
    }

    /**
     * 判断 list2（如玩家掉落物）是否全部包含于 list1（如地板现有物）。
     * 元素按 itemId + ownerId + quantity 三元组等价判定（MapItem 身份，非引用身份）。
     */
    public static boolean checkSecondListInsideFirstList(List<MapObject> list1, List<MapObject> list2) {
        if (list1.size() < list2.size()) {
            return false;
        }
        for (MapObject obj2 : list2) {
            boolean found = false;
            for (MapObject obj1 : list1) {
                if (areObjectsEqual(obj1, obj2)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static boolean areObjectsEqual(MapObject obj1a, MapObject obj2b) {
        MapItem obj1 = (MapItem) obj1a;
        MapItem obj2 = (MapItem) obj2b;
        if (obj1 == obj2) {
            return true;
        }
        if (obj1 == null || obj2 == null) {
            return false;
        }
        return obj1.getItemId() == obj2.getItemId()
                && obj1.getOwnerId() == obj2.getOwnerId()
                && obj1.getItem().getQuantity() == obj2.getItem().getQuantity();
    }

    /** 以中心点构造矩形（高度 20% 垂直下偏，与参考实现一致）。 */
    public static Rectangle createRectangle(Point center, int width, int height) {
        int halfWidth = width / 2;
        int halfHeight = height / 2;
        int verticalOffset = (int) (height * 0.2);
        int centerYAdjusted = center.y - halfHeight + verticalOffset;
        int topLeftX = center.x - halfWidth;
        int topLeftY = centerYAdjusted - halfHeight;
        return new Rectangle(topLeftX, topLeftY, width, height);
    }

    /**
     * 等价 SoloMapling BotHelpers.blockingSleep：刻意阻塞当前线程（数据驱动的编排）。
     * 仅供 BotCommandsPack 等已在独立虚拟线程上运行的编排路径使用。
     */
    public static void blockingSleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
