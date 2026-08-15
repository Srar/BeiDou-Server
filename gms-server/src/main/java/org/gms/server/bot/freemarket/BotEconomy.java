package org.gms.server.bot.freemarket;

import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Item;
import org.gms.server.ItemInformationProvider;
import org.gms.util.Randomizer;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 自由市场经济定价与估值工具（逐行移植自 SoloMapling FreeMarket.FMEconomyManager /
 * itemPool.ItemUtilities / itemPool.ItemInformationProviderUtilities 中商业类 Bot 用到的子集）。
 * <p>
 * gms 底座差异：无 ItemDatabase / UpgradeSimulator，物品市场价统一退化为 WZ 基准价
 * （{@link ItemInformationProvider#getWholePrice(int)}），已强化装备的估值以 TODO 保留。
 */
public final class BotEconomy {

    private BotEconomy() {
    }

    // ── 物品名称 / 估值 ────────────────────────────────────────────────────

    public static String getItemName(int itemId) {
        return ItemInformationProvider.getInstance().getName(itemId);
    }

    /**
     * 源 ItemUtilities.getItemMarketValue：读 ItemDatabase 市场价（干净装备/非装备）
     * 或 UpgradeSimulator.getEquipMarketValue（已强化装备）。gms 无这两个模块，
     * 此处统一返回 WZ 基准价，非正时兜底 5m。
     */
    public static Integer getItemMarketValue(Item item) {
        if (item == null) {
            return 0;
        }
        int wzPrice = ItemInformationProvider.getInstance().getWholePrice(item.getItemId());
        if (wzPrice <= 0) {
            wzPrice = 5_000_000; // 源 getWzPrice 对无价随机物品的兜底
        }
        // TODO(UpgradeSimulator)：已强化装备应按强化属性估值（源 UpgradeSimulator.getEquipMarketValue）。
        return wzPrice;
    }

    /**
     * 源 UpgradeSimulator.getEquipMarketValue：基于强化属性估值。gms 未移植，
     * 简化为 WZ 基准价 + 每层强化（getLevel）加成。
     */
    public static int getEquipMarketValue(Equip equip) {
        int base = ItemInformationProvider.getInstance().getWholePrice(equip.getItemId());
        int upgradeBonus = equip.getLevel() * 100_000;
        return Math.max(0, base) + upgradeBonus;
    }

    // ── 定价规则（源 FMEconomyManager.priceAdjustmentRules(int)） ─────────────

    public static int priceAdjustmentRules(int adjustedPrice) {
        adjustedPrice = calcPriceBasedOnMarket(adjustedPrice); // Adjust price based on market Value
        adjustedPrice = calcPriceBasedOnDay(adjustedPrice);
        adjustedPrice = randomizePriceAdjustment(adjustedPrice);

        // format price number
        adjustedPrice = getPriceStylingNotation(adjustedPrice);
        return adjustedPrice;
    }

    public static int calcPriceBasedOnDay(int price) {
        DayOfWeek currentDay = LocalDate.now().getDayOfWeek();
        if (currentDay == DayOfWeek.FRIDAY || currentDay == DayOfWeek.SATURDAY || currentDay == DayOfWeek.SUNDAY) {
            return (int) (price * 1.10); // Increase by 10%
        } else if (currentDay == DayOfWeek.MONDAY || currentDay == DayOfWeek.TUESDAY) {
            return (int) (price * 0.95); // Decrease by 5%
        }
        return price; // No adjustment for other days
    }

    public static int randomizePriceAdjustment(int price) {
        // Define weighted probabilities for the adjustments
        int[] adjustments = {-10, -5, 0, 5, 10}; // Adjustments in percentages
        int[] weights = {10, 20, 40, 20, 10};    // Weights corresponding to the adjustments

        int totalWeight = 0;
        for (int weight : weights) {
            totalWeight += weight;
        }

        int randomValue = Randomizer.nextInt(totalWeight) + 1;

        int cumulativeWeight = 0;
        int chosenAdjustment = 0;
        for (int i = 0; i < adjustments.length; i++) {
            cumulativeWeight += weights[i];
            if (randomValue <= cumulativeWeight) {
                chosenAdjustment = adjustments[i];
                break;
            }
        }

        double multiplier = 1 + (chosenAdjustment / 100.0);
        return (int) (price * multiplier);
    }

    // ── 价格展示 ────────────────────────────────────────────────────────────

    public static String formatPriceToShorthand(int price) {
        return formatPriceToShorthand(price, 1);
    }

    public static String formatPriceToShorthand(int price, int decimalPlaces) {
        if (price < 1000) {
            return String.valueOf(price);
        }

        final String[] suffixes = {"", "k", "m", "b"};
        int suffixIndex = 0;
        double formattedPrice = price;

        while (formattedPrice >= 1000 && suffixIndex < suffixes.length - 1) {
            formattedPrice /= 1000;
            suffixIndex++;
        }

        if (decimalPlaces <= 0) {
            return Math.round(formattedPrice) + suffixes[suffixIndex];
        } else {
            String formatPattern = "%." + decimalPlaces + "f";
            String formatted = String.format(formatPattern, formattedPrice);
            if (formatted.contains(".")) {
                formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            return formatted + suffixes[suffixIndex];
        }
    }

    // ── 市场波动指数（源 FMEconomyManager 的 24h/2h 正弦 + 噪声） ─────────────

    private static final int HOURS_IN_A_DAY = 24;
    private static final int UPDATE_INTERVAL_HOURS = 2;
    private static final int VALUES_COUNT = HOURS_IN_A_DAY / UPDATE_INTERVAL_HOURS;
    private static final double MIN_INDEX = 0.85;
    private static final double MAX_INDEX = 1.15;
    private static List<Double> marketIndices = null;

    public static List<Double> getMarketIndices() {
        if (marketIndices == null) {
            generateMarketIndices();
        }
        return marketIndices;
    }

    private static void generateMarketIndices() {
        marketIndices = new ArrayList<>();
        double amplitude = (MAX_INDEX - MIN_INDEX) / 2.0;
        double baseline = (MAX_INDEX + MIN_INDEX) / 2.0;

        for (int i = 0; i < VALUES_COUNT; i++) {
            double sineComponent = Math.sin((2 * Math.PI / VALUES_COUNT) * i);
            double randomNoise = (Randomizer.nextDouble() - 0.5) * 0.05; // +/- 0.025
            double index = baseline + amplitude * sineComponent + randomNoise;
            marketIndices.add(clamp(index, MIN_INDEX, MAX_INDEX));
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double getCurrentMarketIndex() {
        List<Double> indices = getMarketIndices();
        int currentHour = LocalTime.now().getHour();
        int indexPosition = (currentHour / UPDATE_INTERVAL_HOURS) % VALUES_COUNT;
        return indices.get(indexPosition);
    }

    public static int calcPriceBasedOnMarket(int price) {
        return (int) (price * getCurrentMarketIndex());
    }

    // ── 价格风格化（源 FMEconomyManager.getPriceStylingNotation 及其辅助） ─────

    public static int getPriceStylingNotation(int number) {
        int formatType = Randomizer.nextInt(3);
        number = roundToNearestCleanNumber(number);
        switch (formatType) {
            case 0:
                return formatWithTrailingZeros(number);
            case 1:
                return formatWithTrailingNines(number);
            case 2:
                return formatWithTrailingMainNumber(number);
            default:
                return number;
        }
    }

    public static int formatWithTrailingZeros(int num) {
        return num;
    }

    public static int formatWithTrailingNines(int num) {
        return num - 1;
    }

    public static int formatWithTrailingMainNumber(int number) {
        String numStr = String.valueOf(number);
        int lastNonZeroIndex = -1;
        for (int i = numStr.length() - 1; i >= 0; i--) {
            if (numStr.charAt(i) != '0') {
                lastNonZeroIndex = i;
                break;
            }
        }
        if (lastNonZeroIndex == -1) {
            return number;
        }
        char lastNonZeroDigit = numStr.charAt(lastNonZeroIndex);
        StringBuilder newNumStr = new StringBuilder(numStr.substring(0, lastNonZeroIndex + 1));
        for (int i = lastNonZeroIndex + 1; i < numStr.length(); i++) {
            newNumStr.append(lastNonZeroDigit);
        }
        return Integer.parseInt(newNumStr.toString());
    }

    public static int roundToNearestCleanNumber(int number) {
        int numDigits = String.valueOf(Math.abs(number)).length();
        int significantDigits;
        if (6 <= numDigits && numDigits <= 9) {
            significantDigits = 2;
        } else if (numDigits == 10) {
            significantDigits = 3;
        } else {
            return number;
        }

        int mostSignificant = Integer.parseInt(String.valueOf(number).substring(0, significantDigits));
        int orderMagnitude = (int) Math.pow(10, numDigits - significantDigits);
        return Math.round(number / orderMagnitude) * orderMagnitude;
    }
}
