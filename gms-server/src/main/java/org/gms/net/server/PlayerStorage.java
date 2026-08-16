/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.gms.net.server;

import org.gms.client.Character;
import org.gms.client.Client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PlayerStorage {
    private final Map<Integer, Character> storage = new LinkedHashMap<>();
    private final Map<String, Character> nameStorage = new LinkedHashMap<>();
    private final Lock rlock;
    private final Lock wlock;
    // bot 会话计数（Client.isBot() 为真）：容量统计需排除 bot（loadenv 数千 bot 会占满 channel_capacity）
    private int botCount;

    public PlayerStorage() {
        ReadWriteLock readWriteLock = new ReentrantReadWriteLock(true);
        this.rlock = readWriteLock.readLock();
        this.wlock = readWriteLock.writeLock();
    }

    public void addPlayer(Character chr) {
        wlock.lock();
        try {
            Character previous = storage.put(chr.getId(), chr);
            if (previous != null) {
                // 同 id 重复注册（防御路径）：先按被覆盖旧角色的类型回退计数，避免 botCount 漂移
                nameStorage.remove(previous.getName().toLowerCase());
                Client previousClient = previous.getClient();
                if (previousClient != null && previousClient.isBot()) {
                    botCount--;
                }
            }
            nameStorage.put(chr.getName().toLowerCase(), chr);
            Client client = chr.getClient();
            if (client != null && client.isBot()) {
                botCount++;
            }
        } finally {
            wlock.unlock();
        }
    }

    public Character removePlayer(int chr) {
        wlock.lock();
        try {
            Character mc = storage.remove(chr);
            if (mc != null) {
                nameStorage.remove(mc.getName().toLowerCase());
                Client client = mc.getClient();
                if (client != null && client.isBot()) {
                    botCount--;
                }
            }

            return mc;
        } finally {
            wlock.unlock();
        }
    }

    public Character getCharacterByName(String name) {
        if (name == null) {
            return null;    // 防御：bot 摊位等合成对象可能带 null 名字（如 HiredMerchant 的 ownerName）
        }
        rlock.lock();
        try {
            return nameStorage.get(name.toLowerCase());
        } finally {
            rlock.unlock();
        }
    }

    public Character getCharacterById(int id) {
        rlock.lock();
        try {
            return storage.get(id);
        } finally {
            rlock.unlock();
        }
    }

    public Collection<Character> getAllCharacters() {
        rlock.lock();
        try {
            return new ArrayList<>(storage.values());
        } finally {
            rlock.unlock();
        }
    }

    public final void disconnectAll() {
        List<Character> chrList;
        rlock.lock();
        try {
            chrList = new ArrayList<>(storage.values());
        } finally {
            rlock.unlock();
        }

        for (Character mc : chrList) {
            Client client = mc.getClient();
            if (client != null) {
                client.forceDisconnect();
            }
        }

        wlock.lock();
        try {
            storage.clear();
            nameStorage.clear(); // 与 storage 对称清空，避免残留幽灵名字（getCharacterByName/isNameTaken 污染）
            botCount = 0;
        } finally {
            wlock.unlock();
        }
    }

    public int getSize() {
        rlock.lock();
        try {
            return storage.size();
        } finally {
            rlock.unlock();
        }
    }

    /** 当前存储中的 bot 会话数（Client.isBot() 为真）。 */
    public int getBotCount() {
        rlock.lock();
        try {
            return botCount;
        } finally {
            rlock.unlock();
        }
    }

    /** 非 bot 在线数（单锁原子快照）：容量统计专用，避免 size 与 botCount 两次读之间被写入穿插。 */
    public int getNonBotSize() {
        rlock.lock();
        try {
            return storage.size() - botCount;
        } finally {
            rlock.unlock();
        }
    }
}
