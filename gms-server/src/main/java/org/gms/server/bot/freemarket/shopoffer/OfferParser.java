package org.gms.server.bot.freemarket.shopoffer;

import org.gms.server.ItemInformationProvider;
import org.gms.server.maps.PlayerShopItem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报价解析器（移植自 SoloMapling FreeMarket.ShopOfferSystem.OfferParser）。
 * <p>
 * 从玩家在 bot 商店里的聊天文本中提取两部分信息：
 * <ul>
 *   <li>价格：数字 + 可选后缀 k/m/b（大小写不敏感），多组数字取最大；</li>
 *   <li>目标物品：按物品名在店内商品列表中匹配；同名多件时可用 1st/2nd/3rd/Nth
 *       序号显式指定，否则按「与报价价格最接近的标价」自动选一件。</li>
 * </ul>
 * 匹配不到有效价格或物品时返回 null（调用方静默忽略）。
 */
public class OfferParser {

    private static final Pattern PRICE_PATTERN = Pattern.compile("(\\d+\\.?\\d*)\\s*(k|m|b)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDINAL_PATTERN = Pattern.compile("(1st|2nd|3rd|([4-9]|\\d{2,})th)", Pattern.CASE_INSENSITIVE);

    public static class ParsedOffer {
        private final String itemName;
        private final int itemIndex;
        private final long offerPrice;
        private final PlayerShopItem shopItem;

        public ParsedOffer(String itemName, int itemIndex, long offerPrice, PlayerShopItem shopItem) {
            this.itemName = itemName;
            this.itemIndex = itemIndex;
            this.offerPrice = offerPrice;
            this.shopItem = shopItem;
        }

        public String getItemName() {
            return itemName;
        }

        public int getItemIndex() {
            return itemIndex;
        }

        public long getOfferPrice() {
            return offerPrice;
        }

        public PlayerShopItem getShopItem() {
            return shopItem;
        }
    }

    public static ParsedOffer parse(String message, List<PlayerShopItem> shopItems) {
        return parse(message, shopItems, OfferParser::resolveItemName);
    }

    /**
     * 注入物品名解析器（测试专用）：生产路径用 {@link ItemInformationProvider}，
     * 其单例静态初始化会触碰 wz 与数据库，纯单测环境无法加载。
     */
    static ParsedOffer parse(String message, List<PlayerShopItem> shopItems, Function<Integer, String> nameResolver) {
        long price = extractPrice(message);
        if (price <= 0) {
            return null;
        }

        String lower = message.toLowerCase();
        int requestedOrdinal = extractOrdinal(lower);

        String matchedName = null;
        List<Integer> matchingIndices = new ArrayList<>();

        for (int i = 0; i < shopItems.size(); i++) {
            PlayerShopItem item = shopItems.get(i);
            if (!item.isExist() || item.getBundles() <= 0) {
                continue;
            }

            String itemName = nameResolver.apply(item.getItem().getItemId());
            if (itemName == null || itemName.isEmpty()) {
                continue;
            }

            if (lower.contains(itemName.toLowerCase())) {
                if (matchedName == null) {
                    matchedName = itemName;
                }
                if (itemName.equalsIgnoreCase(matchedName)) {
                    matchingIndices.add(i);
                }
            }
        }

        if (matchedName == null || matchingIndices.isEmpty()) {
            return null;
        }

        int selectedIdx;
        if (requestedOrdinal > 0 && requestedOrdinal <= matchingIndices.size()) {
            selectedIdx = matchingIndices.get(requestedOrdinal - 1);
        } else if (matchingIndices.size() == 1) {
            selectedIdx = matchingIndices.get(0);
        } else {
            selectedIdx = findClosestPriceMatch(matchingIndices, shopItems, price);
        }

        PlayerShopItem matched = shopItems.get(selectedIdx);
        return new ParsedOffer(matchedName, selectedIdx, price, matched);
    }

    private static String resolveItemName(int itemId) {
        return ItemInformationProvider.getInstance().getName(itemId);
    }

    /** 同名多件且未给序号时：选标价与报价最接近的一件。 */
    private static int findClosestPriceMatch(List<Integer> indices, List<PlayerShopItem> items, long offerPrice) {
        int bestIdx = indices.get(0);
        long bestDiff = Long.MAX_VALUE;

        for (int idx : indices) {
            long diff = Math.abs(items.get(idx).getPrice() - offerPrice);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIdx = idx;
            }
        }
        return bestIdx;
    }

    static int extractOrdinal(String lower) {
        Matcher m = ORDINAL_PATTERN.matcher(lower);
        if (!m.find()) {
            return -1;
        }

        String match = m.group().toLowerCase();
        if (match.equals("1st")) {
            return 1;
        }
        if (match.equals("2nd")) {
            return 2;
        }
        if (match.equals("3rd")) {
            return 3;
        }
        String digits = match.replaceAll("\\D", "");
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static long extractPrice(String message) {
        Matcher matcher = PRICE_PATTERN.matcher(message);
        long bestPrice = -1;

        while (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1));
                String suffix = matcher.group(2);
                if (suffix != null) {
                    switch (suffix.toLowerCase()) {
                        case "k":
                            value *= 1_000;
                            break;
                        case "m":
                            value *= 1_000_000;
                            break;
                        case "b":
                            value *= 1_000_000_000;
                            break;
                        default:
                            break;
                    }
                }
                long parsed = (long) value;
                if (parsed > bestPrice) {
                    bestPrice = parsed;
                }
            } catch (NumberFormatException e) {
                // 跳过格式异常的片段，继续尝试后续匹配
            }
        }

        return bestPrice;
    }
}
