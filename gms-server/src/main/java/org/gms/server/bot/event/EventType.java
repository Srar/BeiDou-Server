package org.gms.server.bot.event;

/**
 * Bot 事件总线的事件类型。
 */
public enum EventType {
    /** 真实玩家在非命令频道聊天。 */
    CHAT,
    /** 真实玩家进入某张地图。 */
    MAP_ENTERED,
    /** 角色升级（玩家或 bot 都发布）。 */
    LEVEL_UP,
    /** 玩家使用卷轴的结果（成功/失败），载荷携带 pass 标志。 */
    SCROLLING,
    /** 玩家扭蛋开奖获得奖励（百宝箱/卷轴屋等开奖路径发布）。 */
    GACHAPON_REWARD
}
