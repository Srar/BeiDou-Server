package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.server.bot.types.IdleBot;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.gms.util.I18nUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.awt.Point;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BotHelpers：bot 判定（区段初筛 + 注册表双判据）、随机名字池格式。
 */
class BotHelpersTest {

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @AfterEach
    void tearDown() {
        BotStorage.removeActiveBot(BotHelpers.BOT_BASE_ID + 100);
    }

    @Test
    void isBotIdBySegmentBoundary() {
        // 区段初筛（高频发布点用）：真实玩家 id 由数据库自增，恒在区段之外
        assertFalse(BotHelpers.isBotId(BotHelpers.BOT_BASE_ID), "id == base must not be in bot segment");
        assertTrue(BotHelpers.isBotId(BotHelpers.BOT_BASE_ID + 1), "id > base must be in bot segment");
        assertFalse(BotHelpers.isBotId(12345), "real character id must not be in bot segment");
        assertFalse(BotHelpers.isBotId(0));
    }

    @Test
    void isBotRequiresRegistryHit() {
        int botId = BotHelpers.BOT_BASE_ID + 100;

        // 区段内但未注册：不是 bot（防真实玩家 id 意外越界被误判）
        assertFalse(BotHelpers.isBot(botId));

        // 注册后：是 bot
        BotStorage.addActiveBot(botId, new IdleBot(Mockito.mock(Character.class)));
        assertTrue(BotHelpers.isBot(botId));

        // 注销后：不再是 bot
        BotStorage.removeActiveBot(botId);
        assertFalse(BotHelpers.isBot(botId));
    }

    @Test
    void isBotByCharacter() {
        int botId = BotHelpers.BOT_BASE_ID + 100;
        Character bot = Mockito.mock(Character.class);
        Mockito.when(bot.getId()).thenReturn(botId);
        assertFalse(BotHelpers.isBot(bot), "unregistered segment character must not be a bot");

        BotStorage.addActiveBot(botId, new IdleBot(bot));
        assertTrue(BotHelpers.isBot(bot));

        Character player = Mockito.mock(Character.class);
        Mockito.when(player.getId()).thenReturn(99);
        assertFalse(BotHelpers.isBot(player));
        assertFalse(BotHelpers.isBot(null));
    }

