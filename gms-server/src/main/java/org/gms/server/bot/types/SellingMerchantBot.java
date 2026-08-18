package org.gms.server.bot.types;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
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
import static org.gms.server.bot.freemarket.BotShopCatalog.generateItem;
import static org.gms.server.bot.freemarket.BotShopCatalog.generatePotionsList;
import static org.gms.server.bot.freemarket.BotShopCatalog.generateScrollsList;
import static org.gms.server.bot.freemarket.BotShopCatalog.generateThiefStarsList;

/**
 * 出售型商人 Bot（逐行移植自 SoloMapling SellingMerchantBot，250 行）。
 * 商品清单 scrolls/darkScrolls/thiefStars/potions，定价 marketValue*0.9。
 */
public class SellingMerchantBot extends BotSM {
    private SellingState sellingState = SellingState.RESET;
    private List<String> hint = Collections.singletonList(getChr().getName());
    private List<FMItem> itemsToSell;
    private int itemIndex = 0;
    private boolean movedDuringAdvertise = false;

    private enum SellingState {
        RESET,
        SELECT_ITEM,
        ADVERTISE,
        CHECK_TRADES,
        IDLE_ACTIONS
    }

    private static final List<String> FLAVOR_NODES = List.of("ScamMessages", "BeggingMessages", "RWTMessages", "FunnyMessages");

    public SellingMerchantBot(Character character) {
        super(character);
        dialoguePath = "MerchantBotDialogue.yaml";
        botType = "MerchantBot";
    }

    private void resetState() {
        itemIndex = 0;
        loadItemList();
        sellingState = SellingState.RESET;
    }

    private void loadItemList() {
        Supplier<List<FMItem>>[] generators = new Supplier[]{
                () -> generateScrollsList("A"),
                () -> generateDarkScrollsList("A"),
                () -> generateThiefStarsList("A"),
                () -> generatePotionsList("S")
        };
        itemsToSell = generators[Randomizer.nextInt(generators.length)].get();
    }

    private FMItem getCurrentItem() {
        if (itemsToSell == null || itemIndex >= itemsToSell.size()) {
            return null;
        }
        return itemsToSell.get(itemIndex);
    }

    private void selectNextItem() {
        if (itemsToSell == null || itemsToSell.isEmpty()) {
            loadItemList();
        }

        itemIndex++;
        if (itemIndex >= itemsToSell.size()) {
            itemIndex = 0;
            loadItemList();
        }

        FMItem currItem = getCurrentItem();
        if (currItem == null) {
            return;
        }

        Item item = generateItem(currItem.getItemId(), 1, 1);
        getTradeInventory().setItemForSaleMain(item);
        getTradeWants().resetTradeWants();
        int rawValue = BotEconomy.getItemMarketValue(item);
        int adjValue = BotEconomy.priceAdjustmentRules((int) (rawValue * 0.9));
        getTradeWants().setMesoWanted(adjValue);
        setTradeMode(BotTradeSM.TradeMode.SELLING);

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
            String msg = buildSellingMessage(itemName);
            SocialCommands.BotSpeak(getChr(), msg);
        }
    }

    static String buildSellingMessage(String itemName) {
        List<String> prefixes = List.of("卖", "出", "卖>", "出>", "出售", "低价出",
                "白菜价", "甩卖", "清仓甩", "诚意出", "今天必出", "挂店出");
        List<String> suffixes = List.of("带价M我", "不黑", "可小刀", "大刀勿扰", "记者勿扰",
                "骗子勿扰", "先钱后货", "诚心要", "速度交易", "只换金币",
                "小刀可谈", "压价免谈", "爽快来", "秒回在线", "不换只卖",
                "问价带价", "别墨迹", "长期卖", "今晚必出", "急出回血",
                "自用脱手", "东西干净", "价格公道", "先到先得", "当面交易",
                "老顾客都知道", "自用货", "包真不包退", "可验货", "看上直接交易",
                "喊价不回", "回血价", "一手货源", "老店放心", "痛快交易",
                "差不多就出", "价高者得", "挂着就是卖", "好货不多", "手慢无");

        String msg = getRandomElement(prefixes) + " " + itemName + " " + getRandomElement(suffixes);

        msg = msg.replace("[", "").replace("]", "");
        return msg;
    }

    // Dynamic movement lands on the exact picked pixel, so the old nudgeAwayFromOverlap
    // band-aid (recorded paths piling bots onto fixed endpoints) is no longer needed here.
    private boolean tryPlatformShuffleWhileAdvertising() {
        // gms 移植：源按平台聚合点换位（PlatformPlacement.botMoveToPlatformAnyUnoccupiedSpotDynamic /
        // getCurrentPlatform / getMainPlatformIds）。PlatformPlacement 已移植
        // （org.gms.server.bot.environment.platform）但换位 API 未接线，本类用 gcmove 踱步等价替代。
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
        // gms 移植：等价 PlatformPlacement 换位（见 tryPlatformShuffleWhileAdvertising 注释）。
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
                String.format("%s SellingMerchantBot: %s", getChr().getName(), sellingState),
                String.format("%s", sellingState));

        switch (sellingState) {
            case RESET:
                resetState();
                sellingState = SellingState.SELECT_ITEM;
                break;
            case SELECT_ITEM:
                selectNextItem();
                sellingState = SellingState.ADVERTISE;
                break;
            case ADVERTISE:
                if (rollChanceInverse(25)) {
                    getDialogueHandler().executeBotFlavorDialogue(getRandomElement(FLAVOR_NODES), this);
                } else {
                    advertise();
                }
                movedDuringAdvertise = tryPlatformShuffleWhileAdvertising();
                sellingState = SellingState.CHECK_TRADES;
                break;
            case CHECK_TRADES:
                checkForTrades();
                sellingState = SellingState.IDLE_ACTIONS;
                break;
            case IDLE_ACTIONS:
                handleIdleActions();
                if (tryConvertToNXMerchant()) {
                    return;
                }
                sellingState = SellingState.SELECT_ITEM;
                break;
            default:
                state = BotState.FINISHED;
                throw new IllegalStateException("Unexpected state: " + sellingState);
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
