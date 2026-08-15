package org.gms.server.bot.messaging;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.TimerManager;
import org.gms.server.bot.BotExecutors;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.types.SocialBot;

import java.util.ArrayList;
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

    // 单轮处理上限：drain 循环防止高峰点名消息积压后被 QueueCleaner（primary 10s 过期）误删，
    // 但每 2s 轮询周期内不能无限取消息——过载会饿死 TimerManager 共享线程。
    // 达到上限本轮停止，剩余消息下轮继续。
    private static final int MAX_MESSAGES_PER_ROUND = 64;

    private void processMessages() {
        // drain 循环：原实现每轮只 poll 1 条，而 QueueCleaner 每 2s 也会清理过期消息，
        // 高峰时 primary 队列消息积压速度超过消费速度 → 点名消息被 10s 过期误删。
        int processed = 0;
        while (processed++ < MAX_MESSAGES_PER_ROUND) {
            try {
                ChatMessage message = messageQueue.getMessageNonBlocking("primary");
                if (message == null) {
                    return;
                }
                // 快照拷贝：防遍历期间玩家进图/离图与 getCharacters 并发修改抛 CME
                //（对齐 BotSM.checkMainPlayersOnMap 的既有做法）。
                Collection<Character> chars_on_map = new ArrayList<>(message.getMap().getCharacters());
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
    }

    private boolean checkIfCharacterOnMap(Collection<Character> chars_on_map, ChatMessage message, int[] botToCall) {
        boolean characterFound = false;
        for (Character character : chars_on_map) {
            // 候选过滤：只匹配已注册 bot——真人名 contains 命中后 getBotById 查无此 bot，
            // 走 logBotNotFound 噪音且浪费一轮 async。isBot 双判据（区段 id + 注册表）过滤。
            if (!BotHelpers.isBot(character)) {
                continue;
            }
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
        // FINISHED 态的 bot 正在拆卸（掉线/交易完成路径），此时启动新会话会与拆卸流程冲突。
        if (bot.getState() == BotSM.BotState.FINISHED) {
            log.debug("Dispatcher: bot {} is FINISHED, skipping new session",
                    bot.getChr() != null ? bot.getChr().getName() : "?");
            return;
        }
        log.info("Bot not running. Start scheduledTask line");
        bot.setRunning(true);
        bot.getInteractors().setRespondant(message.getSender());
        bot.startScheduledTask();
        if (bot instanceof SocialBot socialBot) {
            socialBot.onFirstInteraction(message.getSender());
        } else {
            // 非 SocialBot（FollowerBot/TrainingBot/BlackjackDealerBot 等）无 onFirstInteraction：
            // 此前只 setRunning + setRespondant + startScheduledTask，玩家收不到任何菜单反馈；
            // 后续关键词走 secondary 而这些 bot 不读 secondary → 首点名死路。
            // 对齐 handleExistingBotSession 非 Social 分支：setInquirer + listOptions 弹菜单
            //（listOptions 内部走 displayCommands 弹菜单，玩家可见可点选项）。
            bot.getInteractors().setInquirer(message.getSender());
            bot.getDialogueHandler().listOptions(message.getSender(), bot);
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
        // bot.getChr() 可能为 null（拆卸过程中角色已销毁）：跳过 remainder 逻辑，防 NPE 中断本轮 poll。
        if (bot.getChr() == null) {
            log.debug("Dispatcher: bot chr is null, skipping remainder enqueue");
            return;
        }
        String remainder = message.getContent().replace(bot.getChr().getName(), "").trim();
        if (!remainder.isEmpty()) {
            messageQueue.addMessage("tertiary", new ChatMessage(message.getSender(), remainder));
            bot.nudgeSoon(0L); // player is on the bot's map -> pull the pre-selected option forward so it feels instant
        }
    }

    private void handleSocialBotSession(SocialBot socialBot, ChatMessage message) {
        if (socialBot.hasActiveRespondant()) {
            // busy 不丢消息：strip bot 名后的非空 remainder 入 secondary 队列，由会话 bot 在其
            // tick 内消费（SocialBot.processMessages 轮询 secondary，且 sender == respondant 才
            // 处理，恰是当前会话玩家）。remainder 为空则维持原状直接 return。
            Character botChr = socialBot.getChr();
            if (botChr == null) {
                log.info("[Dispatcher] SocialBot busy, bot chr gone, dropping");
                return;
            }
            String remainder = message.getContent().replace(botChr.getName(), "").trim();
            if (!remainder.isEmpty()) {
                log.info("[Dispatcher] SocialBot busy, queuing remainder to secondary");
                messageQueue.addMessage("secondary", new ChatMessage(message.getSender(), remainder));
            } else {
                log.info("[Dispatcher] SocialBot busy, ignoring second player");
            }
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
