package org.gms.server.bot;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.util.PacketCreator;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Random;

/**
 * Bot 调试处理（参考 SoloMapling 的 BotDebugHandler 1:1 移植）。
 * <p>
 * 三重调试渠道的 gms 适配：
 * <ol>
 *   <li>文件日志：源 BotLogger 写 BotLog.txt，gms 等价写 {@code botlog.txt}；</li>
 *   <li>MMC 转发：gms 无 MapleMessengerConsole，原调用以 TODO 注释保留；</li>
 *   <li>粉笔黑板：gms 有 {@link PacketCreator#useChalkboard}，等价替换 SocialCommands.botSetChalkboard/botClearChalkboard。</li>
 * </ol>
 */
@Slf4j
public class BotDebugHandler {

    private static final String BOT_LOG_FILE = "botlog.txt";

    /**
     * 文件日志总开关（gms 增强 F4）：默认关闭。
     * 源（SoloMapling BotLogger）每 tick 无条件写 BotLog.txt；gms 侧全服 bot 争
     * logToFile 的全局 synchronized 锁做打开/追加/关闭磁盘 I/O（TrainingBot 每 tick
     * 两次），2核4G 数千 bot 时为固定浪费。默认不写文件，测试/GM 调试经
     * {@link #setFileLoggingEnabled(boolean)} 显式开启。volatile：tick 线程读、
     * 调试命令线程写。useChalkDebug/chalkboard 粉笔黑板逻辑不受此开关影响。
     */
    private static volatile boolean FILE_LOGGING_ENABLED = false;

    /** 开启/关闭 botlog.txt 文件日志（供测试或 GM 调试使用；默认关闭）。 */
    public static void setFileLoggingEnabled(boolean enabled) {
        FILE_LOGGING_ENABLED = enabled;
    }

    /** 查询文件日志开关状态（测试断言用）。 */
    public static boolean isFileLoggingEnabled() {
        return FILE_LOGGING_ENABLED;
    }

    boolean useChalkDebug;
    boolean logInteractors;
    Character chr;

    public BotDebugHandler(Character chr) {
        this.chr = chr;
        this.useChalkDebug = false;
        this.logInteractors = false;
    }

    public void setChalkDebug(boolean status) {
        useChalkDebug = status;
    }

    public boolean getChalkDebug() {
        return useChalkDebug;
    }

    public void setLogInteractors(boolean status) {
        logInteractors = status;
    }

    public boolean isLogInteractors() {
        return logInteractors;
    }

    public static void setBotChalkboard(Character fakechar, boolean status) {
        BotSM bot = BotStorage.getBotById(fakechar.getId());
        bot.getDebugger().setChalkDebug(status);
        if (!status) {
            botClearChalkboard(fakechar);
        }
    }

    public static boolean getChalkboardStatus(Character fakechar) {
        BotSM bot = BotStorage.getBotById(fakechar.getId());
        return bot.getDebugger().getChalkDebug();
    }

    public void debugLoggingFull(String botLogMessage) {
        debugLoggingFull(botLogMessage, null);
    }

    public void debugLoggingFull(String botLogMessage, String chalkboardMessage) {
        // 1) 文件日志：源 BotLogger 写 BotLog.txt，gms 等价写 botlog.txt。
        //    gms 增强（F4）：开关未开时跳过（防御绕过 handleDebugPrints 的直接调用者，
        //    如 TrainingBot.updateState 每 tick 的直接调用，省去字符串拼接后的写盘）。
        if (FILE_LOGGING_ENABLED) {
            logToFile(botLogMessage);
        }

        // 2) MMC 转发：gms 无 MapleMessengerConsole，原调用保留为 TODO（待 MMC 落地后回填）
        // TODO(移植): MapleMessengerConsole.isLoggingBot(chr.getId()) -> sendMMCLogToConnected(botLogMessage)
        // if (MapleMessengerConsole.isLoggingBot(chr.getId())) {
        //     MapleMessengerConsole.sendMMCLogToConnected(botLogMessage);
        // }

        // 3) 粉笔黑板（等价 SocialCommands.botSetChalkboard）
        if (chr != null && chalkboardMessage != null && useChalkDebug) {
            botSetChalkboard(chr, chalkboardMessage);
        }
    }

