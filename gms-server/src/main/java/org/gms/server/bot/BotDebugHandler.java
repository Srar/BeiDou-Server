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
        // 1) 文件日志：源 BotLogger 写 BotLog.txt，gms 等价写 botlog.txt
        logToFile(botLogMessage);

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
