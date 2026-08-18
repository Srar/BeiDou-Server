/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 ~ 2010 Patrick Huy <patrick.huy@frz.cc>
                       Matthias Butz <matze@odinms.de>
                       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License version 3
    as published by the Free Software Foundation. You may not use, modify
    or distribute this program under any other version of the
    GNU Affero General Public License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.gms.server.maps;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Item;
import org.gms.util.PacketCreator;

import java.awt.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.SECONDS;

public class MapItem extends AbstractMapObject {
    protected Client ownerClient;
    protected Item item;
    protected MapObject dropper;
    protected int character_ownerid, party_ownerid, meso, questid = -1;
    protected byte type;
    protected boolean pickedUp = false, playerDrop, partyDrop;
    // 永久所有权标志(移植自 SoloMapling MapItem):置位后 15s 所有权到期不再把
    // 掉落转为 FFA,非 owner/party 永远不能拾取(黑杰克发牌、drop game 赌注等
    // owner-only 掉落使用)。仅 bot 掉落路径在 spawn 后 set,真实玩家掉落默认 false。
    // volatile 是因为 setPermanentOwner 在物品注册进地图后由 bot 线程锁外写入,
    // 拾取判定(锁内 canBePickedBy / 锁外 hasClientsideOwnership)要保证可见性。
    protected volatile boolean permanentOwner;
    protected long dropTime;
    private final Lock itemLock = new ReentrantLock();

    public MapItem(Item item, Point position, MapObject dropper, Character owner, Client ownerClient, byte type, boolean playerDrop) {
        setPosition(position);
        this.item = item;
        this.dropper = dropper;
        this.character_ownerid = owner.getId();
        this.party_ownerid = owner.getPartyId();
        this.partyDrop = this.party_ownerid != -1;
        this.ownerClient = owner.getClient();
        this.meso = 0;
        this.type = type;
        this.playerDrop = playerDrop;
    }

    public MapItem(Item item, Point position, MapObject dropper, Character owner, Client ownerClient, byte type, boolean playerDrop, int questid) {
        setPosition(position);
        this.item = item;
        this.dropper = dropper;
        this.character_ownerid = owner.getId();
        this.party_ownerid = owner.getPartyId();
        this.partyDrop = this.party_ownerid != -1;
        this.ownerClient = owner.getClient();
        this.meso = 0;
        this.type = type;
        this.playerDrop = playerDrop;
        this.questid = questid;
    }

    public MapItem(int meso, Point position, MapObject dropper, Character owner, Client ownerClient, byte type, boolean playerDrop) {
        setPosition(position);
        this.item = null;
        this.dropper = dropper;
        this.character_ownerid = owner.getId();
        this.party_ownerid = owner.getPartyId();
        this.partyDrop = this.party_ownerid != -1;
        this.ownerClient = owner.getClient();
        this.meso = meso;
        this.type = type;
        this.playerDrop = playerDrop;
    }

    public final Item getItem() {
        return item;
    }

    public final int getQuest() {
        return questid;
    }

    public final int getItemId() {
        if (meso > 0) {
            return meso;
        }
        return item.getItemId();
    }

    public final MapObject getDropper() {
        return dropper;
    }

    public final int getOwnerId() {
        return character_ownerid;
    }

    /**
     * 与 {@link #getOwnerId()} 语义相同,但要求调用方已持有 {@link #itemLock}。
     * {@code character_ownerid} 在构造后即不可变,保留该锁定读是为了与
     * {@link #getPartyOwnerIdLocked()} 在遍历/扫描代码里保持一致的锁纪律。
     */
    public final int getOwnerIdLocked() {
        return character_ownerid;
    }

    /**
     * {@code party_ownerid} 是运行时可变字段,调用方必须持有 {@link #itemLock}
     * 再读取,否则与持锁写路径({@link #setPartyOwnerIdLocked(int)}、
     * {@link #canBePickedBy(Character)})并发时可能读到撕裂值。
     */
    public final int getPartyOwnerId() {
        return party_ownerid;
    }

    /**
     * 与 {@link #getPartyOwnerId()} 语义相同,但要求调用方已持有 {@link #itemLock}。
     */
    public final int getPartyOwnerIdLocked() {
        return party_ownerid;
    }

    /**
     * 与 {@link #setPartyOwnerId(int)} 语义相同,但要求调用方已持有 {@link #itemLock}。
     */
    public final void setPartyOwnerId(int partyid) {
        party_ownerid = partyid;
    }