    @Test
    void randomBotNameComesFromPool() {
        // 大池无放回轮盘（对齐 SoloMapling FMShopDescGen 语义）：名字必须来自池，
        // 且连续发放绝不重名（旧实现 20 个小池有放回随机导致满屏「豆豆2/云朵11」）。
        // 池为懒加载：单独运行本测试类时尚未加载，先触发一次加载再取快照。
        if (BotHelpers.loadedNamePoolSnapshot().isEmpty()) {
            BotHelpers.randomBotName();
        }
        Set<String> poolNames = BotHelpers.loadedNamePoolSnapshot();
        assertFalse(poolNames.isEmpty(), "bot name pool resource must not be empty");

        Set<String> issued = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String name = BotHelpers.randomBotName();
            assertNotNull(name);
            assertFalse(name.isBlank(), "generated name must not be blank");
            assertTrue(poolNames.contains(name), "generated name not from pool: " + name);
            assertTrue(issued.add(name), "duplicate name issued: " + name);
        }
    }

    @Test
    void loadedNamePoolIsEncodingSafe() {
        // 名字编码安全审计（客户端栈溢出/尾字节风险）：全池名字必须 GBK 可编码、
        // GBK 字节数 ≤12（v83 客户端名字字段固定 13 字节）且不含危险尾字节。
        // 池为空时先触发懒加载；若资源文件缺失（打包异常）则跳过断言。
        if (BotHelpers.loadedNamePoolSnapshot().isEmpty()) {
            BotHelpers.randomBotName();
        }
        Set<String> poolNames = BotHelpers.loadedNamePoolSnapshot();
        if (poolNames.isEmpty()) {
            return;
        }

        Charset gbk = Charset.forName("GBK");
        CharsetEncoder encoder = gbk.newEncoder();
        for (String name : poolNames) {
            assertTrue(encoder.canEncode(name), "name must be GBK-encodable: " + name);
            byte[] bytes = name.getBytes(gbk);
            assertTrue(bytes.length <= 12,
                    "GBK byte length must be <= 12 for the v83 13-byte name field: " + name);
            // GB2312 客户端字库范围：双字节对必须高位 0xA1-0xF7 且低位 0xA1-0xFE
            // （客户端无扩展区字形映射，渲染即崩——2026-08-17 崩溃事故根因）
            for (int i = 0; i < bytes.length; i++) {
                int first = bytes[i] & 0xFF;
                if (first < 0x80) {
                    continue;
                }
                assertTrue(i + 1 < bytes.length, "dangling GBK lead byte: " + name);
                int hi = first;
                int lo = bytes[++i] & 0xFF;
                assertTrue(hi >= 0xA1 && hi <= 0xF7 && lo >= 0xA1 && lo <= 0xFE,
                        "name contains char outside GB2312 client font range (0x"
                                + Integer.toHexString(hi).toUpperCase() + Integer.toHexString(lo).toUpperCase()
                                + "): " + name);
            }
            // 危险尾字节清单直接引用生产常量，杜绝测试与实现双份清单漂移
            for (byte b : bytes) {
                for (byte unsafe : BotHelpers.UNSAFE_TAIL_BYTES) {
                    assertFalse(b == unsafe,
                            "name must not contain unsafe tail byte 0x"
                                    + Integer.toHexString(b & 0xFF).toUpperCase() + ": " + name);
                }
            }
        }
    }

    /** 反射调用 BotHelpers 私有方法 isNameSafe（加载过滤逻辑的单元级入口）。 */
    private static boolean isNameSafe(String name) throws Exception {
        Method method = BotHelpers.class.getDeclaredMethod("isNameSafe", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, name);
    }

    @Test
    void isNameSafeRejectsOverflowAndUnsafeTailBytes() throws Exception {
        // 危险尾字节：「啈運星」GBK = 86 91 DF 5C D0 C7，含 0x5C '\'，干扰客户端 C 字符串解析
        assertFalse(isNameSafe("啈運星"), "GBK byte stream containing 0x5C must be rejected");
        // 超长全角名：7 个全角字符 → GBK 14 字节 > 12，会溢出 v83 客户端 13 字节名字字段
        assertFalse(isNameSafe("一二三四五六七"), "GBK byte length > 12 must be rejected");
        // 字符数超限：13 个 ASCII 字符
        assertFalse(isNameSafe("abcdefghijklm"), "char length > 12 must be rejected");
        // 空/空白
        assertFalse(isNameSafe(""));
        assertFalse(isNameSafe("   "));
        assertFalse(isNameSafe(null));
        // 正常名字通过
        assertTrue(isNameSafe("糖糖"), "safe name must pass");
        assertTrue(isNameSafe("Bot"), "safe ASCII name must pass");
        // 边界：6 个全角字符恰好 12 字节，应通过
        assertTrue(isNameSafe("一二三四五六"), "12-byte GBK name must pass");
    }

    @Test
    void isNameSafeRejectsGbkExtensionCharacters() throws Exception {
        // GBK 扩展区字符（客户端 GB2312 字库无字形映射，渲染即崩）：
        // 2026-08-17 事故中崩溃前最后一条 SPAWN_PLAYER 的 bot 名「頹廢菂愛」，
        // 其中「菂」= 0xC785、「頹」= 0xEE6A、「廢」= 0x8F55——全部落在扩展区。
        assertFalse(isNameSafe("頹廢菂愛"), "GBK extension chars must be rejected");
        assertFalse(isNameSafe("菂"), "rare char outside GB2312 must be rejected");
        assertFalse(isNameSafe("轉身離開ゞ"), "traditional chars / kana mark must be rejected");
        assertFalse(isNameSafe("天真菂回憶"), "the crash-scene char must be rejected");
        assertFalse(isNameSafe("℡乖℡"), "symbol outside GB2312 must be rejected");
        assertFalse(isNameSafe("﹏狼﹏"), "wavy dash outside GB2312 must be rejected");
        // GB2312 区内字符放行：简体汉字、常用符号（☆）、全角数字
        assertTrue(isNameSafe("转身离开"), "simplified Chinese must pass");
        assertTrue(isNameSafe("天真回忆"), "simplified Chinese must pass");
        assertTrue(isNameSafe("低调小老虎"), "simplified Chinese must pass");
        assertTrue(isNameSafe("☆蓝☆"), "GB2312 symbol zone must pass");
        assertTrue(isNameSafe("小①"), "full-width digit must pass");
    }

    @Test
    void pickGroundSpotsSpreadsBatchAcrossX() {
        // 回归防线（「批量 bot 都站在一起」）：出生点必须沿 X 分散且保持最小间距，
        // 每个点经 getPointBelow 修正到地面（mock：地面 Y 恒为 100）
        MapleMap map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getPointBelow(Mockito.any())).thenAnswer(inv -> new Point(
                ((Point) inv.getArgument(0)).x, 100));

        List<Point> spots = BotHelpers.pickGroundSpots(map, new Point(0, 100), 10);

        assertEquals(10, spots.size());
        for (Point spot : spots) {
            assertEquals(100, spot.y, "every spot must be grounded via getPointBelow");
        }
        for (int i = 0; i < spots.size(); i++) {
            for (int j = i + 1; j < spots.size(); j++) {
                assertTrue(Math.abs(spots.get(i).x - spots.get(j).x) >= 20,
                        "spots must keep horizontal spacing, got " + spots.get(i) + " vs " + spots.get(j));
            }
        }
    }

    @Test
    void pickGroundSpotsFallsBackToAnchorWhenNoFoothold() {
        MapleMap map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getPointBelow(Mockito.any())).thenReturn(null);

        List<Point> spots = BotHelpers.pickGroundSpots(map, new Point(50, 60), 5);

        assertEquals(5, spots.size());
        for (Point spot : spots) {
            assertEquals(new Point(50, 60), spot,
                    "without footholds every spot must fall back to the anchor");
        }
    }

    @Test
    void pickGroundSpotsHandlesDegenerateInputs() {
        MapleMap map = Mockito.mock(MapleMap.class);
        assertTrue(BotHelpers.pickGroundSpots(null, new Point(0, 0), 3).isEmpty());
        assertTrue(BotHelpers.pickGroundSpots(map, null, 3).isEmpty());
        assertTrue(BotHelpers.pickGroundSpots(map, new Point(0, 0), 0).isEmpty());
    }
}
