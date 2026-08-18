package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.inventory.BodyPart;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;
import org.gms.server.life.NPC;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Bot 游戏逻辑工具（参考 SoloMapling 的 BotLogic 1:1 移植）。
 * <p>
 * 底座差异：MovementStructures.MovementEnums 已移植（org.gms.server.bot.replay.MovementEnums），
 * 自由市场房间区间此处保留本地常量；无 DebugUtilities，改用 slf4j；装备槽位经
 * {@link BodyPart} 映射到 gms 负槽位。
 */
@Slf4j
public final class BotLogic {

    // 源自 SoloMapling MovementStructures.MovementEnums.FreeMarketValues（MovementEnums 已移植，
    // 见 org.gms.server.bot.replay.MovementEnums；此处保留本地常量）。
    private static final int FM_ENTRANCE = 910000000;
    private static final int FM_ROOM_1 = 910000001;
    private static final int FM_ROOM_22 = 910000022;

    private BotLogic() {
    }

    public static Character waitForPlayerInRange(Character fakechar, int width, int height) {
        Rectangle rect = BotHelpers.createRectangle(fakechar.getPosition(), width, height);
        List<MapObject> playersInRange = getPlayersInRange(fakechar, rect);
        return findFirstPlayerInRange(fakechar, playersInRange);
    }

    // Helper method to get all players in the rectangle
    private static List<MapObject> getPlayersInRange(Character fakeChar, Rectangle rect) {
        return fakeChar.getMap().getMapObjectsInRect(rect, List.of(MapObjectType.PLAYER));
    }

    // Helper method to find the first valid player in the range
    private static Character findFirstPlayerInRange(Character fakeChar, List<MapObject> playersInRange) {
        for (MapObject obj : playersInRange) {
            if (obj instanceof Character player) {
                if (player.getId() != fakeChar.getId()) {
                    return player;
                }
            }
        }
        return null; // No valid player found
    }

    public static List<MapObject> checkForItemsOnFloor(Character chr, Point location, int[] itemIds) {
        return checkForItemsOnFloor(chr, location, 5000, itemIds);
    }

    public static List<MapObject> checkForItemsOnFloor(Character chr, Point location, double range, int[] itemIds) {
        // Return list of items on floor that matches itemId
        List<MapObject> itemsOnFloor = readItemOnFloor(chr, location, range);
        return filterDroppedItems(itemsOnFloor, itemIds);
    }

    public static List<MapObject> checkForItemOnFloor(Character chr, Point location, int itemId) {
        int[] itemToCheck = {itemId};
        return checkForItemsOnFloor(chr, location, itemToCheck);
    }

    public static List<MapObject> readItemOnFloor(Character character, Point location, double range) {
        // 源内另有逐项读取 itemId/名称/坐标的死代码（无副作用），此处仅返回空间查询结果。
        return character.getMap().getMapObjectsInRange(location, range, Arrays.asList(MapObjectType.ITEM));
    }

    public static List<MapObject> filterDroppedItems(List<MapObject> items, int[] filterList) {
        // Returns list of items on Floor that ONLY contains items in filterList
        Iterator<MapObject> iterator = items.iterator();
        List<Integer> itemFilterList = Arrays.stream(filterList).boxed().collect(Collectors.toList());
        while (iterator.hasNext()) {
            MapItem mapItem = (MapItem) iterator.next();
            if (!itemFilterList.contains(mapItem.getItemId())) {
                iterator.remove();
            }
        }
        return items;
    }

    public static List<MapObject> filterPlayersDroppedItems(List<MapObject> items, Character player) {
        // Filter out items that do not belong to the player
        Iterator<MapObject> iterator = items.iterator();
        while (iterator.hasNext()) {
            MapItem mapItem = (MapItem) iterator.next();
            if (mapItem.getOwnerId() != player.getId()) {
                iterator.remove();
            }
        }
        return items;
    }

