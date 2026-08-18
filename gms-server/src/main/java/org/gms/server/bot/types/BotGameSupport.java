package org.gms.server.bot.types;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.server.bot.BotExecutors;
import org.gms.server.bot.BotLogic;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;

import java.awt.Point;
import java.util.Arrays;
import java.util.List;

/**
 * 小游戏/场景类 bot 共用的社交与掉落原语。
 *
 * <p>SoloMapling 的 SocialCommands / DropCommands / WarpCommands 已随 commands 包
 * 一并移植（org.gms.server.bot.commands）；本类按源实现语义内联等价实现
 * （PacketCreator + MapleMap 底层掉落 API），可择机改调 commands 包以消除重复。</p>
 */
public final class BotGameSupport {

    private BotGameSupport() {
    }

    // ── 社交原语（等价 SocialCommands） ──────────────────────────────────────

    /** 等价 SocialCommands.BotSpeak → BotFullChat：普通聊天广播。 */
    public static void botSpeak(Character chr, String message) {
        if (chr == null || chr.getMap() == null) {
            return;
        }
        chr.getMap().broadcastMessage(PacketCreator.getChatText(chr.getId(), message, chr.getWhiteChat(), 0));
    }

    /** 等价 SocialCommands.BotChatbubble：只出气泡、不进聊天框。 */
    public static void botChatbubble(Character chr, String message) {
        if (chr == null || chr.getMap() == null) {
            return;
        }
        chr.getMap().broadcastMessage(PacketCreator.getChatText(chr.getId(), message, false, 1));
    }

    /** 等价 SocialCommands.BotEmote(chr, emote)：表情广播。 */
    public static void botEmote(Character chr, int emote) {
        if (chr == null || chr.getMap() == null) {
            return;
        }
        chr.getMap().broadcastMessage(PacketCreator.facialExpression(chr, emote));
    }

    /** 等价 SocialCommands.displayPlayerChatCommands(chr, commands)：编号菜单提示气泡。 */
    public static void displayPlayerChatCommands(Character chr, List<String> commands) {
        if (chr == null || chr.getClient() == null) {
            return;
        }
        StringBuilder msg = new StringBuilder();
        int maxWidth = 0;
        for (String command : commands) {
            maxWidth = Math.max(maxWidth, command.length());
        }
        for (int i = 0; i < commands.size(); i++) {
            msg.append(i + 1).append(". ").append(commands.get(i)).append("\r\n");
        }
        int width = maxWidth < 5 ? 60 : maxWidth * 10;
        chr.getClient().sendPacket(PacketCreator.sendHint(msg.toString(), width, 25));
        chr.getClient().sendPacket(PacketCreator.enableActions());
    }

    // ── 掉落/投掷/拾取原语（等价 DropCommands） ───────────────────────────────

    /** 等价 DropCommands.botThrowItem：投到指定位置，所有人可拾取（pet 可拾取）。 */
    public static void botThrowItem(Character chr, int itemId, Point pos) {
        if (chr == null || chr.getMap() == null) {
            return;
        }
        Item itemToDrop = BotLogic.generateCleanItem(itemId);
        chr.getMap().spawnItemDrop(chr, chr, itemToDrop, pos, true, false);
    }

    /** 等价 DropCommands.botThrowItemQty。 */
    public static void botThrowItemQty(Character chr, int itemId, int qty, Point pos) {
        if (chr == null || chr.getMap() == null) {
            return;
        }
        Item itemToDrop = BotLogic.generateCleanItemWithQty(itemId, qty);
        chr.getMap().spawnItemDrop(chr, chr, itemToDrop, pos, true, false);
    }

    /** 等价 DropCommands.botThrowItemNoExpireOwnerOnly：不消失 + 仅拥有者可拾取。 */
    public static MapItem botThrowItemNoExpireOwnerOnly(Character chr, int itemId, Point pos) {
        if (chr == null || chr.getMap() == null) {
            return null;
        }
        Item itemToDrop = BotLogic.generateCleanItem(itemId);
        // gms MapItem 无 setPermanentOwner；用 dropType=0（拥有者保护）近似，TODO(移植) 见源。
        return chr.getMap().spawnItemDropNoExpire(chr, chr, itemToDrop, pos, false, false);
    }

