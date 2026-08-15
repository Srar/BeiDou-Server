package org.gms.server.bot.types;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.bot.BotLogic;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotTiming;
import org.gms.server.bot.messaging.ChatMessage;
import org.gms.server.bot.messaging.MessageQueue;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.util.Randomizer;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class DiceBot extends BotSM {
    private DiceBotState diceBotState = DiceBotState.RESET;
    private BetType currentBet = BetType.NONE;
    int[] rolls;
    private List<MapObject> currentPot;
    long startTime;
    long endTime;
    private boolean waitingForBet;
    private boolean betsProcessed;


    public DiceBot(Character character) {
        super(character);
    }

    private enum DiceBotState {
        RESET,
        WAIT_FOR_BET,
        BET,
        ROLL,
        ROLL_PENDING,
        PROCESS_BET,
        BET_PAYOUT
    }

    private void setDiceBotState(DiceBotState state) {
        this.diceBotState = state;
    }

    private enum BetType {
        CHO,
        HAN,
        NONE;
    }

    private void setBet(BetType bet) {
        this.currentBet = bet;
    }


    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }

        getDebugger().debugLoggingFull(String.format("%s DiceBotState: %s", getChr().getName(), diceBotState), String.format("%s", diceBotState));
        switch (diceBotState) {
            case RESET:
                resetDiceBotState();
                startGame();
                setDiceBotState(DiceBotState.WAIT_FOR_BET);
                break;
            case WAIT_FOR_BET:
                waitForBet();
                break;
            case BET:
                if (detectBet()) {
                    setDiceBotState(DiceBotState.ROLL);
                } else {
                    setDiceBotState(DiceBotState.WAIT_FOR_BET);
                }
                break;
            case ROLL:
                calculateRolls();
                setDiceBotState(DiceBotState.ROLL_PENDING);
                break;
            case ROLL_PENDING:
                if (rolls != null) {
                    setDiceBotState(DiceBotState.PROCESS_BET);
                }
                break;
            case PROCESS_BET:
                calculateBet();
                setDiceBotState(DiceBotState.BET_PAYOUT);
                break;
            case BET_PAYOUT:
                if (betsProcessed) {
                    setDiceBotState(DiceBotState.RESET);
                }
                break;
            default:
                log.info("Unexpected state: " + diceBotState);
                state = BotState.FINISHED;
                resetDiceBotState();
                throw new IllegalStateException("Unexpected state: " + state);
        }
    }

    private void resetDiceBotState() {
        setDiceBotState(DiceBotState.RESET);
        setBet(BetType.NONE);
        currentPot = null;
        rolls = null;
        startTime = System.currentTimeMillis();
        endTime = 0;
        betsProcessed = false;
    }

    private void startGame() {
        BotGameSupport.botChatbubble(getChr(), "Please Place your bets!");
    }

    @Override
    public void displayCommands(Character chr) {
        List<String> hint = List.of("Bet Cho (even)", "Bet Han (odd)");
        BotGameSupport.displayPlayerChatCommands(chr, hint);
    }

    private void waitForBet() {
        log.info("Waiting for bet");
        if (waitingForBet) {
            return; // Method is already running, so exit
        }

        synchronized (this) {
            if (waitingForBet) {
                return; // Double-check in case another thread just set it
            }
            waitingForBet = true;
        }

        try {
            try {
                displayCommands(getInteractors().getRespondant());
                waitForState(DiceBotState.BET, 25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Operation was interrupted");
            }
        } finally {
            waitingForBet = false; // Reset the flag when done
        }
    }

    private boolean handleIfListIsEmpty(List<MapObject> items) {
        // Check if the list is empty
        if (items.isEmpty()) {
            BotGameSupport.botSpeak(getChr(), "No Bet detected. Please Place your bets!");
            startTime = System.currentTimeMillis(); // Give player more time to bet in case they mistype
            return true;
        }
        return false;
    }

    private void handleBetList(List<MapObject> items) {
        BotGameSupport.botSpeak(getChr(), BotLogic.announceBetString(getInteractors().getRespondant(), items));
        currentPot = items;
    }

    private boolean detectBet() {
        List<MapObject> items = BotLogic.readPlayersBetsStamps(getInteractors().getRespondant(), 15000);
        if (handleIfListIsEmpty(items)) {
            return false;
        } else {
            handleBetList(items);
            return true;
        }
    }


    private void calculateRolls() {
        BotTiming.after(3000, () -> rolls = botDealerRollDoubleDice());
    }

    // gms 移植：BotCommands.botDealerRollDoubleDice 未落地（骰子特效包在源内已注释），
    // 按源语义内联：随机两骰并播报。特效广播待 TODO 回填。
    private int[] botDealerRollDoubleDice() {
        // TODO(P5-H2): 源还广播 showDiceEffect/showDoubleDiceEffect（被注释）；gms 无对应
        // PacketCreator.EffectPacket，保留为 TODO。
        int dice1 = Randomizer.nextInt(6) + 1;
        int dice2 = Randomizer.nextInt(6) + 1;
        BotGameSupport.botSpeak(getChr(), String.format("test %d %d", dice1, dice2));
        return new int[]{dice1, dice2};
    }

    private void calculateBet() {
        BotTiming.after(2000, () -> processBet(rolls));
    }

    private boolean calculateIfPlayerWins(boolean isOdd) {
        boolean playerWins;
        if (currentBet == BetType.HAN) {
            playerWins = isOdd; // HAN means Odd
        } else if (currentBet == BetType.CHO) {
            playerWins = !isOdd; // CHO means Even
        } else {
            throw new IllegalArgumentException("Invalid bet: " + currentBet); // Handle unexpected bet values
        }
        return playerWins;
    }

    private void payoutWinnersBets() {
        for (MapObject item : currentPot) {
            MapItem mapItem = (MapItem) item;
            for (int x = 0; x < mapItem.getItem().getQuantity(); x++) {
                Point center = getInteractors().getRespondant().getPosition();
                center = BotGameSupport.adjustCenterPositionXAxis(center, x, 2, 4, 20);
                BotGameSupport.botThrowItem(getChr(), mapItem.getItemId(), center);
                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void handlePlayerWin() {
        payoutWinnersBets();
        betsProcessed = true;
    }

    private void handlePlayerStealsBets() {
        getInteractors().getRespondant().setFame(getInteractors().getRespondant().getFame() - 10);
        getInteractors().getRespondant().dropMessage(5, "[Mushroom Casino] You have stolen your bets. You have been defamed by the Mushroom Casino.");
        BotGameSupport.botSpeak(getChr(), "Player has stolen back his bet. Ceasing Game.");
        state = BotState.FINISHED;
    }

    private void handlePlayerLose() {
        boolean pot_collectible = BotLogic.checkIfItemsOnFloorStill(
                currentPot, BotLogic.readPlayersBetsStamps(getInteractors().getRespondant(),
                        currentPot.getFirst().getPosition(), 75000)); // originally getRespondant().getPosition()

        if (pot_collectible) {
            BotGameSupport.lootItemListOnFloor(getChr(), currentPot);
        } else {
            handlePlayerStealsBets();
        }
    }

    private void processBet(int[] rolls) {
        int sum = rolls[0] + rolls[1];
        boolean isOdd = sum % 2 != 0;
        boolean playerWins = calculateIfPlayerWins(isOdd);
        BotGameSupport.botSpeak(getChr(), "Result: [" + rolls[0] + "] [" + rolls[1] + "] - " + (isOdd ? "Han" : "Cho"));
        BotGameSupport.botEmote(getChr(), 2);

        if (playerWins) {
            handlePlayerWin();
        } else {
            handlePlayerLose();
        }
        betsProcessed = true;
    }

    @Override
    public void processMessages() {
        try {
            ChatMessage message = MessageQueue.getInstance().getMessageWithTimeout("secondary", 1, TimeUnit.SECONDS);
            if (message == null) {
                return;
            }
            handleBetCommand(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void waitForState(DiceBotState targetState, long timeoutSeconds) throws InterruptedException {
        log.info("Waiting for state: " + targetState);
        endTime = startTime + (timeoutSeconds * 1000);
        synchronized (this) {
            if (!diceBotState.equals(targetState) && System.currentTimeMillis() < endTime) {
                processMessages();
            } else {
                BotGameSupport.botSpeak(getChr(), "Please place your bets when you are ready!");
                state = BotState.FINISHED;
                resetDiceBotState();
            }
        }
    }

    private void handleBetCommand(ChatMessage message) {
        if (!getInteractors().isMessageFromRespondant(message)) {
            return;
        }

        String content = message.getContent().toLowerCase();
        if (!content.contains("bet")) {
            return;
        }

        if (content.contains("han")) {
            processBet(BetType.HAN, message);
        } else if (content.contains("cho")) {
            processBet(BetType.CHO, message);
        } else {
            handleInvalidBet(message);
        }
    }

    private void processBet(BetType betType, ChatMessage message) {
        setDiceBotState(DiceBotState.BET);
        String announcement = String.format("%s bets on %s!",
                message.getSender().getName(),
                betType.name());
        BotGameSupport.botSpeak(getChr(), announcement);
        setBet(betType);
    }

    private void handleInvalidBet(ChatMessage message) {
        BotGameSupport.botSpeak(getChr(), "Please Select Han or Cho!");
        startTime = System.currentTimeMillis();
        setBet(BetType.NONE);
    }
}
