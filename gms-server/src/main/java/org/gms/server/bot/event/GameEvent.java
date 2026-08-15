package org.gms.server.bot.event;

/**
 * 游戏事件值对象：世界 / 频道 / 地图定位 + 事件类型 + 可选的载荷
 * （聊天事件携带说话者角色 ID 与内容）。
 */
public final class GameEvent {

    private final EventType type;
    private final int world;
    private final int channel;
    private final int mapId;
    private final int sourceCharacterId;
    private final String text;

    private GameEvent(EventType type, int world, int channel, int mapId, int sourceCharacterId, String text) {
        this.type = type;
        this.world = world;
        this.channel = channel;
        this.mapId = mapId;
        this.sourceCharacterId = sourceCharacterId;
        this.text = text;
    }

    public static GameEvent chat(int world, int channel, int mapId, int senderId, String text) {
        return new GameEvent(EventType.CHAT, world, channel, mapId, senderId, text);
    }

    public static GameEvent mapEntered(int world, int channel, int mapId, int entererId) {
        return new GameEvent(EventType.MAP_ENTERED, world, channel, mapId, entererId, null);
    }

    /** 角色升级事件（玩家或 bot）：sourceCharacterId 为升级角色 ID（由订阅方按需解析角色）。 */
    public static GameEvent levelUp(int world, int channel, int mapId, int playerId) {
        return new GameEvent(EventType.LEVEL_UP, world, channel, mapId, playerId, null);
    }

    public EventType getType() {
        return type;
    }

    public int getWorld() {
        return world;
    }

    public int getChannel() {
        return channel;
    }

    public int getMapId() {
        return mapId;
    }

    public int getSourceCharacterId() {
        return sourceCharacterId;
    }

    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        return "GameEvent{type=" + type + ", world=" + world + ", channel=" + channel
                + ", mapId=" + mapId + ", sourceCharacterId=" + sourceCharacterId + '}';
    }
}
