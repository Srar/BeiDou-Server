package org.gms.server.bot.freemarket;

import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Item;
import org.gms.server.ItemInformationProvider;
import org.gms.server.maps.HiredMerchant;
import org.gms.util.Randomizer;

import java.awt.Point;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
        return priceAdjustmentRules(adjustedPrice, null, null);
    }

    /**
     * 源 FMEconomyManager.priceAdjustmentRules(int, Integer, Point)：单参版本之上的完整规则链，
     * 随机化之后追加 FM 房间溢价与门位溢价（mapId / pos 任一为 null 时跳过对应溢价）。
     */
    public static int priceAdjustmentRules(int adjustedPrice, Integer mapId, Point pos) {
        adjustedPrice = calcPriceBasedOnMarket(adjustedPrice); // Adjust price based on market Value
        adjustedPrice = calcPriceBasedOnDay(adjustedPrice);
        adjustedPrice = randomizePriceAdjustment(adjustedPrice);

        if (mapId != null) {
            adjustedPrice = FMRoomPremiumCalculator(mapId, adjustedPrice);
        }
        if (mapId != null && pos != null) {
            adjustedPrice = FMRoomDoorSpotPremiumCalculator(mapId, pos, adjustedPrice);
        }

        // format price number
        adjustedPrice = getPriceStylingNotation(adjustedPrice);
        return adjustedPrice;
    }

    /**
     * 源 FMEconomyManager.adjustFMPrices(HiredMerchantArtificial, int)。
     * gms 适配：源参数类型 HiredMerchantArtificial extends HiredMerchant（该子类由并行代理负责移植），
     * 方法体内仅使用基类方法 getPosition()/getMapId()，故此处直接以 gms 基类
     * {@link HiredMerchant} 收参；将来 HiredMerchantArtificial 实例传入时自动向上转型，语义不变。
     */
    public static int adjustFMPrices(HiredMerchant merchant, int price) {
        int adjustedPrice = price;
        Point pos = merchant.getPosition();
        int mapId = merchant.getMapId();
        return priceAdjustmentRules(adjustedPrice, mapId, pos);
    }

    /**
     * 源 FMEconomyManager.adjustFMQuantity(HiredMerchantArtificial, int)：数量同样叠加房间溢价
     * 与门位溢价（源如此，房间 multiplier 0.82-1.25 直接作用于数量）。参数类型适配说明同 adjustFMPrices。
     */
    public static int adjustFMQuantity(HiredMerchant merchant, int quantity) {
        int adjustedQuantity = quantity;
        if (adjustedQuantity == 1) {
            return 1;
        }
        Point pos = merchant.getPosition();
        int mapId = merchant.getMapId();

        adjustedQuantity = FMRoomPremiumCalculator(mapId, adjustedQuantity);
        adjustedQuantity = FMRoomDoorSpotPremiumCalculator(mapId, pos, adjustedQuantity);

        return adjustedQuantity;
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
        // 国服化：万/亿中文单位，10000 以下保持纯数字（如 5500 → "5500"）
        if (price < 10000) {
            return String.valueOf(price);
        }

        String unit;
        double formattedPrice = price;
        if (price >= 100_000_000) {
            unit = "亿";
            formattedPrice = price / 100_000_000.0;
        } else {
            unit = "万";
            formattedPrice = price / 10000.0;
        }

        if (decimalPlaces <= 0) {
            return normalizeWanToYi(Math.round(formattedPrice), unit);
        } else {
            String formatPattern = "%." + decimalPlaces + "f";
            String formatted = String.format(formatPattern, formattedPrice);
            if (formatted.contains(".")) {
                formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            return normalizeWanToYi(Double.parseDouble(formatted), unit);
        }
    }

    /** 万级数值 >= 10000 时进位到亿（如 9999.9 万 → "1亿"），避免出现 "10000万"。 */
    private static String normalizeWanToYi(double value, String unit) {
        if ("万".equals(unit) && value >= 10000.0) {
            String formatted = String.valueOf(Math.round(value / 10000.0 * 100.0) / 100.0);
            if (formatted.contains(".")) {
                formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            return formatted + "亿";
        }
        return (value == Math.floor(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value) : String.valueOf(value)) + unit;
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

    // ── FM 房间层级与溢价（源 FMEconomyManager，SoloMapling FreeMarket） ──────
    // 移植说明：本段补齐源 FMEconomyManager 中房间维度的定价组件，包括 HOT_ROOMS/isHotRoom、
    // mapIdMultipliers、FMRoomPremiumCalculator（0.82-1.25 房间溢价）、FMRoomDoorSpotPremiumCalculator
    // （门位溢价 ×1.2）、getTierForRoom/weightedRandomSelection（按房间抽 S/A/B 档）。
    // 数据来源适配：门位溢价的 region 列表源自 FMShopInfoManager 的静态字段
    // henesysRegionFM（910000001-910000006）与 ludiRegionFM（910000007-910000012）。
    // 源通过 fmInfo.getRegionFMMapId("henesys"/"ludi") 读取，gms 版 FMShopInfoManager 由并行代理
    // 正在移植，此处硬编码同值以保持本类自洽；待其建成后可切换为调用其 getRegionFMMapId。

    private static final int[] HOT_ROOMS = {910000001, 910000002, 910000007};

    public static boolean isHotRoom(int mapId) {
        for (int hotRoom : HOT_ROOMS) {
            if (mapId == hotRoom) return true;
        }
        return false;
    }

    // 同源 FMShopInfoManager.henesysRegionFM（门位：henesys 自由市场入口）
    private static final List<Integer> HENESYS_REGION_FM = List.of(910000001, 910000002, 910000003, 910000004, 910000005, 910000006);
    // 同源 FMShopInfoManager.ludiRegionFM（门位：ludi 自由市场入口）
    private static final List<Integer> LUDI_REGION_FM = List.of(910000007, 910000008, 910000009, 910000010, 910000011, 910000012);

    private static final Map<Integer, Double> mapIdMultipliers = Map.ofEntries(
            Map.entry(910000001, 1.25),
            Map.entry(910000002, 1.20),
            Map.entry(910000003, 1.10),

            Map.entry(910000007, 1.15),

            Map.entry(910000014, 0.97),
            Map.entry(910000015, 0.95),
            Map.entry(910000016, 0.93),
            Map.entry(910000017, 0.91),

            Map.entry(910000018, 0.9),
            Map.entry(910000019, 0.88),
            Map.entry(910000020, 0.86),
            Map.entry(910000021, 0.84),
            Map.entry(910000022, 0.82)
    );

    public static int FMRoomPremiumCalculator(int mapId, int adjustedPrice) {
        // Specific FM room premium
        double multiplier = mapIdMultipliers.getOrDefault(mapId, 1.0);
        adjustedPrice = (int) (adjustedPrice * multiplier);
        return adjustedPrice;
    }

    public static int FMRoomDoorSpotPremiumCalculator(int mapId, Point pos, int adjustedPrice) {
        // Door Spot premium
        if (HENESYS_REGION_FM.contains(mapId)) { // door spots
            if (isPointWithinArea(pos, new Point(300, -80), new Point(640, 34))) {
                adjustedPrice = (int) (adjustedPrice * 1.2);
            }
        }

        if (LUDI_REGION_FM.contains(mapId)) { // door spots
            if (isPointWithinArea(pos, new Point(-709, -100), new Point(-109, 102))) {
                adjustedPrice = (int) (adjustedPrice * 1.2);
            }
        }
        return adjustedPrice;
    }

    public static boolean isPointWithinArea(Point point, Point topleft, Point botright) {
        return point.x >= topleft.x && point.x <= botright.x &&
                point.y >= topleft.y && point.y <= botright.y;
    }

    // Method to determine which tier to pick based on room number
    public static String getTierForRoom(int roomNumber) {
        Random random = new Random();

        if (isHotRoom(roomNumber)) {
            int[] weightsHot = {85, 13, 2};  // 85% S, 13% A, 2% B
            return weightedRandomSelection(weightsHot, random);
        } else if (roomNumber == 910000003) {
            int[] weightsWarm = {60, 35, 5};  // 60% S, 35% A, 5% B
            return weightedRandomSelection(weightsWarm, random);
        } else if (roomNumber >= 910000004 && roomNumber <= 910000006 || roomNumber >= 910000008 && roomNumber <= 910000012) {
            // A-tier is more likely
            int[] weightsA = {25, 60, 15};
            return weightedRandomSelection(weightsA, random);
        } else if (roomNumber == 910000013) {
            // Balanced distribution for S, A, and B
            int[] weightsBalanced = {33, 33, 34};
            return weightedRandomSelection(weightsBalanced, random);
        } else if (roomNumber >= 910000014 && roomNumber <= 910000017) {
            // B-tier is more likely
            int[] weightsB = {15, 30, 55};
            return weightedRandomSelection(weightsB, random);
        } else if (roomNumber >= 910000018 && roomNumber <= 910000022) {
            int[] weightsRandom = {20, 40, 40};
            return weightedRandomSelection(weightsRandom, random);
        }
        return "Unknown"; // Default case, should not occur if room number is valid
    }

    // Helper method for weighted random selection
    private static String weightedRandomSelection(int[] weights, Random random) {
        int totalWeight = 0;
        for (int weight : weights) {
            totalWeight += weight;
        }

        int randomValue = random.nextInt(totalWeight);  // Random number between 0 and totalWeight
        int cumulativeWeight = 0;

        if (randomValue < cumulativeWeight + weights[0]) {
            return "S";  // Select S
        }
        cumulativeWeight += weights[0];
        if (randomValue < cumulativeWeight + weights[1]) {
            return "A";  // Select A
        }
        cumulativeWeight += weights[1];
        return "B";  // Select B
    }
}
