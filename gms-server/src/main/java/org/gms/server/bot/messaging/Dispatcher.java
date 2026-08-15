package org.gms.server.bot.messaging;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.TimerManager;
import org.gms.server.bot.BotExecutors;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.types.SocialBot;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.gms.server.bot.BotStorage.checkIfInquirer;
import static org.gms.server.bot.BotStorage.checkIfRespondant;
import static org.gms.server.bot.BotStorage.getBotById;

@Slf4j
public class Dispatcher implements Runnable {

    private static final Dispatcher dispatcher = new Dispatcher(MessageQueue.getInstance());
    private final MessageQueue messageQueue;
    private final ScheduledFuture<?> scheduledTask;

    public Dispatcher(MessageQueue messageQueue) {
        log.info("Dispatcher object created");
        this.messageQueue = messageQueue;
        BotExecutors.ensureStarted();
        this.scheduledTask = TimerManager.getInstance().register(this, 2000);
    }

    // Static method to access the singleton instance
    public static Dispatcher getInstance() {
        return dispatcher;
    }

    @Override
    public void run() {
//        log.info("Dispatcher: RUN");
        processMessages();
    }

    private void processMessages() {
        try {
            ChatMessage message = messageQueue.getMessageNonBlocking("primary");
            if (message == null) {
                return;
            }
            Collection<Character> chars_on_map = message.getMap().getCharacters();
            final int[] botToCall = new int[1];  // Using an array to hold the bot ID

            boolean characterFound = checkIfCharacterOnMap(chars_on_map, message, botToCall);

            // Determine if the message is for any registered bot
            if (characterFound) {
                handleBotRunning(botToCall, message);
            } else {
                handleMessageWithNoBotName(message);
            }
        } catch (Exception e) {
            log.warn("Dispatcher.processMessages error", e);
        }
    }

    private boolean checkIfCharacterOnMap(Collection<Character> chars_on_map, ChatMessage message, int[] botToCall) {
        boolean characterFound = false;
        for (Character character : chars_on_map) {
            if (message.getContent().contains(character.getName())) {
                if (!checkIfInvisibleBot(character.getId())) {
                    botToCall[0] = character.getId();
                    characterFound = true;
                    break;
                }
            }
        }
        return characterFound;
    }

    private void handleBotRunning(int[] botToCall, ChatMessage message) {
        BotExecutors.runAsync(() -> {
            BotSM bot = getBotById(botToCall[0]);
            if (bot == null) {
                logBotNotFound(botToCall[0]);
                return;
            }

            if (!bot.getRunning()) {
                startNewBotSession(bot, message);
            } else {
                handleExistingBotSession(bot, message);
            }
        });
    }

    private void startNewBotSession(BotSM bot, ChatMessage message) {
        log.info("Bot not running. Start scheduledTask line");
        bot.setRunning(true);
        bot.getInteractors().setRespondant(message.getSender());
        bot.startScheduledTask();
        if (bot instanceof SocialBot socialBot) {
            socialBot.onFirstInteraction(message.getSender());
        }
    }

    private void handleExistingBotSession(BotSM bot, ChatMessage message) {
        if (bot instanceof SocialBot socialBot) {
            handleSocialBotSession(socialBot, message);
            return;
        }
        log.info("bot already running");
        bot.getInteractors().setInquirer(message.getSender());
        bot.getDialogueHandler().listOptions(message.getSender(), bot);
        // One-line inquiry: a naming message that also carries a keyword ("Tiger wana party?") pre-selects the
        // menu option in the same breath. Strip the bot's name first so a name that itself contains a keyword
        // can't self-match, then re-enqueue the remainder onto the tertiary queue exactly as a stand-alone
        // follow-up would have arrived — BotOptionMenu.poll drains it on the bot's next tick with the same
        // trim/lowercase/contains matcher. The menu is already active (listOptions ran synchronously above),
        // and a non-matching remainder ("yo Tiger") falls through harmlessly like a junk follow-up would.
        String remainder = message.getContent().replace(bot.getChr().getName(), "").trim();
        if (!remainder.isEmpty()) {
            messageQueue.addMessage("tertiary", new ChatMessage(message.getSender(), remainder));
            bot.nudgeSoon(0L); // player is on the bot's map -> pull the pre-selected option forward so it feels instant
        }
    }

    private void handleSocialBotSession(SocialBot socialBot, ChatMessage message) {
        if (socialBot.hasActiveRespondant()) {
            log.info("[Dispatcher] SocialBot busy, ignoring second player");
            return;
        }
        log.info("[Dispatcher] SocialBot available, setting respondant");
        socialBot.getInteractors().setRespondant(message.getSender());
        socialBot.onFirstInteraction(message.getSender());
    }

    private void logBotNotFound(int botId) {
        log.info("No bot found for ID: " + botId);
    }

    private void handleMessageWithNoBotName(ChatMessage message) {
        // message does not contain any info w/ bot names.
        Character respondant = message.getSender();
        if (checkIfRespondant(respondant)) { // Check if message contains a respondant
            // TODO(P5-D 对话体系)：SoloMapling 原实现先 expirePlayerChatCommands(respondant) 清除气泡，
            // 再入默认 secondary 队列；expirePlayerChatCommands 属对话命令，尚未移植。
            messageQueue.addMessage(message); // Put into 2nd queue
        } else if (checkIfInquirer(respondant)) {
            messageQueue.addMessage("tertiary", message);
        }
    }

    public void shutdown() {
        // SoloMapling 原实现关闭自有 ExecutorService；gms 的 TimerManager/ThreadManager 为服务器共享池，
        // 此处仅取消本 dispatcher 的调度任务，不触碰共享池。
        if (scheduledTask != null) {
            TimerManager.getInstance().stop(scheduledTask);
        }
    }

    // SoloMapling 的 checkIfInvisibleBot 定义于 CharacterStorage（硬编码不可见 bot id 列表），
    // 不在 P5-A 的副表（respondants/inquirers）扩展范围内，此处按源语义内联保留。
    // id 100 为 SoloMapling 约定；gms bot id 区段为 > BotHelpers.BOT_BASE_ID，暂无等价物。
    private static final List<Integer> INVISIBLE_BOT_LIST = Arrays.asList(100);

    private static boolean checkIfInvisibleBot(int id) {
        return INVISIBLE_BOT_LIST.contains(id);
    }

    /*
    Message:
    - Has Bot Name -> start that bot

    - Doesn't have bot name
        -> contains a respondant -> 2ndary queue
        -> doesn't contain a respondant -> random chat, remove

    2ndary queue:
        - active bots pull from this.
     */
}