    /** 等价 DropCommands.botThrowItemToOwner：投给指定拥有者，仅其可拾取。 */
    public static void botThrowItemToOwner(Character dropper, int itemId, Point pos, Character owner) {
        if (dropper == null || dropper.getMap() == null) {
            return;
        }
        Item itemToDrop = BotLogic.generateCleanItem(itemId);
        dropper.getMap().spawnItemDropNoExpire(dropper, owner, itemToDrop, pos, false, false);
    }

    /** 等价 DropCommands.botDropItemQtyOwnerOnly：脚下掉落，仅拥有者可拾取。 */
    public static void botDropItemQtyOwnerOnly(Character chr, int itemId, int qty) {
        if (chr == null || chr.getMap() == null) {
            return;
        }
        Item itemToDrop = BotLogic.generateCleanItemWithQty(itemId, qty);
        chr.getMap().spawnItemDropNoExpire(chr, chr, itemToDrop, chr.getPosition(), false, false);
    }

    /** 等价 DropCommands.botDropItemWithExpiry：不消失掉落 + 到期后移除。 */
    public static MapItem botDropItemWithExpiry(Character chr, int itemId, boolean isEquip, long expiryMs) {
        if (chr == null || chr.getMap() == null) {
            return null;
        }
        Item itemToDrop = isEquip ? BotLogic.generateCleanItemEquip(itemId) : BotLogic.generateCleanItem(itemId);
        MapItem drop = chr.getMap().spawnItemDropNoExpire(chr, chr, itemToDrop, chr.getPosition(), true, true);
        if (drop != null && expiryMs > 0) {
            BotExecutors.schedule(() -> {
                if (drop.isPickedUp()) {
                    return;
                }
                MapleMap map = chr.getMap();
                if (map != null) {
                    map.broadcastMessage(PacketCreator.removeItemFromMap(drop.getObjectId(), 0, 0), drop.getPosition());
                }
            }, expiryMs);
            BotExecutors.schedule(() -> {
                MapleMap map = chr.getMap();
                if (map != null) {
                    map.makeDisappearItemFromMap(drop);
                }
            }, expiryMs + 3000);
        }
        return drop;
    }

    /** 等价 DropCommands.botLootOwnerItems：拾取范围内属于指定 bot 的物品。 */
    public static void botLootOwnerItems(Character chr, Point pos, double range) {
        if (chr == null || chr.getMap() == null) {
            return;
        }
        List<MapObject> items = chr.getMap().getMapObjectsInRange(pos, range, Arrays.asList(MapObjectType.ITEM));
        for (MapObject item : items) {
            MapItem mapItem = (MapItem) item;
            if (mapItem.getOwnerId() == chr.getId()) {
                chr.getMap().pickItemDrop(PacketCreator.removeItemFromMap(mapItem.getObjectId(), 2, chr.getId()), mapItem);
            }
        }
    }

    /** 等价 DropCommands.lootItemListOnFloor：逐个拾取地板物品列表。 */
    public static void lootItemListOnFloor(Character chr, List<MapObject> items) {
        if (chr == null || chr.getMap() == null || items == null) {
            return;
        }
        for (MapObject item : items) {
            MapItem mapItem = (MapItem) item;
            chr.getMap().pickItemDrop(PacketCreator.removeItemFromMap(mapItem.getObjectId(), 2, chr.getId()), mapItem);
        }
    }

    // ── 通用工具（等价 SoloMapling BotHelpers / 编排原语） ────────────────────

    /** 等价 SoloMapling BotHelpers.adjustCenterPositionXAxis：掉落喷洒的 X 轴偏移。 */
    public static Point adjustCenterPositionXAxis(Point center, int currIndex, int initialIncrement, int subsequentIncrement, int offset) {
        // initialIncrement = How many units it will go left
        // SubsequentIncrement = How many units it will go right (Usually 2x initial Increment for an even "spread"
        // Offset = how much space between each item
        if (currIndex < initialIncrement) {
            center.x += offset;
        } else {
            int adjustedIndex = currIndex - initialIncrement;
            int cycle = (adjustedIndex / subsequentIncrement) % 2;

            if (cycle == 0) {
                if (adjustedIndex % subsequentIncrement < subsequentIncrement) {
                    center.x -= offset;
                }
            } else {
                if (adjustedIndex % subsequentIncrement < subsequentIncrement) {
                    center.x += offset;
                }
            }
        }
        return center;
    }

    /** 等价 SoloMapling BotHelpers.blockingSleep：刻意阻塞当前线程（编排节拍）。 */
    public static void blockingSleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
