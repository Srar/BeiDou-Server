package org.gms.server.bot.types;

import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.freemarket.FMEquip;
import org.gms.server.bot.messaging.ChatMessage;
import org.gms.server.bot.messaging.MessageQueue;
import org.gms.server.bot.trade.BotTradeSM;
import org.gms.util.Randomizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.gms.server.bot.BotLogic.generateCleanItemEquip;
import static org.gms.server.bot.commands.MegaphoneCommands.BotItemMegaphone;
import static org.gms.server.bot.commands.SocialCommands.BotEmote;
import static org.gms.server.bot.commands.VFXCommands.botScrollFail;
import static org.gms.server.bot.commands.VFXCommands.botScrollSuccess;
import static org.gms.server.bot.dialogue.BotDialogueHandler.getRandomResolvedLine;
import static org.gms.server.bot.freemarket.BotEconomy.formatPriceToShorthand;
import static org.gms.server.bot.freemarket.BotEconomy.getEquipMarketValue;
import static org.gms.server.bot.freemarket.BotEconomy.priceAdjustmentRules;
import static org.gms.server.bot.freemarket.BotShopCatalog.generateCommonEquipList;
import static org.gms.server.bot.commands.SocialCommands.displayPlayerChatCommands;

/**
 * 卷轴强化 Bot（逐行移植自 SoloMapling ScrollingBot，238 行）。
 * 10-15s 滚动间隔 / 5-8min 售后等待 / Fisher-Yates 序列。
 */
public class ScrollingBot extends BotSM {
    private ScrollingBotState scrollingBotState = ScrollingBotState.RESET;
    private List<String> hint = Collections.singletonList(getChr().getName());
    private List<FMEquip> itemsToScroll = new ArrayList<>();
    private int scrollToUse;
    private int listIndex = 0;

    private int successfulScrolls;
    private int upgradeSlots;
    private boolean[] scrollResults;
    private int currentPosition = 0;

    private long startTime;
    private long endTime;

    public ScrollingBot(Character character) {
        super(character);
        dialoguePath = "ScrollingBotDialogue.yaml";
        botType = "ScrollingBot";
    }

    private void setScrollingBotState(ScrollingBotState state) {
        this.scrollingBotState = state;
    }

    private long waitUntilTime;

    private enum ScrollingBotState {
        RESET,
        SET_SCROLL_VARS,
        SCROLLING_ITEMS,
        ADVERTISE_SALES,
        WAITING_AFTER_AD
    }

    private void setScrollingItems() {
        itemsToScroll.clear();
        while (itemsToScroll.isEmpty()) {
            itemsToScroll = generateCommonEquipList("A");
        }
        FMEquip eq = itemsToScroll.get(listIndex);
        successfulScrolls = eq.getEquip().getLevel();
        Equip cleanItem = (Equip) generateCleanItemEquip(eq.getEquip().getItemId());
        upgradeSlots = cleanItem.getUpgradeSlots();
        scrollResults = generateExactScrollList(successfulScrolls, upgradeSlots);
        currentPosition = 0;
    }

    public boolean[] generateExactScrollList(int succScroll, int upgradeSlots) {
        // Creates a list of boolean that has a randomized success/fail order based on item's scrolled value

        boolean[] resultList = new boolean[upgradeSlots];
        for (int i = 0; i < succScroll; i++) {
            resultList[i] = true;
        }
        for (int i = succScroll; i < upgradeSlots; i++) {
            resultList[i] = false;
        }

        // Shuffle the array using Fisher-Yates algorithm
        for (int i = upgradeSlots - 1; i > 0; i--) {
            int randomIndex = (int) (Math.random() * (i + 1));
            boolean temp = resultList[i];
            resultList[i] = resultList[randomIndex];
            resultList[randomIndex] = temp;
        }
        return resultList;
    }

    private boolean scrollItem() {
        // Scrolls item 1 slot at a time

        // Get result from the current position
        if (currentPosition >= scrollResults.length) {
            return false; // Return false to indicate no more scrolls to process
        }

        boolean isSuccess = scrollResults[currentPosition];

        if (isSuccess) {
            botScrollSuccess(getChr());
            BotEmote(getChr(), 2);
        } else {
            botScrollFail(getChr());
            BotEmote(getChr(), 4);
        }
        currentPosition++;

        if (Math.random() < 0.20) {
            getDialogueHandler().executeBotFlavorDialogue(isSuccess ? "ScrollSuccess" : "ScrollFail", this);
        }

        waitForRandom(10000, 15000); // pace the next scroll 10-15s out (gated wait, no sleep)
        return true;
    }

    private void advertiseItemForSale() {
        setSellingItems();
    }

    private void setSellingItems() {
        Equip eqToSell = itemsToScroll.get(listIndex).getEquip();
        getTradeInventory().setItemForSaleMain(eqToSell);
        getTradeWants().resetTradeWants();
        int rawMesoValue = getEquipMarketValue(eqToSell);
        int adj = priceAdjustmentRules(rawMesoValue);
        getTradeWants().setMesoWanted(adj);
//        getTradeWants().setMesoWanted(0);
//        getTradeWants().addItemWanted(2022179, 2);
        String adBase = getRandomResolvedLine(this, "AdvertiseSale");
        String adLine = (adBase != null ? adBase : "").replace("%PRICE%", formatPriceToShorthand(adj));
        BotItemMegaphone(getChr(), adLine, eqToSell);
        setTradeMode(BotTradeSM.TradeMode.SELLING);
    }

    private void resetScrollingBotState() {
        setScrollingBotState(ScrollingBotState.RESET);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        if (getState() == BotState.TRADING) { // getTradeHandler().verifyTradePartner()
            return;
        }
        getDebugger().debugLoggingFull(String.format("%s ScrollingBotState: %s", this.getChr().getName(), scrollingBotState), String.format("%s", scrollingBotState));

        switch (scrollingBotState) {
            case RESET:
                resetScrollingBotState();
                setScrollingBotState(ScrollingBotState.SET_SCROLL_VARS);
                break;
            case SET_SCROLL_VARS:
                setScrollingItems();
                setScrollingBotState(ScrollingBotState.SCROLLING_ITEMS);
                break;
            case SCROLLING_ITEMS:
                boolean continueScrolling = scrollItem();
                checkForTrades();
                if (!continueScrolling) {
                    setScrollingBotState(ScrollingBotState.ADVERTISE_SALES);
                    break;
                }
                break;
            case ADVERTISE_SALES:
                if (Math.random() < 0.10) {
                    advertiseItemForSale();
                }
                waitUntilTime = System.currentTimeMillis() + (5 * 60 * 1000) + Randomizer.nextInt(3 * 60 * 1000 + 1); // 5-8 min
                setScrollingBotState(ScrollingBotState.WAITING_AFTER_AD);
                break;
            case WAITING_AFTER_AD:
                checkForTrades();
                if (System.currentTimeMillis() >= waitUntilTime) {
                    setScrollingBotState(ScrollingBotState.SET_SCROLL_VARS);
                }
                break;
            default:
                state = BotState.FINISHED;
                resetScrollingBotState();
                throw new IllegalStateException("Unexpected state: " + state);
        }
    }

    @Override
    public void displayCommands(Character chr) {
        displayPlayerChatCommands(chr, hint);
    }

    @Override
    public void processMessages() {
        try {
            ChatMessage message = MessageQueue.getInstance().getMessageWithTimeout("secondary", 1, TimeUnit.SECONDS);
            if (message == null) {
                return;
            }
//            handleBetCommand(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
