package org.gms.server.bot.messaging;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.command.CommandsExecutor;
import org.gms.constants.inventory.EquipType;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.bot.BotDebugHandler;
import org.gms.server.bot.BotGeneration;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.DefaultBotServerAccess;
import org.gms.server.bot.commands.MapleMessengerCommands;
import org.gms.server.bot.decorate.BotDecorateNX;
import org.gms.server.bot.decorate.BotDecorationQueue;
import org.gms.server.bot.decorate.NXItemPool;
import org.gms.server.bot.itempool.EquipMetadataCache;
import org.gms.util.I18nUtil;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Maple Messenger 控制台（SoloMapling MapleMessengerConsole 移植）。
 * <p>
 * GM 在信使窗里输入 {@code 命令名:参数}（如 {@code mmc:connect}）即与 Console 傀儡 bot
 * 同处一个信使房间，控制台回显走 MapleMessengerCommands 的彩字消息。命令集：
 * <ul>
 *   <li>mmc connect / disconnect —— 连接/断开控制台（连接时把 Console 傀儡拉进信使）</li>
 *   <li>botlog / botunlog &lt;id|name&gt; —— 开/关单个 bot 的日志转发</li>
 *   <li>chalkboard &lt;id|name&gt; —— 切换 bot 粉笔黑板调试</li>
 *   <li>setallchalk / removeallchalk —— 全量开/关粉笔黑板</li>
 *   <li>resetbotlog —— 清空日志转发集合</li>
 *   <li>cmd &lt;chatCommand&gt; —— 经 GM 客户端执行任意聊天命令（如 !bot list）</li>
 *   <li>decoqueue enable|disable|start|stop|status —— BotDecorationQueue 控制</li>
 *   <li>decoratenx enable|disable|status|cache|reload —— BotDecorateNX/NXItemPool/EquipMetadataCache 控制</li>
 * </ul>
 * gms 增强：源用普通 HashSet 存连接态/日志态，多线程（netty worker + bot tick）并发读写
 * 有可见性风险，gms 改用并发集合；角色查找统一经 DefaultBotServerAccess（bot 频道）。
 */
@Slf4j
public class MapleMessengerConsole {

    @FunctionalInterface
    private interface ConsoleCommand {
        void execute(Character chr, String[] args);
    }

    private static final Set<Integer> connectedUsers = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> botsLogging = ConcurrentHashMap.newKeySet();
    private static final ConcurrentMap<String, ConsoleCommand> commandMap = new ConcurrentHashMap<>();

    // =========================================================================
    // 连接态与日志态
    // =========================================================================

    public static boolean isUserConnected(int userId) {
        return connectedUsers.contains(userId);
    }

    private static void connectUser(Character chr) {
        int userId = chr.getId();
        if (connectedUsers.add(userId)) {
            Character console = BotGeneration.getConsoleBot();
            if (console != null && chr.getMessenger() != null) {
                MapleMessengerCommands.addBotToMessenger(chr, console);
            }
            MapleMessengerCommands.sendColoredConsoleMessage(chr,
                    I18nUtil.getMessage("BotCommand.mmc.connected", chr.getName()));
        }
    }

    public static void disconnectUser(Character chr) {
        int userId = chr.getId();
        if (connectedUsers.remove(userId)) {
            Character console = BotGeneration.getConsoleBot();
            if (console != null && chr.getMessenger() != null) {
                MapleMessengerCommands.removeBotFromMessenger(chr, console);
            }
            MapleMessengerCommands.sendConsoleMessage(chr,
                    I18nUtil.getMessage("BotCommand.mmc.disconnected", chr.getName()));
        }
    }

    /**
     * GM 下线清理钩子（{@code Character.logOff} 调用，仅真实登出触发）：
     * 只摘除连接态集合条目，不发回显也不动信使（登出链路上 messenger 已被
     * closePlayerMessenger 关闭、客户端会话即将断开，发消息既无意义也可能失败）。
     * 不清理则 GM 重登后 mmc:connect 的 add 返回 false 变 no-op。
     */
    public static void onUserLogout(Character chr) {
        if (chr != null) {
            connectedUsers.remove(chr.getId());
        }
    }

    /**
     * bot 销毁清理钩子（{@code BotGeneration.removeBotFromServer} 调用）：
     * 把已销毁 bot 从日志转发集合摘除，防止 botsLogging 随 bot 生成/销毁风暴
     * 积累死 id（死 id 本身无害——转发按在线查找兜底，但会让集合无界增长）。
     */
    public static void cleanupBot(int botId) {
        botsLogging.remove(botId);
    }

