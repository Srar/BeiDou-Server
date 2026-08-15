package org.gms.server.bot.types;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.server.Trade;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotTiming;
import org.gms.server.bot.commands.SocialCommands;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.messaging.ChatMessage;
import org.gms.server.bot.messaging.MessageQueue;
import org.gms.server.bot.trade.BotTradeSM;
import org.gms.util.Randomizer;

import java.awt.Point;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.gms.server.bot.BotTypeManager.BotType.BUYING_MERCHANT_BOT;
import static org.gms.server.bot.BotTypeManager.BotType.SELLING_MERCHANT_BOT;
import static org.gms.server.bot.BotTypeManager.convertBotType;
import static org.gms.server.bot.commands.MapleMessengerCommands.botLeaveMessenger;
import static org.gms.server.bot.commands.MapleMessengerCommands.botSendChatFull;
import static org.gms.server.bot.commands.MapleMessengerCommands.isMessengerInviteAccepted;
import static org.gms.server.bot.commands.MapleMessengerCommands.sendMessengerInviteComplete;
import static org.gms.server.bot.freemarket.BotRand.getRandomElement;
import static org.gms.server.bot.freemarket.BotRand.rollChanceInverse;
import static org.gms.server.bot.freemarket.BotRand.waitForCondition;
import static org.gms.server.bot.freemarket.BotShopCatalog.generateItem;
import static org.gms.server.bot.freemarket.NXCodeManager.createCompleteNXCode;
import static org.gms.server.bot.freemarket.NXCodeManager.generateGiftCardCode;

/**
 * NX 码商人 Bot（逐行移植自 SoloMapling NXMerchantBot，205 行）。
 * MAX_ADVERTISE_CYCLES=15，售价 50m，filler 物品 4031865。
 */
public class NXMerchantBot extends BotSM {
    private NXState nxState = NXState.SETUP;
    private List<String> hint = Collections.singletonList(getChr().getName());
    private int advertiseCycles = 0;
    private static final int MAX_ADVERTISE_CYCLES = 15;

    private enum NXState {
        SETUP,
        ADVERTISE,
        CHECK_TRADES,
        DELIVER_CODE,
        CONVERT_BACK
    }

    private static final List<String> FLAVOR_NODES = List.of("ScamMessages", "BeggingMessages", "RWTMessages", "FunnyMessages");

    public NXMerchantBot(Character character) {
        super(character);
        dialoguePath = "MerchantBotDialogue.yaml";
        botType = "MerchantBot";
    }

    private void setupNXSale() {
        // Use a filler item as the visual representation in trade
        Item filler = generateItem(4031865, 1, 100);
        getTradeInventory().setItemForSaleMain(filler);
        getTradeWants().resetTradeWants();
        int fiftyMill = 50_000_000;
        getTradeWants().setMesoWanted(fiftyMill);
        setTradeMode(BotTradeSM.TradeMode.SELLING);
        resetLastTradeResult();
        resetLastTradedCharacter();
    }

    private void advertise() {
        List<String> messages = List.of(
                "Selling 10k nx cash code, 50m TRADE ME!",
                "S> 10k NX code 50m, no lowballs",
                "NX CODE 10k >> 50m trade me!! legit only",
                "10k nx cash code for 50m, Pros only",
                "SELLING NX 10K CODE!! 50m!! no scammers",
                "S>> 10,000 NX code, 50m, serious offers only",
                "got nx codes, 10k for 50m, trade me fast"
        );
        SocialCommands.BotSpeak(getChr(), getRandomElement(messages));
    }

