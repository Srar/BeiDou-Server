package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.maps.MapleMap;
import org.gms.util.I18nUtil;

/**
 * {@link BotServerAccess} 的生产实现：经 OdinMS 式 {@code Server.getInstance()} 单例
 * 访问 channel/world 玩家存储与地图工厂。bot 的世界/频道来自 game_config
 * （bot.world / bot.channel，缺省回落 0 世界 1 频道）。
 */
@Slf4j
public final class DefaultBotServerAccess implements BotServerAccess {

    public static final DefaultBotServerAccess INSTANCE = new DefaultBotServerAccess();

    private DefaultBotServerAccess() {
    }

    public static int resolveBotWorld() {
        int world = GameConfig.getServerInt("bot.world");
        return world <= 0 ? 0 : world;
    }

    public static int resolveBotChannel() {
        int channel = GameConfig.getServerInt("bot.channel");
        return channel <= 0 ? 1 : channel;
    }

    @Override
    public void addBotToServer(Character bot) {
        int world = bot.getWorld();
        int channel = bot.getClient().getChannel();
        Channel ch = Server.getInstance().getChannel(world, channel);
        if (ch == null) {
            throw new IllegalStateException(
                    I18nUtil.getLogMessage("BotServerAccess.channel.missing", world, channel));
        }
        ch.addPlayer(bot);
        World w = Server.getInstance().getWorld(world);
        if (w != null) {
            w.getPlayerStorage().addPlayer(bot);
        }
    }

    @Override
    public void removeBotFromServer(Character bot) {
        // 销毁/回滚路径释放 gcmove 移动引擎资源（disable 幂等，内部判空并取消
        // follow/travel/fidget），防 BotMovementState 随销毁风暴永久泄漏。
        GCMovement.disable(bot);
        int world = bot.getWorld();
        int channel = bot.getClient().getChannel();
        Channel ch = Server.getInstance().getChannel(world, channel);
        if (ch != null) {
            ch.removePlayer(bot);
        }
        World w = Server.getInstance().getWorld(world);
        if (w != null) {
            w.getPlayerStorage().removePlayer(bot.getId());
        }
    }

    @Override
    public MapleMap getMap(int world, int channel, int mapId) {
        Channel ch = Server.getInstance().getChannel(world, channel);
        return ch == null ? null : ch.getMapFactory().getMap(mapId);
    }

    @Override
    public Character getCharacterById(int characterId) {
        Channel ch = Server.getInstance().getChannel(resolveBotWorld(), resolveBotChannel());
        return ch == null ? null : ch.getPlayerStorage().getCharacterById(characterId);
    }

    @Override
    public boolean isNameTaken(String name) {
        for (World world : Server.getInstance().getWorlds()) {
            if (world.getPlayerStorage().getCharacterByName(name) != null) {
                return true;
            }
        }
        return false;
    }
}
