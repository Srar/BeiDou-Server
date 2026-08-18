package org.gms.server.bot.commands;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MapItem#setPermanentOwner} 所有权保护语义 + {@link DropCommands} 三个
 * owner-only 掉落入口置位回归测试（移植自 SoloMapling permanentOwner 语义）。
 *
 * <p>permanentOwner 仅 bot 掉落路径在物品生成后置位；真实玩家掉落默认 false，
 * 所有权到期（vanilla 15s 转 FFA）行为不变——本类同时覆盖该无副作用断言。</p>
 */
class DropCommandsPermanentOwnerTest {

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    private static Character mockCharacter(int id) {
        return mockCharacter(id, -1);
    }

    private static Character mockCharacter(int id, int partyId) {
        Character chr = Mockito.mock(Character.class);
        Mockito.when(chr.getId()).thenReturn(id);
        Mockito.when(chr.getPartyId()).thenReturn(partyId);
        Mockito.when(chr.isPartyMember(Mockito.anyInt())).thenReturn(false);
        return chr;
    }

    private static MapItem newDrop(Character owner, long ageMs) {
        Item item = new Item(4000000, (short) 0, (short) 1);
        MapObject dropper = Mockito.mock(MapObject.class);
        MapItem drop = new MapItem(item, new Point(0, 0), dropper, owner, null, (byte) 0, false);
        drop.setDropTime(System.currentTimeMillis() - ageMs);
        return drop;
    }

    private static boolean canBePickedBy(MapItem drop, Character chr) {
        drop.lockItem();
        try {
            return drop.canBePickedBy(chr);
        } finally {
            drop.unlockItem();
        }
    }

    // ── permanentOwner 所有权到期判定 ────────────────────────────────────────

    @Test
    void vanillaDropBecomesFfaAfterExpiry() {
        // owner 属于队伍 100、stranger 无队伍：避免两方 partyId 同为 -1 时
        // hasClientsideOwnership 的 party_ownerid(-1)==player.getPartyId()(-1)
        // 恒真分支（OdinMS 原版语义）干扰断言。
        Character owner = mockCharacter(100, 100);
        Character other = mockCharacter(200);
        MapItem drop = newDrop(owner, 20_000); // 20s > vanilla 15s 到期窗口

        // 无 permanentOwner 时,到期后非 owner 可拾取（vanilla FFA 行为不变）。
        assertTrue(canBePickedBy(drop, other), "expired vanilla drop must be FFA");
        assertTrue(drop.isFFADrop(), "expired vanilla drop must report FFA");
        assertTrue(drop.hasClientsideOwnership(other), "expired vanilla drop has clientside ownership for anyone");
    }

    @Test
    void permanentOwnerKeepsOwnershipAfterExpiry() {
        Character owner = mockCharacter(100, 100);
        Character other = mockCharacter(200);
        MapItem drop = newDrop(owner, 20_000); // 20s > vanilla 15s 到期窗口
        drop.setPermanentOwner(true);

        assertTrue(drop.isPermanentOwner(), "flag must be set");
        // 到期后:非 owner/party 仍不可拾取,不转 FFA。
        assertFalse(canBePickedBy(drop, other), "permanent-owner drop must not open to strangers after expiry");
        assertFalse(drop.isFFADrop(), "permanent-owner drop must never report FFA");
        assertFalse(drop.hasClientsideOwnership(other), "stranger has no clientside ownership on permanent-owner drop");
        // owner 自身照常可拾取。
        assertTrue(canBePickedBy(drop, owner), "owner must still pick their own permanent-owner drop");
        assertTrue(drop.hasClientsideOwnership(owner), "owner keeps clientside ownership");
    }

    @Test
    void permanentOwnerProtectsEvenBeforeExpiry() {
        Character owner = mockCharacter(100, 100);
        Character other = mockCharacter(200);
        MapItem drop = newDrop(owner, 5_000); // 5s,尚未到期
        drop.setPermanentOwner(true);

        assertFalse(canBePickedBy(drop, other), "stranger must not pick a fresh permanent-owner drop");
        assertTrue(canBePickedBy(drop, owner), "owner must pick their own fresh drop");
    }

    // ── DropCommands 三处 owner-only 入口置位 ───────────────────────────────

    @Test
    void botThrowItemNoExpireOwnerOnlySetsPermanentOwner() {
        Character bot = mockCharacter(100);
        Point pos = new Point(10, 20);
        MapleMap map = Mockito.mock(MapleMap.class);
        Mockito.when(bot.getMap()).thenReturn(map);

        MapItem realDrop = newDrop(bot, 0);
        Mockito.when(map.spawnItemDropNoExpire(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.anyBoolean(), Mockito.anyBoolean()))
                .thenReturn(realDrop);

        MapItem drop = DropCommands.botThrowItemNoExpireOwnerOnly(bot, 4000000, pos);

        assertSame(realDrop, drop, "drop must come from the map spawn call");
        assertTrue(drop.isPermanentOwner(), "owner-only no-expire throw must set permanentOwner");
    }

    @Test
    void botDropItemQtyOwnerOnlySetsPermanentOwner() {
        Character bot = mockCharacter(100);
        MapleMap map = Mockito.mock(MapleMap.class);
        Mockito.when(bot.getMap()).thenReturn(map);
        Mockito.when(bot.getPosition()).thenReturn(new Point(5, 5));

        MapItem realDrop = newDrop(bot, 0);
        Mockito.when(map.spawnItemDropNoExpire(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.anyBoolean(), Mockito.anyBoolean()))
                .thenReturn(realDrop);

        DropCommands.botDropItemQtyOwnerOnly(bot, 4000000, 10);

        assertTrue(realDrop.isPermanentOwner(), "owner-only qty drop must set permanentOwner");
    }

    @Test
    void botThrowItemToOwnerSetsPermanentOwner() {
        Character dropper = mockCharacter(100);
        Character owner = mockCharacter(200);
        Point pos = new Point(30, 40);
        MapleMap map = Mockito.mock(MapleMap.class);
        Mockito.when(dropper.getMap()).thenReturn(map);

        MapItem realDrop = newDrop(owner, 0);
        Mockito.when(map.spawnItemDropNoExpire(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.anyBoolean(), Mockito.anyBoolean()))
                .thenReturn(realDrop);

        DropCommands.botThrowItemToOwner(dropper, 4000000, pos, owner);

        assertTrue(realDrop.isPermanentOwner(), "throw-to-owner must set permanentOwner");
    }
}