    /**
     * 服务停机清理（Server 停机钩子序列调用）：清空连接态与日志转发集合，
     * 保证 in-place 重启后旧世界的角色 id 不会污染新会话。
     */
    public static void clearForShutdown() {
        connectedUsers.clear();
        botsLogging.clear();
    }

    public static boolean isLoggingBot(int userId) {
        return botsLogging.contains(userId);
    }

    private static void startLoggingBot(Character chr) {
        if (chr != null) {
            botsLogging.add(chr.getId());
        }
    }

    private static void stopLoggingBot(Character chr) {
        if (chr != null) {
            botsLogging.remove(chr.getId());
        }
    }

    private static void resetLoggingBot() {
        botsLogging.clear();
    }

    private static void toggleBotChalkboard(Character chr) {
        if (chr == null || !BotHelpers.isBot(chr) || BotStorage.getBotById(chr.getId()) == null) {
            return;
        }
        BotDebugHandler.setBotChalkboard(chr, !BotDebugHandler.getChalkboardStatus(chr));
    }

    // =========================================================================
    // 命令注册
    // =========================================================================

    static {
        registerCommand("help", (chr, args) ->
                MapleMessengerCommands.sendConsoleMessage(chr, I18nUtil.getMessage("BotCommand.mmc.help")));

        registerCommand("mmc", (chr, args) -> {
            if (args.length > 1) {
                switch (args[1]) {
                    case "connect":
                        connectUser(chr);
                        break;
                    case "disconnect":
                        executeIfConnected(chr, () -> disconnectUser(chr));
                        break;
                    default:
                        MapleMessengerCommands.sendConsoleMessage(chr,
                                I18nUtil.getMessage("BotCommand.mmc.usageMmc"));
                        break;
                }
            } else {
                MapleMessengerCommands.sendConsoleMessage(chr, I18nUtil.getMessage("BotCommand.mmc.usageMmc"));
            }
        });

        registerBotLoggingCommand("botlog", true);   // 开启日志转发
        registerBotLoggingCommand("botunlog", false); // 关闭日志转发

        registerCommand("chalkboard", (chr, args) -> {
            executeIfConnected(chr, () -> {
                if (args.length > 1) {
                    handleBotChalkboard(args[1]);
                } else {
                    MapleMessengerCommands.sendColoredConsoleMessage(chr,
                            I18nUtil.getMessage("BotCommand.mmc.usageChalk"));
                }
            });
        });

        registerCommand("setallchalk", (chr, args) -> {
            executeIfConnected(chr, MapleMessengerConsole::setAllChalkboards);
        });

        registerCommand("removeallchalk", (chr, args) -> {
            executeIfConnected(chr, MapleMessengerConsole::removeAllChalkboards);
        });

        registerCommand("resetbotlog", (chr, args) -> {
            executeIfConnected(chr, MapleMessengerConsole::resetLoggingBot);
        });

        registerCommand("cmd", (chr, args) -> {
            executeIfConnected(chr, () -> {
                if (args.length > 1) {
                    chatCommandViaMMC(chr, args);
                } else {
                    MapleMessengerCommands.sendColoredConsoleMessage(chr,
                            I18nUtil.getMessage("BotCommand.mmc.usageCmd"));
                }
            });
        });

        registerCommand("decoqueue", (chr, args) -> {
            executeIfConnected(chr, () -> handleDecoQueue(chr, args));
        });

        registerCommand("decoratenx", (chr, args) -> {
            executeIfConnected(chr, () -> handleDecorateNx(chr, args));
        });
    }

