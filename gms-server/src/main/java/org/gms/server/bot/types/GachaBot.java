package org.gms.server.bot.types;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.dialogue.BotDialogueHandler;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.EventType;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.bot.gacha.CustomReactor;
import org.gms.server.bot.itempool.GachaFillerSystem;
import org.gms.server.bot.messaging.ChatMessage;
import org.gms.server.bot.messaging.MessageQueue;
import org.gms.server.bot.replay.MovementRecording;
import org.gms.server.maps.ReactorDropEntry;
import org.gms.util.I18nUtil;
import org.gms.util.Randomizer;

import java.awt.Point;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import static org.gms.server.bot.replay.InPacketReader.getMovementRecording;
import static org.gms.server.bot.replay.MovementCommands.BotMoveStreamOffset;

@Slf4j
public class GachaBot extends BotSM {
    private GachaBotState gachaBotState = GachaBotState.RESET;
    private List<String> hint = Collections.singletonList(getChr().getName());

    // 摆摊位置喊话小池（国服口语，随机三选一）
    private static final List<String> POSITION_PROMPTS = List.of("位置摆好了！", "摊子支起来咯！", "就这儿 开抽！");

    private long startTime;
    private long endTime;

    private Point basePosition;
    private Queue<String> rewardQueue; // Queue for gacha rewards to react to

    public GachaBot(Character character) {
        super(character);
        dialoguePath = "GachaBotDialogue.yaml";
        botType = "GachaBot";
    }

    @Override
    protected void onScheduledStart() {
        // stopScheduledTask 会退订全部事件；每轮启动时幂等重订阅。
        BotEventBus.getInstance().subscribe(EventType.LEVEL_UP, this);
        BotEventBus.getInstance().subscribe(EventType.SCROLLING, this);
        BotEventBus.getInstance().subscribe(EventType.GACHAPON_REWARD, this);
    }

    private void setGachaBotState(GachaBotState state) {
        this.gachaBotState = state;
    }

    protected enum GachaBotState {
        RESET,
        SET_POSITION,
        RUN_ROULETTE,
        STAND_BY_1,
        STAND_BY_2,
        PICKUP_ITEM,
        STAND_BY_3,
        STAND_BY_4,
        PROCESS_REWARD
    }

    private void resetGachaBotState() {
        setGachaBotState(GachaBotState.RESET);
    }

    private void setPosition() {
        // Set the bot's main position where it will operate from
        this.basePosition = getChr().getPosition();
        BotGameSupport.botChatbubble(getChr(), POSITION_PROMPTS.get(Randomizer.nextInt(POSITION_PROMPTS.size())));
    }

    private void runRoulette() {
        // Check if there are main characters on the map
        if (!checkMainPlayersOnMap()) {
            return;
        }

        // Run the roulette drop animation
        int prize_id = 1082223; // scg
        List<ReactorDropEntry> popDrops = CustomReactor.createReactorDropList(GachaFillerSystem.createGachaListWithPrize(prize_id));
        CustomReactor.gachaPop(getChr(), popDrops);
    }

    private void pickupItem() {
        // Check if there are main characters on the map
        if (!checkMainPlayersOnMap()) {
            return;
        }
        BotGameSupport.botEmote(getChr(), 3);
        BotGameSupport.botLootOwnerItems(getChr(), getChr().getPosition(), 12000);
    }

    private void processReward() {
        return;
    }

    private void reactToReward(String reward) {
        // React based on the reward received
        String reaction = BotDialogueHandler.getRandomResolvedLine(GachaBot.this, "RewardReaction");
        if (reaction != null && !reaction.isEmpty()) {
            BotGameSupport.botSpeak(getChr(), reaction + " " + reward);
        } else {
            BotGameSupport.botSpeak(getChr(), "哇！" + reward);
        }
    }


    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        getDebugger().debugLoggingFull(String.format("%s GachaBotState: %s", this.getChr().getName(), gachaBotState), String.format("%s", gachaBotState));

