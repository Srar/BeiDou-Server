package org.gms.server.bot.event;

/**
 * Bot 事件总线的事件类型。
 */
public enum EventType {
    /** 真实玩家在非命令频道聊天。 */
    CHAT,
    /** 真实玩家进入某张地图。 */
    MAP_ENTERED
}
