package org.gms.server.bot.types;

import org.gms.client.Character;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.commands.SocialCommands;
import org.gms.server.bot.freemarket.BotEconomy;
import org.gms.server.bot.freemarket.FMItem;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.messaging.ChatMessage;
import org.gms.server.bot.messaging.MessageQueue;
import org.gms.server.bot.trade.BotTradeSM;
import org.gms.util.Randomizer;

import java.awt.Point;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.gms.server.bot.BotTypeManager.BotType.NX_MERCHANT_BOT;
import static org.gms.server.bot.BotTypeManager.convertBotType;
import static org.gms.server.bot.freemarket.BotRand.getRandomElement;
import static org.gms.server.bot.freemarket.BotRand.rollChanceInverse;
import static org.gms.server.bot.freemarket.BotShopCatalog.generateDarkScrollsList;
import static org.gms.server.bot.freemarket.BotShopCatalog.generateScrollsList;

/**
 * 收购型商人 Bot（逐行移植自 SoloMapling BuyingMerchantBot，250 行）。
 * BUY_DISCOUNT 0.80-0.90，商品清单 scrolls/darkScrolls。
 */
public class BuyingMerchantBot extends BotSM {
    private BuyingState buyingState = BuyingState.RESET;
    private List<String> hint = Collections.singletonList(getChr().getName());
    private List<FMItem> itemsToBuy;
    private int itemIndex = 0;
    private boolean movedDuringAdvertise = false;

    private static final double BUY_DISCOUNT_MIN = 0.80;
    private static final double BUY_DISCOUNT_MAX = 0.90;

    private enum BuyingState {
        RESET,
        SELECT_ITEM,
        ADVERTISE,
        CHECK_TRADES,
        IDLE_ACTIONS
    }

    private static final List<String> FLAVOR_NODES = List.of("ScamMessages", "BeggingMessages", "RWTMessages", "FunnyMessages");

    public BuyingMerchantBot(Character character) {
        super(character);
        dialoguePath = "MerchantBotDialogue.yaml";
        botType = "MerchantBot";
    }

    private void resetState() {
        itemIndex = 0;
        loadItemList();
        buyingState = BuyingState.RESET;
    }

    private void loadItemList() {
        Supplier<List<FMItem>>[] generators = new Supplier[]{
                () -> generateScrollsList("A"),
                () -> generateDarkScrollsList("A")
        };
        itemsToBuy = generators[Randomizer.nextInt(generators.length)].get();
    }

    private FMItem getCurrentItem() {
        if (itemsToBuy == null || itemIndex >= itemsToBuy.size()) {
            return null;
        }
        return itemsToBuy.get(itemIndex);
    }

    private void selectNextItem() {
        if (itemsToBuy == null || itemsToBuy.isEmpty()) {
            loadItemList();
        }

        itemIndex++;
        if (itemIndex >= itemsToBuy.size()) {
            itemIndex = 0;
            loadItemList();
        }

        FMItem currItem = getCurrentItem();
        if (currItem == null) {
            return;
        }

        // Set up buying mode: we want the item, we offer mesos at a discount
        setTradeMode(BotTradeSM.TradeMode.BUYING);
        getTradeWants().resetTradeWants();
        getTradeWants().addItemWanted(currItem.getItemId(), 1);

        int marketPrice = currItem.getPrice();
        double discount = BUY_DISCOUNT_MIN + Randomizer.nextDouble() * (BUY_DISCOUNT_MAX - BUY_DISCOUNT_MIN);
        int buyPrice = BotEconomy.priceAdjustmentRules((int) (marketPrice * discount));
        getTradeWants().setMesoOffering(buyPrice);
        getTradeWants().setMesoWanted(0);

        resetLastTradeResult();
        resetLastTradedCharacter();
    }

    private void advertise() {
        FMItem itm = getCurrentItem();
        if (itm == null) {
            return;
        }
        String itemName = BotEconomy.getItemName(itm.getItemId());
        if (itemName != null) {
            String msg = buildBuyingMessage(itemName, getTradeWants().getMesoOffering());
            SocialCommands.BotSpeak(getChr(), msg);
        }
    }

    static String buildBuyingMessage(String itemName, int offerPrice) {
        List<String> prefixes = List.of("Buying", "B>", "B>>", "BUY>", "Buying>");
        List<String> suffixes = List.of("Trade Me", "PM me", "just trade me!", "hmu", "whisp me",
                "no lowball", "no noobs", "no scammers", "Pros only", "hotties only", "no nx h0es",
                "baddies only", "no weebs", "English Only", "No Spanish",
                "serious offers only", "dont waste my time", "legit only", "fair price only");

        String msg = getRandomElement(prefixes) + " " + itemName + " " + BotEconomy.formatPriceToShorthand(offerPrice) + " " + getRandomElement(suffixes);

        int fillerCount = Randomizer.nextInt(3);
        for (int i = 0; i < fillerCount; i++) {
            msg += " @@@@@@@@";
        }

        msg = msg.replace("[", "").replace("]", "");

        if (Randomizer.nextDouble() < 0.15) {
            msg = msg.toUpperCase();
        }
        return msg;
    }

    private boolean tryPlatformShuffleWhileAdvertising() {
        // gms 移植：PlatformPlacement 已移植（org.gms.server.bot.environment.platform）
        // 但换位 API 未接线，本类用 gcmove 踱步等价替代。
        if (rollChanceInverse(10)) {
            nudgeRandomly();
            return true;
        } else if (rollChanceInverse(20)) {
            nudgeRandomly();
            return true;
        } else if (rollChanceInverse(30)) {
            nudgeRandomly();
            return true;
        } else if (rollChanceInverse(70)) {
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

    private void handleIdleActions() {
        if (movedDuringAdvertise) {
            movedDuringAdvertise = false;
            return;
        }
        // gms 移植：等价 PlatformPlacement 换位。
        if (rollChanceInverse(10)) {
            nudgeRandomly();
        } else if (rollChanceInverse(20)) {
            nudgeRandomly();
        } else if (rollChanceInverse(30)) {
            nudgeRandomly();
        } else if (rollChanceInverse(70)) {
            nudgeRandomly();
        }
    }

    private boolean tryConvertToNXMerchant() {
        if (rollChanceInverse(100)) {
            convertBotType(getChr(), NX_MERCHANT_BOT);
            return true;
        }
        return false;
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
        getDebugger().debugLoggingFull(
                String.format("%s BuyingMerchantBot: %s", getChr().getName(), buyingState),
                String.format("%s", buyingState));

        switch (buyingState) {
            case RESET:
                resetState();
                buyingState = BuyingState.SELECT_ITEM;
                break;
            case SELECT_ITEM:
                selectNextItem();
                buyingState = BuyingState.ADVERTISE;
                break;
            case ADVERTISE:
                if (rollChanceInverse(25)) {
                    getDialogueHandler().executeBotFlavorDialogue(getRandomElement(FLAVOR_NODES), this);
                } else {
                    advertise();
                }
                movedDuringAdvertise = tryPlatformShuffleWhileAdvertising();
                buyingState = BuyingState.CHECK_TRADES;
                break;
            case CHECK_TRADES:
                checkForTrades();
                buyingState = BuyingState.IDLE_ACTIONS;
                break;
            case IDLE_ACTIONS:
                handleIdleActions();
                if (tryConvertToNXMerchant()) {
                    return;
                }
                buyingState = BuyingState.SELECT_ITEM;
                break;
            default:
                state = BotState.FINISHED;
                throw new IllegalStateException("Unexpected state: " + buyingState);
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