        switch (gachaBotState) {
            case RESET:
                resetGachaBotState();
                setGachaBotState(GachaBotState.SET_POSITION);
                break;
            case SET_POSITION:
                setPosition();
                setGachaBotState(GachaBotState.RUN_ROULETTE);
                break;
            case RUN_ROULETTE:
                runRoulette();
                setGachaBotState(GachaBotState.STAND_BY_1);
                break;
            case STAND_BY_1:
                setGachaBotState(GachaBotState.STAND_BY_2);
                break;
            case STAND_BY_2:
                setGachaBotState(GachaBotState.PICKUP_ITEM);
                break;
            case PICKUP_ITEM:
                pickupItem();
                setGachaBotState(GachaBotState.STAND_BY_3);
                break;
            case STAND_BY_3:
                executeEventQueueGacha();
                break;
            case STAND_BY_4:
                setGachaBotState(GachaBotState.PROCESS_REWARD);
                break;
            case PROCESS_REWARD:
                processReward();
                setGachaBotState(GachaBotState.RUN_ROULETTE); // Loop back to state 1
                break;
            default:
                log.info("Unexpected state: " + gachaBotState);
                state = BotState.FINISHED;
                resetGachaBotState();
                throw new IllegalStateException("Unexpected state: " + state);
        }
    }

    @Override
    public void displayCommands(Character chr) {
        BotGameSupport.displayPlayerChatCommands(chr, hint);
    }

    @Override
    public void processMessages() {
        try {
            ChatMessage message = MessageQueue.getInstance().getMessageWithTimeout("secondary", 1, TimeUnit.SECONDS);
            if (message == null) {
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void executeEventQueueGacha() {
        // Process all queued events
        super.processQueuedEvents();

        // Transition logic
        if (super.hasQueuedEvents()) {
            log.debug(I18nUtil.getLogMessage("GachaBot.event.queue.pending"));
        } else {
            // Move to next appropriate state
            setGachaBotState(GachaBotState.STAND_BY_4);
        }
    }

    @Override
    public void handleEvent(GameEvent event) {
        switch (event.getType()) {
            case GACHAPON_REWARD:
                handleGachaponEvent(event);
                break;
            case SCROLLING:
                handleScrollingEvent(event);
                break;
            case LEVEL_UP:
                handleLevelUpEvent(event);
                break;
            default:
                // 未订阅的事件类型（bot 只订阅三种），保持空操作
                break;
        }
    }

    // 扭蛋开奖反应：表情 + 对开奖玩家的祝贺气泡。
    private void handleGachaponEvent(GameEvent event) {
        BotGameSupport.botEmote(getChr(), 2);
        BotGameSupport.botChatbubble(getChr(), "运气爆棚 " + resolvePlayerName(event.getSourceCharacterId()) + "！");
    }

    // 卷轴结果反应：成功/失败分别用不同表情 + 台词祝贺/安慰。
    private void handleScrollingEvent(GameEvent event) {
        if (Boolean.TRUE.equals(event.getPass())) {
            BotGameSupport.botEmote(getChr(), 3);
            BotGameSupport.botChatbubble(getChr(), "卷轴成了 " + resolvePlayerName(event.getSourceCharacterId()) + "，好手气！");
        } else {
            BotGameSupport.botEmote(getChr(), 4);
            BotGameSupport.botChatbubble(getChr(), "哎呀 " + resolvePlayerName(event.getSourceCharacterId()) + "，卷轴炸了…");
        }
    }

    // 升级庆祝：刻意同步编排——两段录制回放按其数据时长阻塞，中间夹表情+祝贺气泡
    //（见 SoloMapling GachaBot.handleLevelUpEvent：rightleft45 → emote+气泡 → leftright70）。
    // 录制品缺失/回放异常时保留表情+气泡兜底，不中断庆祝流程。
    private void handleLevelUpEvent(GameEvent event) {
        playCelebrationRecording("rightleft45");
        BotGameSupport.blockingSleep(1500);
        BotGameSupport.botEmote(getChr(), 2);
        BotGameSupport.botChatbubble(getChr(), "恭喜 " + resolvePlayerName(event.getSourceCharacterId()) + "！");
        BotGameSupport.blockingSleep(1500);
        playCelebrationRecording("leftright70");
    }

    /** 播放 map0 下的升级庆祝录制（带偏移回放）；失败仅告警，由调用方兜底。 */
    private void playCelebrationRecording(String recordingName) {
        try {
            MovementRecording mvr = getMovementRecording(0, recordingName);
            BotMoveStreamOffset(mvr, getChr());
        } catch (Exception e) {
            log.warn(I18nUtil.getLogMessage("GachaBot.recording.missing", recordingName, getChr().getName()), e);
        }
    }

    private String resolvePlayerName(int characterId) {
        Character target = null;
        if (getChr().getMap() != null) {
            for (Character chr : getChr().getMap().getCharacters()) {
                if (chr.getId() == characterId) {
                    target = chr;
                    break;
                }
            }
        }
        return target != null ? target.getName() : ("#" + characterId);
    }

}