    private void deliverNXCode() {
        if (getLastTradeResult() != Trade.TradeResult.SUCCESSFUL) {
            convertBack();
            return;
        }

        SocialCommands.BotSpeak(getChr(), "messaging you.");
        sendMessengerInviteComplete(getChr(), getLastTradedCharacter());

        boolean accepted = waitForCondition(
                () -> isMessengerInviteAccepted(getChr(), getLastTradedCharacter())
        );

        if (accepted) {
            String nxCode = generateGiftCardCode();
            createCompleteNXCode(nxCode);

            botSendChatFull(getChr(), "here is the 10k nx code... be sure to write it down. Remember to NOT include dashes", 3000);
            botSendChatFull(getChr(), nxCode, 7000);
            botSendChatFull(getChr(), "enjoy it!", 2000);

            BotTiming.after(2000, () -> botLeaveMessenger(getChr()));
            waitFor(2500); // hold CONVERT_BACK until the messenger leave lands
        } else {
            SocialCommands.BotSpeak(getChr(), "You didn't accept the messenger invite... too bad noob.");
        }

        resetLastTradeResult();
        resetLastTradedCharacter();
    }

    // Dynamic movement lands on the exact picked pixel, so the old nudgeAwayFromOverlap
    // band-aid (recorded paths piling bots onto fixed endpoints) is no longer needed here.
    private boolean tryPlatformShuffle() {
        // gms 移植：等价 PlatformPlacement 换位（见 SellingMerchantBot 同类注释）。
        if (rollChanceInverse(15)) {
            nudgeRandomly();
            return true;
        } else if (rollChanceInverse(40)) {
            nudgeRandomly();
            return true;
        }
        return false;
    }

    private void nudgeRandomly() {
        Character chr = getChr();
        Point pos = chr.getPosition();
        int dx = Randomizer.nextInt(41) - 20;
        GCMovement.move(chr, pos.x + dx, pos.y);
    }

    private void convertBack() {
        if (Randomizer.nextBoolean()) {
            convertBotType(getChr(), SELLING_MERCHANT_BOT);
        } else {
            convertBotType(getChr(), BUYING_MERCHANT_BOT);
        }
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        if (getState() == BotState.TRADING) {
            return;
        }
        // Skip straight to delivery if trade completed while we were in TRADING state
        if (getLastTradeResult() == Trade.TradeResult.SUCCESSFUL && nxState != NXState.DELIVER_CODE && nxState != NXState.CONVERT_BACK) {
            nxState = NXState.DELIVER_CODE;
        }

        getDebugger().debugLoggingFull(
                String.format("%s NXMerchantBot: %s", getChr().getName(), nxState),
                String.format("%s", nxState));

        switch (nxState) {
            case SETUP:
                setupNXSale();
                nxState = NXState.ADVERTISE;
                break;
            case ADVERTISE:
                // 4% (1/25) Chance to advertise flavor, 96% chance to advertise NX
                if (rollChanceInverse(25)) {
                    getDialogueHandler().executeBotFlavorDialogue(getRandomElement(FLAVOR_NODES), this);
                } else {
                    advertise();
                }
                nxState = NXState.CHECK_TRADES;
                break;
            case CHECK_TRADES:
                checkForTrades();
                advertiseCycles++;
                tryPlatformShuffle();
                if (getLastTradeResult() == Trade.TradeResult.SUCCESSFUL) {
                    nxState = NXState.DELIVER_CODE;
                } else if (advertiseCycles >= MAX_ADVERTISE_CYCLES) {
                    nxState = NXState.CONVERT_BACK;
                } else {
                    nxState = NXState.ADVERTISE;
                }
                break;
            case DELIVER_CODE:
                deliverNXCode();
                nxState = NXState.CONVERT_BACK;
                break;
            case CONVERT_BACK:
                convertBack();
                break;
            default:
                state = BotState.FINISHED;
                throw new IllegalStateException("Unexpected state: " + nxState);
        }
    }

    @Override
    public void displayCommands(Character chr) {
        SocialCommands.displayPlayerChatCommands(chr, hint);
    }

    @Override
    public void processMessages() {
        try {
            ChatMessage message = MessageQueue.getInstance().getMessageWithTimeout("secondary", 1, TimeUnit.SECONDS);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