    public static boolean checkIfItemsOnFloorStill(List<MapObject> itemsToCheck, List<MapObject> itemsAtPlayer) {
        return BotHelpers.checkSecondListInsideFirstList(itemsAtPlayer, itemsToCheck);
    }

    public static boolean isNpcPresent(MapleMap map, int npcId) {
        for (MapObject mmo : map.getMapObjects()) {
            if (mmo instanceof NPC npc && npc.getId() == npcId) {
                return true;
            }
        }
        return false;
    }

    public static Item generateCleanItemEquip(int id) {
        Item cleanItem;
        if (ItemConstants.getInventoryType(id) == InventoryType.EQUIP) {
            cleanItem = generateCleanEquip(id);
        } else {
            cleanItem = generateCleanItem(id);
        }
        return cleanItem;
    }

    /**
     * Builds a clean equip for {@code itemId}. The returned item is never null: a bare
     * {@link Equip} is produced even when the WZ has no stats for the id.
     */
    public static Item generateCleanEquip(int itemId) {
        return ItemInformationProvider.getInstance().getEquipById(itemId);
    }

    public static Item generateCleanItem(int itemId) {
        return generateCleanItemWithQty(itemId, 1);
    }

    public static Item generateCleanItemWithQty(int itemId, int qty) {
        return new Item(itemId, (short) 0, (short) qty);
    }

    public static Equip readPlayerEquipBySlotName(Character fakechar, BodyPart bodyPartName) {
        return readPlayerEquipBySlotName(fakechar, bodyPartName, false);
    }

    public static Equip readPlayerCashEquipBySlotName(Character fakechar, BodyPart bodyPartName) {
        return readPlayerEquipBySlotName(fakechar, bodyPartName, true);
    }

    public static Equip readPlayerEquipBySlotName(Character fakechar, BodyPart bodyPartName, boolean cash) {
        short dst = getSlotDstValueByEquipSlotName(bodyPartName, cash);
        return readPlayerEquipBySlotId(fakechar, dst);
    }

    public static Equip readPlayerEquipBySlotId(Character fakechar, short slot) {
        Inventory equipped = fakechar.getInventory(InventoryType.EQUIPPED);
        if (equipped == null) {
            log.debug("BotLogic.readPlayerEquipBySlotId: no equipped inventory for bot {}",
                    fakechar != null ? fakechar.getId() : "null");
            return null;
        }
        return (Equip) equipped.getItem(slot);
    }

    public static short getSlotDstValueByEquipSlotName(BodyPart eqSlot, boolean cash) {
        if (cash) {
            return (short) -(eqSlot.getValue() + BodyPart.CASH_BASE.getValue());
        }
        return (short) -eqSlot.getValue();
    }

    public static HashMap<String, Integer> makeItemIdFromNameMap(int[] items) {
        HashMap<String, Integer> itemNameToIdMap = new HashMap<>();
        for (int itemId : items) {
            String itemName = BotHelpers.convertItemIdToName(itemId).toLowerCase();
            itemNameToIdMap.put(itemName, itemId);
        }
        return itemNameToIdMap;
    }

    /**
     * Returns real (non-bot) players within a rectangle around the bot.
     * Lightweight — single spatial query + id filter.
     */
    public static List<Character> getRealPlayersInRange(Character fakechar, int width, int height) {
        Rectangle rect = BotHelpers.createRectangle(fakechar.getPosition(), width, height);
        List<MapObject> objects = getPlayersInRange(fakechar, rect);
        List<Character> realPlayers = new ArrayList<>();
        for (MapObject obj : objects) {
            if (obj instanceof Character player) {
                if (player.getId() != fakechar.getId() && !BotHelpers.isBot(player)) {
                    log.debug("Detected real player: {}", player.getName());
                    realPlayers.add(player);
                }
            }
        }
        return realPlayers;
    }

    public static boolean isCharNear(Point p1, Point p2) {
        return isPointNear(p1, p2, 25);
    }

