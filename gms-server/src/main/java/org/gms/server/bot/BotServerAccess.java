package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;

/**
 * Bot 与游戏服务器的注册接缝：channel/world 玩家存储、取图、名字查重、按 ID 取角色。
 * 默认实现 {@link DefaultBotServerAccess} 走 {@code Server.getInstance()}；单元测试注入
 * Fake 实现，避免触碰重量级 Server 单例与 Spring/数据库。
 */
public interface BotServerAccess {

    /** 把 bot 注册进 channel 与 world 的玩家存储（对应参考实现的 addBotToServer）。 */
    void addBotToServer(Character bot);

    /** 从 channel 与 world 玩家存储移除 bot。 */
    void removeBotFromServer(Character bot);

    /** 取图（null 表示图不存在）。 */
    MapleMap getMap(int world, int channel, int mapId);

    /** 从 bot 世界的 channel 存储按 ID 取角色（null 表示不在线）。 */
    Character getCharacterById(int characterId);

    /** 名字是否已被占用（跨世界玩家存储）。 */
    boolean isNameTaken(String name);
}