    private static void handleDecorateNx(Character chr, String[] args) {
        if (args.length < 2) {
            MapleMessengerCommands.sendColoredConsoleMessage(chr,
                    I18nUtil.getMessage("BotCommand.mmc.decoratenxUsage"));
            return;
        }
        switch (args[1]) {
            case "enable":
                BotDecorateNX.ENABLED = true;
                MapleMessengerCommands.sendColoredConsoleMessage(chr,
                        I18nUtil.getMessage("BotCommand.mmc.decoratenxEnabled"));
                break;
            case "disable":
                BotDecorateNX.ENABLED = false;
                MapleMessengerCommands.sendColoredConsoleMessage(chr,
                        I18nUtil.getMessage("BotCommand.mmc.decoratenxDisabled"));
                break;
            case "status":
                MapleMessengerCommands.sendColoredConsoleMessage(chr,
                        I18nUtil.getMessage("BotCommand.mmc.decoratenxStatus",
                                BotDecorateNX.ENABLED, NXItemPool.isLoaded(),
                                EquipMetadataCache.isInitialized()));
                break;
            case "cache": {
                // 展示 EquipMetadataCache 统计（gms 无源 all()/getCashByType，按类型求和 nonCash）
                if (!EquipMetadataCache.isInitialized()) {
                    MapleMessengerCommands.sendColoredConsoleMessage(chr,
                            I18nUtil.getMessage("BotCommand.mmc.decoratenxCacheNotReady"));
                    break;
                }
                EquipMetadataCache cache = EquipMetadataCache.get();
                int total = 0;
                for (EquipType et : EquipType.values()) {
                    total += cache.nonCash(et).size();
                }
                MapleMessengerCommands.sendColoredConsoleMessage(chr,
                        I18nUtil.getMessage("BotCommand.mmc.decoratenxCache", total));
                break;
            }
            case "reload": {
                // 强制重载 NXItemPool（重读 YAML + 从缓存重建）
                NXItemPool.forceReload();
                MapleMessengerCommands.sendColoredConsoleMessage(chr,
                        I18nUtil.getMessage("BotCommand.mmc.decoratenxReloaded"));
                break;
            }
            default:
                MapleMessengerCommands.sendColoredConsoleMessage(chr,
                        I18nUtil.getMessage("BotCommand.mmc.decoratenxUsage"));
        }
    }

    private static void handleDecoQueue(Character chr, String[] args) {
        if (args.length < 2) {
            MapleMessengerCommands.sendColoredConsoleMessage(chr,
                    I18nUtil.getMessage("BotCommand.mmc.decoqueueUsage"));
            return;
        }
        switch (args[1]) {
            case "enable":
                BotDecorationQueue.ENABLED = true;
                MapleMessengerCommands.sendColoredConsoleMessage(chr,
                        I18nUtil.getMessage("BotCommand.mmc.decoqueueEnabled"));
                break;
            case "disable":
                BotDecorationQueue.ENABLED = false;
                BotDecorationQueue.stop();
                MapleMessengerCommands.sendColoredConsoleMessage(chr,
                        I18nUtil.getMessage("BotCommand.mmc.decoqueueDisabled"));
                break;
            case "start":
                if (!BotDecorationQueue.ENABLED) {
                    MapleMessengerCommands.sendColoredConsoleMessage(chr,
                            I18nUtil.getMessage("BotCommand.mmc.decoqueueStartFail"));
                    break;
                }
                if (BotDecorationQueue.isRunning()) {
                    MapleMessengerCommands.sendColoredConsoleMessage(chr,
                            I18nUtil.getMessage("BotCommand.mmc.decoqueueRunning"));
                    break;
                }
                BotDecorationQueue.start();
                MapleMessengerCommands.sendColoredConsoleMessage(chr,
                        I18nUtil.getMessage("BotCommand.mmc.decoqueueStarted"));
                break;
            case "stop":
                if (!BotDecorationQueue.isRunning()) {
                    MapleMessengerCommands.sendColoredConsoleMessage(chr,
                            I18nUtil.getMessage("BotCommand.mmc.decoqueueNotRunning"));
                    break;
                }
                BotDecorationQueue.stop();
                MapleMessengerCommands.sendColoredConsoleMessage(chr,
                        I18nUtil.getMessage("BotCommand.mmc.decoqueueStopped"));
                break;
            case "status":
                MapleMessengerCommands.sendColoredConsoleMessage(chr,
                        I18nUtil.getMessage("BotCommand.mmc.decoqueueStatus",
                                BotDecorationQueue.ENABLED, BotDecorationQueue.isRunning(),
                                BotDecorationQueue.getPendingCount()));
                break;
            default:
                MapleMessengerCommands.sendColoredConsoleMessage(chr,
                        I18nUtil.getMessage("BotCommand.mmc.decoqueueUsage"));
        }
    }

    private static void registerBotLoggingCommand(String commandName, boolean startLogging) {
        registerCommand(commandName, (chr, args) -> {
            executeIfConnected(chr, () -> {
                if (args.length > 1) {
                    handleBotLog(args[1], startLogging);
                } else {
                    MapleMessengerCommands.sendColoredConsoleMessage(chr,
                            I18nUtil.getMessage("BotCommand.mmc.usageBotlog", commandName));
                }
            });
        });
    }

    private static void registerCommand(String name, ConsoleCommand command) {
        commandMap.put(name, command);
    }

    private static void executeIfConnected(Character chr, Runnable command) {
        if (isUserConnected(chr.getId())) {
            command.run();
        } else {
            MapleMessengerCommands.sendConsoleMessage(chr,
                    I18nUtil.getMessage("BotCommand.mmc.needConnect"));
        }
    }

