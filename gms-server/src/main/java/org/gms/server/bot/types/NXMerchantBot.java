package org.gms.server.bot.types;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.server.Trade;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotTiming;
import org.gms.server.bot.commands.SocialCommands;
import org.gms.server.bot.messaging.ChatMessage;
import org.gms.server.bot.messaging.MessageQueue;
import org.gms.server.bot.trade.BotTradeSM;
import org.gms.util.Randomizer;

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
import static org.gms.server.bot.environment.platform.PlatformPlacement.botMoveToPlatformAnyUnoccupiedSpotDynamic;
import static org.gms.server.bot.environment.platform.PlatformPlacement.getCurrentPlatform;
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
                "卖 1万点券 5000万金币 要的密我",
                "出点券 1万点=5000万 先钱后货 爽快交易",
                "点券现货 1万点 5000万 秒发",
                "收金币换点券也行 1万点=5000万",
                "点券交易 从不坑人",
                "1万点券 5000万 长期有货",
                "急出点券 1万点 4800万 今天有效",
                "点券码现场给 安全靠谱",
                "1000万金币=2000点券 只换不卖",
                "出1万点券码 5000万 一手钱一手货",
                "点券不多咯 1万点 5000万 手慢无",
                "卖点券啦 1万点 5000万 交易窗见"
        );
        SocialCommands.BotSpeak(getChr(), getRandomElement(messages));
    }

    private void deliverNXCode() {
        if (getLastTradeResult() != Trade.TradeResult.SUCCESSFUL) {
            convertBack();
            return;
        }

        SocialCommands.BotSpeak(getChr(), "密你了。");
        sendMessengerInviteComplete(getChr(), getLastTradedCharacter());

        boolean accepted = waitForCondition(
                () -> isMessengerInviteAccepted(getChr(), getLastTradedCharacter())
        );

        if (accepted) {
            String nxCode = generateGiftCardCode();
            createCompleteNXCode(nxCode);

            botSendChatFull(getChr(), "这是 1万点券码 记好咯 兑换时别带横杠", 3000);
            botSendChatFull(getChr(), nxCode, 7000);
            botSendChatFull(getChr(), "玩得开心！", 2000);

            BotTiming.after(2000, () -> botLeaveMessenger(getChr()));
            waitFor(2500); // hold CONVERT_BACK until the messenger leave lands
        } else {
            SocialCommands.BotSpeak(getChr(), "你没收我的密语邀请... 那算了 有缘再见。");
        }

        resetLastTradeResult();
        resetLastTradedCharacter();
    }

    // Dynamic movement lands on the exact picked pixel, so the old nudgeAwayFromOverlap
    // band-aid (recorded paths piling bots onto fixed endpoints) is no longer needed here.
    private boolean tryPlatformShuffle() {
        // 换位 API 接线：占位感知换位（Dynamic 引擎落在精确像素，避免商人 bot 堆叠在同一"点位"）。
        if (rollChanceInverse(15)) {
            botMoveToPlatformAnyUnoccupiedSpotDynamic(getChr(), getCurrentPlatform(getChr()));
            return true;
        } else if (rollChanceInverse(40)) {
            botMoveToPlatformAnyUnoccupiedSpotDynamic(getChr(), getRandomElement(List.of("m1", "m5")));
            return true;
        }
        return false;
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