    /**
     * 与 {@link #setPartyOwnerId(int)} 语义相同,但要求调用方已持有 {@link #itemLock}。
     * 配合 {@link #getOwnerIdLocked()}/{@link #getPartyOwnerIdLocked()} 在持锁
     * 路径上完成 owner 字段的读+写,避免锁外读写撕裂。
     */
    public final void setPartyOwnerIdLocked(int partyid) {
        party_ownerid = partyid;
    }

    public final int getClientsideOwnerId() {   // thanks nozphex (RedHat) for noting an issue with collecting party items
        if (this.party_ownerid == -1) {
            return this.character_ownerid;
        } else {
            return this.party_ownerid;
        }
    }

    public final boolean hasClientsideOwnership(Character player) {
        return this.character_ownerid == player.getId() || this.party_ownerid == player.getPartyId() || (!permanentOwner && hasExpiredOwnershipTime());
    }

    public final boolean isFFADrop() {
        // permanentOwner 掉落永不转 FFA(源 SoloMapling 语义一致)
        if (permanentOwner) {
            return false;
        }
        return type == 2 || type == 3 || hasExpiredOwnershipTime();
    }

    public final boolean hasExpiredOwnershipTime() {
        return System.currentTimeMillis() - dropTime >= SECONDS.toMillis(15);
    }

    /**
     * 判断 {@code chr} 是否可以拾取本掉落物。
     *
     * <p><b>并发约束:</b>本方法会在内部读取并可能改写 {@code character_ownerid} /
     * {@code party_ownerid} 实例字段,调用方必须已持有 {@link #itemLock}
     * (即先 {@code mdrop.lockItem()},再调用本方法),否则与其他持锁的
     * 读/写路径(例如 {@code MapleMap.updatePlayerItemDropsToParty})并发时
     * 会出现字段撕裂。所有调用方已调整为先加锁再调用。
     */
    public final boolean canBePickedBy(Character chr) {
        if (character_ownerid <= 0 || isFFADrop()) {
            return true;
        }

        if (party_ownerid == -1) {
            if (chr.getId() == character_ownerid) {
                return true;
            } else if (chr.isPartyMember(character_ownerid)) {
                party_ownerid = chr.getPartyId();
                return true;
            }
        } else {
            if (chr.getPartyId() == party_ownerid) {
                return true;
            } else if (chr.getId() == character_ownerid) {
                party_ownerid = chr.getPartyId();
                return true;
            }
        }

        // permanentOwner 置位时,即使 15s 到期也不向非 owner/party 开放(源 SoloMapling 语义)
        return !permanentOwner && hasExpiredOwnershipTime();
    }

    /**
     * 永久所有权标志(移植自 SoloMapling):置位后本掉落永不因 15s 到期转为 FFA,
     * 非 owner/party 永远无法拾取。仅供 bot 掉落路径(黑杰克发牌、drop game 赌注等
     * owner-only 掉落)在物品生成后立即调用;真实玩家掉落不设置,行为与 vanilla 一致。
     */
    public void setPermanentOwner(boolean permanentOwner) {
        this.permanentOwner = permanentOwner;
    }

    /**
     * 读取永久所有权标志(volatile 读,可在 itemLock 之外安全使用)。
     */
    public boolean isPermanentOwner() {
        return permanentOwner;
    }

    public final Client getOwnerClient() {
        if (ownerClient == null || ownerClient.getPlayer() == null) {
            return null;
        }
        return (ownerClient.isLoggedIn() && !ownerClient.getPlayer().isAwayFromWorld()) ? ownerClient : null;
    }

    public final int getMeso() {
        return meso;
    }

    public final boolean isPlayerDrop() {
        return playerDrop;
    }

    public final boolean isPickedUp() {
        return pickedUp;
    }

    public void setPickedUp(final boolean pickedUp) {
        this.pickedUp = pickedUp;
    }

    public long getDropTime() {
        return dropTime;
    }

    public void setDropTime(long time) {
        this.dropTime = time;
    }

    public byte getDropType() {
        return type;
    }

    public void lockItem() {
        itemLock.lock();
    }

    public void unlockItem() {
        itemLock.unlock();
    }

    @Override
    public final MapObjectType getType() {
        return MapObjectType.ITEM;
    }

    @Override
    public void sendSpawnData(final Client client) {
        Character chr = client.getPlayer();

        if (chr.needQuestItem(questid, getItemId())) {
            this.lockItem();
            try {
                client.sendPacket(PacketCreator.dropItemFromMapObject(chr, this, null, getPosition(), (byte) 2));
            } finally {
                this.unlockItem();
            }
        }
    }

    @Override
    public void sendDestroyData(final Client client) {
        client.sendPacket(PacketCreator.removeItemFromMap(getObjectId(), 1, 0));
    }
}