    // isNear()
    public static boolean isPointNear(Point p1, Point p2, double maxDistance) {
        double distance = p1.distance(p2);
        return distance <= maxDistance;
    }

    // To ensure same platform
    public static boolean isPointNearSameY(Point p1, Point p2, double maxXDistance, int yThreshold) {
        if (Math.abs(p1.y - p2.y) > yThreshold) {
            return false;
        }
        return Math.abs(p1.x - p2.x) <= maxXDistance;
    }

    public static Point getFurthestPointFromOrigin(Point basePoint, List<Point> pts) {
        if (pts == null || pts.isEmpty()) {
            return null; // Return null if the list is empty or null
        }
        Point furthestPoint = null;
        double maxDistance = -1;
        for (Point p : pts) {
            double distance = basePoint.distanceSq(p); // Using squared distance to avoid unnecessary sqrt computation
            if (distance > maxDistance) {
                maxDistance = distance;
                furthestPoint = p;
            }
        }
        return furthestPoint;
    }

    public static boolean isWithinPriceRange(double currentPrice, double marketPrice) {
        return isWithinPriceRange(currentPrice, marketPrice, 0.80); // Default 80% tolerance
    }

    public static boolean isWithinPriceRange(double currentPrice, double marketPrice, double tolerancePercentage) {
        if (tolerancePercentage < 0 || tolerancePercentage > 1) {
            throw new IllegalArgumentException("Tolerance percentage must be between 0 and 1");
        }
        double lowerBound = marketPrice * (1 - tolerancePercentage);
        double upperBound = marketPrice * (1 + tolerancePercentage);
        return currentPrice >= lowerBound && currentPrice <= upperBound;
    }

    public static boolean isInsideFMRooms(Character fakechar) {
        int map = fakechar.getMapId();
        return map >= FM_ROOM_1 && map <= FM_ROOM_22;
    }

    public static boolean isAtFMEntrance(Character fakechar) {
        return fakechar.getMapId() == FM_ENTRANCE;
    }

    public static boolean isInsideFM(Character fakechar) {
        return isInsideFMRooms(fakechar) || isAtFMEntrance(fakechar);
    }

    // 以下为 casino bot 专用（todo refactor to generic，源内保留，逐行移植）

    public static StringBuilder cleanUpBetsString(List<MapObject> items, StringBuilder betString) {
        // Clean up string for Casino Stamps
        for (MapObject item : items) {
            MapItem mapItem = (MapItem) item;
            int mapItemId = mapItem.getItemId();
            int qty = mapItem.getItem().getQuantity();
            String itemName = BotHelpers.convertItemIdToName(mapItemId);
            betString.append(qty).append("x ").append(itemName).append(", ");
        }
        betString = new StringBuilder(betString.toString().replace("'s Stamp", ""));
        betString = new StringBuilder(betString.toString().replace(" Stamp", ""));
        betString = new StringBuilder(betString.toString().replace(" the Really Old", ""));
        betString = new StringBuilder(betString.toString().replace(" Pierce", ""));
        return betString;
    }

    public static String announceBetString(Character better, List<MapObject> items) {
        StringBuilder betString = new StringBuilder();
        betString.append(better.getName()).append("'s Bet: ");
        betString = cleanUpBetsString(items, betString);
        return betString.toString();
    }

    public static List<MapObject> readPlayersBetsStamps(Character player, double range) {
        return readPlayersBetsStamps(player, player.getPosition(), range);
    }

    public static List<MapObject> readPlayersBetsStamps(Character player, Point location, double range) {
        List<MapObject> items = readItemOnFloor(player, location, range);
        items = filterPlayersDroppedItems(items, player);
        int[] stamps = {4002000, 4002001, 4002002, 4002003, 4031558, 4031559, 4031560, 4031561};
        items = filterDroppedItems(items, stamps);
        return items;
    }
}
