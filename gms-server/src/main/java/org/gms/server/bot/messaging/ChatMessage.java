package org.gms.server.bot.messaging;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;

/**
 * 聊天消息值对象（逐行移植自 SoloMapling BotMessagingSystem.ChatMessage）。
 * sender/content/timestamp 为值字段，map 在构造时从 sender 捕获。
 */
public class ChatMessage {
    private final Character sender;
    private final String content;
    private MapleMap map;
    private final long timestamp;

    public ChatMessage(Character sender, String content) {
        this.sender = sender;
        this.content = content;
        this.map = this.sender.getMap();
        this.timestamp = System.currentTimeMillis();
    }

    public Character getSender() {
        return sender;
    }

    public String getContent() {
        return content;
    }

    protected MapleMap getMap() {
        return map;
    }

    protected long getTimestamp() {
        return timestamp;
    }
}
