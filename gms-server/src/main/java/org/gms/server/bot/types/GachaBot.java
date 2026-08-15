package org.gms.server.bot.types;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.event.EventType;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.bot.gacha.CustomReactor;
import org.gms.server.bot.itempool.GachaFillerSystem;
import org.gms.server.bot.messaging.ChatMessage;
import org.gms.server.bot.messaging.MessageQueue;
import org.gms.server.maps.ReactorDropEntry;

import java.awt.Point;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

@Slf4j
public class GachaBot extends BotSM {
    private GachaBotState gachaBotState = GachaBotState.RESET;
    private List<String> hint = Collections.singletonList(getChr().getName());

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
        // gms 未落地 SCROLLING / GACHAPON_REWARD 事件类型，仅订阅 LEVEL_UP。
        BotEventBus.getInstance().subscribe(EventType.LEVEL_UP, this);
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
        BotGameSupport.botChatbubble(getChr(), "Position set!");
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
        String reaction = org.gms.server.bot.dialogue.BotDialogueHandler.getRandomResolvedLine(GachaBot.this, "RewardReaction");
        if (reaction != null && !reaction.isEmpty()) {
            BotGameSupport.botSpeak(getChr(), reaction + " " + reward);
        } else {
            BotGameSupport.botSpeak(getChr(), "Wow! " + reward);
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
            System.out.println("Events queued. staying in STAND BY 3");
        } else {
            // Move to next appropriate state
            setGachaBotState(GachaBotState.STAND_BY_4);
        }
    }

    @Override
    public void handleEvent(GameEvent event) {
        switch (event.getType()) {
            case LEVEL_UP:
                handleLevelUpEvent(event);
                break;
            default:
                // gms EventType 未落地 SCROLLING / GACHAPON_REWARD，保持空操作
                break;
        }
    }

    // 源使用同步录制回放编排：getMovementRecording(0, "rightleft45") + BotMoveStreamOffset，
    // 然后 getMovementRecording(0, "leftright70") + BotMoveStreamOffset（见 SoloMapling GachaBot.handleLevelUpEvent）。
    // gms 的 org.gms.server.bot.replay 已落地这两个入口（InPacketReader.getMovementRecording /
    // MovementCommands.BotMoveStreamOffset），此处暂以表情+气泡祝贺替代原始回放，后续可按源恢复。
    private void handleLevelUpEvent(GameEvent event) {
        BotGameSupport.blockingSleep(1500);
        BotGameSupport.botEmote(getChr(), 2);
        BotGameSupport.botChatbubble(getChr(), "Ayy Congrats " + resolvePlayerName(event.getSourceCharacterId()) + "!");
        BotGameSupport.blockingSleep(1500);
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