    // =========================================================================
    // 信使消息入口（MessengerHandler 0x06 分支，GM 专用）
    // =========================================================================

    /**
     * 解析「命令:参数」格式的信使消息并分发。非 GM 不会被 MessengerHandler 路由到这里。
     */
    public static void executeCommand(Character chr, String input) {
        String noName = getTextAfterColon(input);
        if (noName == null) {
            return;
        }
        String[] parts = noName.split("\\s+");
        String commandName = parts[0];

        ConsoleCommand command = commandMap.get(commandName);
        if (command != null) {
            command.execute(chr, parts);
        } else if (isUserConnected(chr.getId())) {
            MapleMessengerCommands.sendConsoleMessage(chr,
                    I18nUtil.getMessage("BotCommand.mmc.unknown", commandName));
        }
    }

    private static void handleBotLog(String identifier, boolean startLogging) {
        Character chr = resolveCharacter(identifier);
        if (startLogging) {
            startLoggingBot(chr);
        } else {
            stopLoggingBot(chr);
        }
    }

    private static void handleBotChalkboard(String identifier) {
        toggleBotChalkboard(resolveCharacter(identifier));
    }

    private static void setAllChalkboards() {
        for (Map.Entry<Integer, BotSM> e : BotStorage.getAllBots().entrySet()) {
            Character chr = DefaultBotServerAccess.INSTANCE.getCharacterById(e.getKey());
            if (chr != null) {
                BotDebugHandler.setBotChalkboard(chr, true);
            }
        }
    }

    private static void removeAllChalkboards() {
        for (Map.Entry<Integer, BotSM> e : BotStorage.getAllBots().entrySet()) {
            Character chr = DefaultBotServerAccess.INSTANCE.getCharacterById(e.getKey());
            if (chr != null) {
                BotDebugHandler.setBotChalkboard(chr, false);
            }
        }
    }

    /**
     * 把日志行转发给所有已连接控制台的 GM（BotDebugHandler.debugLoggingFull 调用）。
     */
    public static void sendMMCLogToConnected(String textLog) {
        if (connectedUsers.isEmpty()) {
            return;
        }
        for (Integer connectedUserId : connectedUsers) {
            // GM 可在任意世界/频道登录（不只 bot 频道），按 world 级玩家存储查找；
            // 只查 bot 频道会在多频道部署下让 GM 静默收不到日志。
            Character chr = findOnlineCharacter(connectedUserId);
            if (chr != null) {
                MapleMessengerCommands.sendColoredConsoleMessage(chr, textLog);
            } else {
                // 转发目标不在线：GM 已下线但未走 logOff 清理，或正处频道迁移窗口。
                log.warn(I18nUtil.getLogMessage("MapleMessengerConsole.log.forwardMiss", connectedUserId));
            }
        }
    }

    /** 经全 world 玩家存储查找在线角色（跨频道可见，与 botlog 转发语义一致）。 */
    private static Character findOnlineCharacter(int characterId) {
        List<World> worlds = Server.getInstance().getWorlds();
        if (worlds == null) { // 世界未初始化（启动早期/停机中）
            return null;
        }
        for (World w : worlds) {
            Character chr = w.getPlayerStorage().getCharacterById(characterId);
            if (chr != null) {
                return chr;
            }
        }
        return null;
    }

    private static void chatCommandViaMMC(Character player, String[] args) {
        String combinedStringCommand = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        CommandsExecutor.getInstance().handle(player.getClient(), combinedStringCommand);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** 按 id 或名字解析在线角色（bot 频道玩家存储）。 */
    private static Character resolveCharacter(String idOrName) {
        if (isNumeric(idOrName)) {
            return DefaultBotServerAccess.INSTANCE.getCharacterById(Integer.parseInt(idOrName));
        }
        Channel ch = Server.getInstance().getChannel(
                DefaultBotServerAccess.resolveBotWorld(), DefaultBotServerAccess.resolveBotChannel());
        return ch == null ? null : ch.getPlayerStorage().getCharacterByName(idOrName);
    }

    /** 等价 SoloMaplingUtilities.getTextAfterColon：取冒号后文本，无冒号返回 null。 */
    private static String getTextAfterColon(String input) {
        int colonIndex = input.indexOf(":");
        if (colonIndex != -1 && colonIndex < input.length() - 1) {
            return input.substring(colonIndex + 1).trim();
        }
        return null;
    }

    private static boolean isNumeric(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        try {
            Integer.parseInt(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
