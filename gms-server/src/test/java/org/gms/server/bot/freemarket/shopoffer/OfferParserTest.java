package org.gms.server.bot.freemarket.shopoffer;

import org.gms.client.inventory.Item;
import org.gms.server.maps.PlayerShopItem;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * OfferParser 单测：价格后缀、序号、同名多件最近价格匹配、非法输入。
 * 纯逻辑测试（注入物品名解析器，不触碰 ItemInformationProvider 的 wz/DB 静态初始化）。
 */
class OfferParserTest {

    private static final int RED_POTION = 2000000;
    private static final int ORANGE_POTION = 2000001;

    /** 注入解析器：与生产 ItemInformationProvider.getName 形状一致的最小替代。 */
    private static final Function<Integer, String> NAME_RESOLVER = id -> {
        if (id == RED_POTION) {
            return "Red Potion";
        }
        if (id == ORANGE_POTION) {
            return "Orange Potion";
        }
        return null;
    };

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    private static PlayerShopItem shopItem(int itemId, int price) {
        return new PlayerShopItem(new Item(itemId, (short) 0, (short) 1), (short) 1, price);
    }

    // ─────────────────────────── extractPrice：价格后缀 ───────────────────────────

    @Test
    void extractPriceScalesKMBsuffixes() {
        assertEquals(50_000_000L, OfferParser.extractPrice("50m"));
        assertEquals(1_500_000_000L, OfferParser.extractPrice("1.5b"));
        assertEquals(500_000L, OfferParser.extractPrice("500k"));
        assertEquals(50_000_000L, OfferParser.extractPrice("50M"), "后缀大小写不敏感");
    }

    @Test
    void extractPriceFindsPriceInsideSentence() {
        assertEquals(50_000_000L, OfferParser.extractPrice("我愿意出 50m 买这个"));
        assertEquals(80_000_000L, OfferParser.extractPrice("20m 不行就 80m"), "多组数字取最大");
    }

    @Test
    void extractPriceRejectsInvalidInput() {
        assertEquals(-1L, OfferParser.extractPrice("你好，有货吗"));
        assertEquals(-1L, OfferParser.extractPrice(""));
        assertEquals(0L, OfferParser.extractPrice("0"), "纯零价格为 0，parse 层拒绝");
    }

    // ─────────────────────────── extractOrdinal：序号 ───────────────────────────

    @Test
    void extractOrdinalParsesStNdRdTh() {
        assertEquals(1, OfferParser.extractOrdinal("1st red potion"));
        assertEquals(2, OfferParser.extractOrdinal("2nd red potion"));
        assertEquals(3, OfferParser.extractOrdinal("3rd red potion"));
        assertEquals(4, OfferParser.extractOrdinal("4th red potion"));
        assertEquals(10, OfferParser.extractOrdinal("10th red potion"));
        assertEquals(-1, OfferParser.extractOrdinal("red potion"), "无序号返回 -1");
    }

    // ─────────────────────────── parse：匹配与选件 ───────────────────────────

    @Test
    void parseMatchesItemNameAndPrice() {
        List<PlayerShopItem> items = List.of(shopItem(RED_POTION, 100_000_000));
        OfferParser.ParsedOffer offer = OfferParser.parse("50m for Red Potion", items, NAME_RESOLVER);

        assertEquals("Red Potion", offer.getItemName());
        assertEquals(0, offer.getItemIndex());
        assertEquals(50_000_000L, offer.getOfferPrice());
        assertSame(items.get(0), offer.getShopItem());
    }

    @Test
    void parseIsCaseInsensitiveOnItemName() {
        List<PlayerShopItem> items = List.of(shopItem(RED_POTION, 100_000_000));
        OfferParser.ParsedOffer offer = OfferParser.parse("50m red potion", items, NAME_RESOLVER);

        assertEquals("Red Potion", offer.getItemName());
    }

    @Test
    void parseReturnsNullForInvalidInput() {
        List<PlayerShopItem> items = List.of(shopItem(RED_POTION, 100_000_000));
        assertNull(OfferParser.parse("没有价格的 red potion", items, NAME_RESOLVER), "无价格应返回 null");
        assertNull(OfferParser.parse("50m 买别的", items, NAME_RESOLVER), "物品名不匹配应返回 null");
        assertNull(OfferParser.parse("50m", List.of(), NAME_RESOLVER), "空商品列表应返回 null");
    }

    @Test
    void parseSelectsByOrdinalForDuplicates() {
        List<PlayerShopItem> items = List.of(
                shopItem(RED_POTION, 100_000_000),
                shopItem(RED_POTION, 200_000_000)
        );

        assertEquals(0, OfferParser.parse("1st red potion 50m", items, NAME_RESOLVER).getItemIndex());
        assertEquals(1, OfferParser.parse("2nd red potion 50m", items, NAME_RESOLVER).getItemIndex());
    }

    @Test
    void parseFallsBackToClosestPriceWhenOrdinalOutOfRange() {
        List<PlayerShopItem> items = List.of(
                shopItem(RED_POTION, 100),
                shopItem(RED_POTION, 60_000_000)
        );

        // 序号超范围（只有 2 件却报 3rd）→ 回退最近价格匹配：60m 更接近 50m
        OfferParser.ParsedOffer offer = OfferParser.parse("3rd red potion 50m", items, NAME_RESOLVER);
        assertEquals(1, offer.getItemIndex());
    }

    @Test
    void parsePicksClosestListingPriceForUnqualifiedDuplicates() {
        List<PlayerShopItem> items = List.of(
                shopItem(RED_POTION, 100),
                shopItem(RED_POTION, 60_000_000)
        );

        OfferParser.ParsedOffer offer = OfferParser.parse("red potion 50m", items, NAME_RESOLVER);
        assertEquals(1, offer.getItemIndex(), "|60m-50m| < |100-50m|，应选标价 60m 那件");
    }

    @Test
    void parseSkipsSoldOutAndRemovedItems() {
        PlayerShopItem soldOut = shopItem(RED_POTION, 100);
        soldOut.setBundles((short) 0);
        PlayerShopItem removed = shopItem(RED_POTION, 200);
        removed.setDoesExist(false);
        PlayerShopItem alive = shopItem(RED_POTION, 300);
        List<PlayerShopItem> items = List.of(soldOut, removed, alive);

        OfferParser.ParsedOffer offer = OfferParser.parse("red potion 50m", items, NAME_RESOLVER);
        assertEquals(2, offer.getItemIndex(), "售罄/移除商品不应参与匹配");
    }
}