    public void logCurrentRespondantsAndInquirers(BotSM botSM) {
        if (!isLogInteractors()) {
            return;
        }
        // gms 增强（F4）：文件日志开关未开时直接返回，省去两次 StringBuilder 拼接与写盘
        //（防御绕过 handleDebugPrints 的直接调用者）。
        if (!FILE_LOGGING_ENABLED) {
            return;
        }
        StringBuilder respondantLine = new StringBuilder("Respondants: ");
        StringBuilder inquirerLine = new StringBuilder("Inquirers: ");

        for (Character character : botSM.getInteractors().getListRespondants()) {
            respondantLine.append(character.getName()).append(", ");
        }

        for (Character character : botSM.getInteractors().getListInquirer()) {
            inquirerLine.append(character.getName()).append(", ");
        }

        // Remove the trailing comma and space from each line
        if (respondantLine.length() > 12) {
            respondantLine.setLength(respondantLine.length() - 2);
        }
        if (inquirerLine.length() > 10) {
            inquirerLine.setLength(inquirerLine.length() - 2);
        }

        logToFile(respondantLine.toString());
        logToFile(inquirerLine.toString());
    }

    void handleDebugPrints(BotSM botSM) {
        // gms 增强（F4）：该方法只做日志（每 tick 无条件调用），文件日志关闭时整条短路
        //（源无条件 debugLoggingFull + logCurrentRespondantsAndInquirers）。粉笔黑板等
        // 非文件日志逻辑在 debugLoggingFull 内部，不受此开关影响。
        if (!FILE_LOGGING_ENABLED) {
            return;
        }
        debugLoggingFull("\n\n" + botSM.getChr().getName() + " State: " + botSM.state);
        logCurrentRespondantsAndInquirers(botSM);
    }

    protected void debugBubble(String debugmsg, BotSM botSM) {
        String periods = ".".repeat(new Random().nextInt(4) + 1);
        String dbmsg = "db: " + debugmsg + " " + periods;
        botSpeak(botSM.getChr(), dbmsg); // 等价 SocialCommands.BotSpeak（BotChatBubble）
        log.debug(dbmsg);
    }

    // ── 等价实现（gms 无 SocialCommands） ────────────────────────────────────

    /** 等价 SocialCommands.botSetChalkboard：设置并广播粉笔黑板。 */
    private static void botSetChalkboard(Character fakechar, String chalkboardMessage) {
        fakechar.setChalkboard(chalkboardMessage);
        if (fakechar.getMap() != null) {
            fakechar.getMap().broadcastMessage(PacketCreator.useChalkboard(fakechar, false));
        }
    }

    /** 等价 SocialCommands.botClearChalkboard：清空并广播粉笔黑板。 */
    private static void botClearChalkboard(Character fakechar) {
        fakechar.setChalkboard(null);
        if (fakechar.getMap() != null) {
            fakechar.getMap().broadcastMessage(PacketCreator.useChalkboard(fakechar, true));
        }
    }

    /** 等价 SocialCommands.BotSpeak → BotFullChat：普通聊天广播。 */
    private static void botSpeak(Character fakechar, String message) {
        if (fakechar.getMap() == null) {
            return;
        }
        fakechar.getMap().broadcastMessage(PacketCreator.getChatText(fakechar.getId(), message, fakechar.getWhiteChat(), 0));
    }

    /** 尽力而为的文件日志：写 botlog.txt（调试用，失败静默）。 */
    private static synchronized void logToFile(String message) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(BOT_LOG_FILE, true))) {
            writer.write(message);
            writer.newLine();
        } catch (IOException e) {
            log.debug("Failed to write botlog.txt: {}", e.getMessage());
        }
    }
}
