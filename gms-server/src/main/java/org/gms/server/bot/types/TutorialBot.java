package org.gms.server.bot.types;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.inventory.BodyPart;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Item;
import org.gms.constants.game.CharacterStance;
import org.gms.server.ItemInformationProvider;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotLogic;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotTiming;
import org.gms.server.bot.commands.DropCommands;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.messaging.ChatMessage;
import org.gms.server.bot.messaging.MessageQueue;
import org.gms.server.bot.trade.BotTradeCommands;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.util.Randomizer;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.gms.server.bot.BotHelpers.convertItemIdToName;
import static org.gms.server.bot.BotLogic.checkForItemOnFloor;
import static org.gms.server.bot.BotLogic.readPlayerEquipBySlotName;
import static org.gms.server.bot.BotLogic.waitForPlayerInRange;
import static org.gms.server.bot.commands.DropCommands.botLootSelectedItems;
import static org.gms.server.bot.commands.DropCommands.botThrowEquipsInARow;
import static org.gms.server.bot.commands.SocialCommands.BotEmote;
import static org.gms.server.bot.commands.SocialCommands.BotSpeak;
import static org.gms.server.bot.commands.SocialCommands.displayPlayerChatCommands;
import static org.gms.server.bot.commands.VFXCommands.botScrollSuccess;
import static org.gms.server.bot.freemarket.NXCodeManager.createCompleteNXCode;
import static org.gms.server.bot.freemarket.NXCodeManager.generateGiftCardCode;

/**
 * 引导型 Bot（逐行移植自 SoloMapling TutorialBot，607 行）。
 * TRADE_MESOS=1B / POWER_ELIXIR=2000005 / ONYX_APPLE=2022179 / ILBI=2070006。
 * gms 底座差异：录制引擎移动命令（botFaceTowardsPoint/pathFinderBeta/BotMoveStream）
 * 用 gcmove/broadcastStance 等价 + TODO 占位。
 */
@Slf4j
public class TutorialBot extends BotSM {
    private TutorialBotState tutorialBotState = TutorialBotState.RESET;
    private Boolean runTutorial;
    private Boolean tutPicked;
    private Boolean wantsAdmin;
    private String generatedNXCode;
    private String generatedNXCode2;
    private List<Integer> noobsHelped = new ArrayList<>();
    private List<String> hint = Collections.singletonList(getChr().getName());

    private int wepId;
    private Equip playersWep;

    private long startTime;
    private long endTime;

    // Mark of Beta, White Maple Dana, Newspaper Hat
    final private int[] items = {1002419, 1002515, 1002418};

    private static final int POWER_ELIXIR = 2000005;
    private static final int ONYX_APPLE = 2022179;
    private static final int TOWN_SCROLL_HENESYS = 2030000;
    private static final int ILBI_STARS = 2070006;
    private static final int TRADE_MESOS = 1_000_000_000;

    private static final int MAPLE_KANDAYO = 1472032;
    private static final int BLUE_SAUNA_ROBE = 1050018;
    private static final int RED_SAUNA_ROBE = 1051017;

    public TutorialBot(Character character) {
        super(character);
        dialoguePath = "TutorialBotDialogue.yaml";
        botType = "TutorialBot";
    }

    private void setTutorialBotState(TutorialBot.TutorialBotState state) {
        this.tutorialBotState = state;
    }

    private void resetTutorialBotState() {
        setTutorialBotState(TutorialBotState.RESET);
        runTutorial = null;
        tutPicked = null;
        wantsAdmin = null;
        generatedNXCode = null;
        generatedNXCode2 = null;
        hint = Collections.singletonList(getChr().getName());
        wepId = 0;
        playersWep = null;

        startTime = System.currentTimeMillis();
        endTime = 0;
    }

    private enum TutorialBotState {
        RESET,
        WAIT_FOR_PLAYER_IN_RANGE,
        START,
        INQUIRE,
        WAIT_FOR_INQUIRY_RESPONSE,
        ASK_ADMIN,
        WAIT_ADMIN_RESPONSE,
        GRANT_ADMIN,
        GIFT_TRADE,
        WAIT_TRADE_ACCEPT,
        TUTORIAL_1,
        TUTORIAL_2,
        TUTORIAL_2_WAIT_PICK,
        TUTORIAL_3,
        TUTORIAL_3_WAIT_DROP,
        TUTORIAL_3_UPGRADE,
        SEND_OFF,
        RETURN
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        getDebugger().debugLoggingFull(String.format("%s TutorialBotState: %s", this.getChr().getName(), tutorialBotState), String.format("%s", tutorialBotState));
        switch (tutorialBotState) {
            case RESET:
                resetTutorialBotState();
                setTutorialBotState(TutorialBotState.WAIT_FOR_PLAYER_IN_RANGE);
                break;
            case WAIT_FOR_PLAYER_IN_RANGE:
                waitForNewPlayer();
                botWaitingForPlayerFlavorText();
                if (getInteractors().getRespondant() != null) {
                    setTutorialBotState(TutorialBotState.START);
                }
                break;
            case START:
                greetPlayer();
                setTutorialBotState(TutorialBotState.INQUIRE);
                break;
            case INQUIRE:
                inquirePlayer();
                setTutorialBotState(TutorialBotState.WAIT_FOR_INQUIRY_RESPONSE);
                break;
            case WAIT_FOR_INQUIRY_RESPONSE:
                waitForResponse();
                if (runTutorial == null) {
                    return;
                }
                if (runTutorial) {
                    setTutorialBotState(TutorialBotState.ASK_ADMIN);
                } else {
                    setTutorialBotState(TutorialBotState.SEND_OFF);
                }
                break;
            case ASK_ADMIN:
                askAdmin();
                setTutorialBotState(TutorialBotState.WAIT_ADMIN_RESPONSE);
                break;
            case WAIT_ADMIN_RESPONSE:
                waitForResponse();
                if (wantsAdmin == null) {
                    return;
                }
                if (wantsAdmin) {
                    setTutorialBotState(TutorialBotState.GRANT_ADMIN);
                } else {
                    setTutorialBotState(TutorialBotState.TUTORIAL_1);
                }
                break;
            case GRANT_ADMIN:
                grantAdmin();
                setTutorialBotState(TutorialBotState.GIFT_TRADE);
                break;
            case GIFT_TRADE:
                initiateGiftTrade();
                setTutorialBotState(TutorialBotState.WAIT_TRADE_ACCEPT);
                break;
            case WAIT_TRADE_ACCEPT:
                if (waitForTradeAccept()) {
                    placeTradeItems();
                    setTutorialBotState(TutorialBotState.TUTORIAL_1);
                }
                break;
            case TUTORIAL_1:
                tutorial_1();
                setTutorialBotState(TutorialBotState.TUTORIAL_2);
                break;
            case TUTORIAL_2:
                tutorial_2();
                setTutorialBotState(TutorialBotState.TUTORIAL_2_WAIT_PICK);
                break;
            case TUTORIAL_2_WAIT_PICK:
                waitForResponse();
                if (tutPicked == null) {
                    return;
                }
                if (tutPicked) {
                    setTutorialBotState(TutorialBotState.TUTORIAL_3);
                }
                waitFor(2000); // beat before TUTORIAL_3 ticks
                break;
            case TUTORIAL_3:
                tutorial_3();
                if (this.wepId != 0) {
                    setTutorialBotState(TutorialBotState.TUTORIAL_3_WAIT_DROP);
                } else {
                    setTutorialBotState(TutorialBotState.SEND_OFF);
                }
                break;
            case TUTORIAL_3_WAIT_DROP:
                waitForPlayerToDropWeapon();
                if (this.playersWep == null) {
                    return;
                }
                setTutorialBotState(TutorialBotState.TUTORIAL_3_UPGRADE);
                break;
            case TUTORIAL_3_UPGRADE:
                upgradeAndReturnWeapon();
                setTutorialBotState(TutorialBotState.SEND_OFF);
                break;
            case SEND_OFF:
                send_off();
                setTutorialBotState(TutorialBotState.RETURN);
                break;
            case RETURN:
                returnOrigin();
                getInteractors().resetRespondant();
                setTutorialBotState(TutorialBotState.RESET);
                break;
            default:
                state = BotState.FINISHED;
                resetTutorialBotState();
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
            handleMessage(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void waitForNewPlayer() {
        Character noob = waitForPlayerInRange(getChr(), 500, 150);
        if (noob != null) {
            if (noobsHelped.contains(noob.getId())) {
                return;
            }
            getInteractors().setRespondant(noob);
        }
    }

    /** 中文否定词组识别：只匹配完整否定词，避免「不错」「不好意思」误命中。 */
    private static boolean containsNegation(String content) {
        String[] negations = {"不想", "不用", "不要", "不了", "不去", "不玩", "不买", "不需要", "算了吧"};
        for (String n : negations) {
            if (content.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private void handleInquiryResponse(String content) {
        // 触发词识别：保留英文 yes/no 兼容老玩家，扩展中文；先判否定（词组级，避免「不错」误命中）
        if (content.contains("no") || content.contains("否") || containsNegation(content)) {
            runTutorial = false;
        } else if (content.contains("yes") || content.contains("是")) {
            runTutorial = true;
        }
    }

    private void handleSelectedHat(String itemName, Integer itemIdSelected) {
        BotSpeak(getChr(), String.format("你选的是 %s！收好咯！", itemName));
        tutPicked = true;
        int[] leftoverHats = Arrays.stream(items).filter(item -> item != itemIdSelected).toArray();
        BotTiming.after(2500, () -> lootLeftoverHats(leftoverHats));
        waitFor(3000); // hold ticks until the leftover-hat loot lands
    }

    private void handleTutPickResponse(String content) {
        HashMap<String, Integer> itemNameToIdMap = BotLogic.makeItemIdFromNameMap(items);
        for (int itemId : items) {
            String itemName = convertItemIdToName(itemId).toLowerCase();
            if (content.contains(itemName)) {
                Integer itemIdSelected = itemNameToIdMap.get(itemName);
                handleSelectedHat(itemName, itemIdSelected);
                return;
            }
        }
        // If no matching item is found
        BotSpeak(getChr(), "名字打对了吗？再检查一下");
        BotEmote(getChr(), 6);
    }

    private void lootLeftoverHats(int[] itemsToLoot) {
        botLootSelectedItems(getChr(), itemsToLoot);
    }

    private void handleMessage(ChatMessage message) {
        if (!getInteractors().isMessageFromRespondant(message)) {
            return;
        }
        String content = message.getContent().toLowerCase();
        if (tutorialBotState == TutorialBotState.WAIT_FOR_INQUIRY_RESPONSE) {
            handleInquiryResponse(content);
        } else if (tutorialBotState == TutorialBotState.WAIT_ADMIN_RESPONSE) {
            handleAdminResponse(content);
        } else if (tutorialBotState == TutorialBotState.TUTORIAL_2_WAIT_PICK) {
            handleTutPickResponse(content);
        }
    }

    private void handleAdminResponse(String content) {
        // 触发词识别：保留英文 yes/no 兼容老玩家，扩展中文；先判否定（词组级，避免「不错」误命中）
        if (content.contains("no") || content.contains("否") || containsNegation(content)) {
            wantsAdmin = false;
        } else if (content.contains("yes") || content.contains("是")) {
            wantsAdmin = true;
        }
    }

    private void askAdmin() {
        getDialogueHandler().executeBotDialogue("AskAdmin", TutorialBot.this);
        hint = List.of("是", "否");
        displayCommands(getInteractors().getRespondant());
        startTime = System.currentTimeMillis();
        endTime = startTime + (30 * 1000);
    }

    private void grantAdmin() {
        Character player = getInteractors().getRespondant();

        player.setLevel(149);
        player.levelUp(false);

        player.changeJob(Job.getById(412));
        player.equipChanged();

        player.setGMLevel(6);
        player.getClient().setGMLevel(6);

        player.gainAp(750, false);
        player.gainSp(450, 0, false);

        player.updateMaxHpMaxMp(10000, 10000);
        player.updateHpMp(10000);

        generatedNXCode = generateGiftCardCode();
        createCompleteNXCode(generatedNXCode, 25000);

        generatedNXCode2 = generateGiftCardCode();
        createCompleteNXCode(generatedNXCode2, 25000);

        Map<String, String> replacements = Map.of(
                "%NX_CODE%", generatedNXCode,
                "%NX_CODE_2%", generatedNXCode2
        );
        getDialogueHandler().executeBotDialogueWithReplacementStrings("GrantAdmin", replacements, TutorialBot.this);
    }

    private void initiateGiftTrade() {
        Character player = getInteractors().getRespondant();
        getDialogueHandler().executeBotDialogue("GiftTradeIntro", TutorialBot.this);
        BotTiming.after(2000, () -> BotTradeCommands.sendTradeRequestToPlayer(getChr(), player));
        waitFor(2500); // trade request lands at +2s
        startTime = System.currentTimeMillis();
        endTime = startTime + (30 * 1000);
    }

    private boolean waitForTradeAccept() {
        if (getChr().getTrade() == null) {
            return false;
        }
        if (getChr().getTrade().isFullTrade()) {
            return true;
        }
        if (System.currentTimeMillis() > endTime) {
            BotTradeCommands.cancelTrade(getChr());
            BotSpeak(getChr(), "没事 礼物下次还在 随时来找我");
            setTutorialBotState(TutorialBotState.TUTORIAL_1);
            return false;
        }
        return false;
    }

    private void placeTradeItems() {
        Character player = getInteractors().getRespondant();
        int robeId = player.isMale() ? BLUE_SAUNA_ROBE : RED_SAUNA_ROBE;

        // ~6s of gift choreography on a chain; dies quietly if the player cancels
        BotTiming.chain()
                .stopUnless(() -> getChr().getTrade() != null)
                .pause(1000)
                .run(() -> BotTradeCommands.writeTradeChat(getChr(), "一点新手礼 拿去起步用！"))
                .pause(1500)
                .run(() -> BotTradeCommands.setMeso(getChr(), TRADE_MESOS))
                .pause(500)
                .run(() -> BotTradeCommands.addItemToTrade(getChr(), POWER_ELIXIR, 1000, 1))
                .pause(300)
                .run(() -> BotTradeCommands.addItemToTrade(getChr(), ONYX_APPLE, 100, 2))
                .pause(300)
                .run(() -> BotTradeCommands.addItemToTrade(getChr(), TOWN_SCROLL_HENESYS, 100, 3))
                .pause(300)
                .run(() -> BotTradeCommands.addItemToTrade(getChr(), ILBI_STARS, 800, 4))
                .pause(300)
                .run(() -> BotTradeCommands.addCleanEquipToTrade(getChr(), MAPLE_KANDAYO, 5))
                .pause(300)
                .run(() -> BotTradeCommands.addCleanEquipToTrade(getChr(), robeId, 6))
                .pause(500)
                .run(() -> BotTradeCommands.writeTradeChat(getChr(), "10亿金币 药水 卷轴 飞镖和装备 都是你的！"))
                .pause(1000)
                .run(() -> BotTradeCommands.confirmTrade(getChr()))
                .start();
        waitFor(7500); // hold TUTORIAL_1 until the gift trade confirms
    }

    private void greetPlayer() {
        faceTowardsPoint(getInteractors().getRespondant().getPosition());
        Map<String, String> replacements = Map.of("%RESP_NAME%", getInteractors().getRespondant().getName());
        getDialogueHandler().executeBotDialogueWithReplacementStrings("Greeting", replacements, TutorialBot.this);
    }

    private void faceTowardsPoint(Point target) {
        // gms 移植：源 MovementCommands.botFaceTowardsPoint 已随 MovementCommands 移植
        // （org.gms.server.bot.replay 包）；此处保留等价内联：按目标点方向广播站立朝向。
        boolean left = target.x < getChr().getPosition().x;
        getChr().broadcastStance(left ? CharacterStance.STAND_LEFT_STANCE : CharacterStance.STAND_RIGHT_STANCE);
    }

    private void inquirePlayer() {
        getDialogueHandler().executeBotDialogue("Inquiry", TutorialBot.this);
        hint = List.of("是", "否");
        displayCommands(getInteractors().getRespondant());
        startTime = System.currentTimeMillis();
        endTime = startTime + (20 * 1000);
    }

    private void waitForResponse() {
        if (System.currentTimeMillis() < endTime) {
            processMessages();
        } else {
            BotSpeak(getChr(), "准备好了再来跟我说一声");
            state = BotState.FINISHED;
            resetTutorialBotState();
        }
    }

    private void tutorial_1() {
        Map<String, String> replacements = Map.of("%CHR_NAME%", getChr().getName());
        getDialogueHandler().executeBotDialogueWithReplacementStrings("Tutorial_1", replacements, TutorialBot.this);
    }

    private void tutorial_2() {
        tutorial_2_dialog();
        tutorial_2_drops();
        startTime = System.currentTimeMillis();
        endTime = startTime + (40 * 1000);
    }

    private void tutorial_2_dialog() {
        getDialogueHandler().executeBotDialogue("Tutorial_2", TutorialBot.this);
    }

    private void tutorial_2_drops() {
        Point centerPos = getChr().getPosition();
        botThrowEquipsInARow(getChr(), items, centerPos, 75);
        displayTutorialDropOptions();
    }

    private void displayTutorialDropOptions() {
        List<String> tutDropOptions = new ArrayList<>();
        for (int itemId : items) {
            String itemName = convertItemIdToName(itemId);
            tutDropOptions.add(itemName);
        }
        hint = tutDropOptions;
        displayCommands(getInteractors().getRespondant());
    }

    private void tutorial_3() {
        this.wepId = analyzePlayersWeapon();
    }

    private void waitForPlayerToDropWeapon() {
        this.playersWep = lootPlayersDroppedWeapon(this.wepId);
    }

    // Deliberate synchronous choreography (same ruling as BlackjackDealerBot):
    // the dialogue steps block for YAML-configured durations, so this strictly
    // sequential script must run on its own tick — a chain + guessed waitFor
    // releases the FSM mid-script (bot was seen doing two states at once).
    private void upgradeAndReturnWeapon() {
        BotHelpers.blockingSleep(2000);
        lootDialog(this.wepId);
        Equip scrolledWep = scrollPlayersDroppedWeapon(this.playersWep);
        returnUpgradedWeapon(scrolledWep);
    }

    private int analyzePlayersWeapon() {
        int playersWeaponId;
        try {
            playersWeaponId = readPlayerEquipBySlotName(getInteractors().getRespondant(), BodyPart.WEAPON).getItemId();
        } catch (Exception e) {
            log.debug("TutorialBot.analyzePlayersWeapon failed for bot {}", getChr().getId(), e);
            return 0;
        }
        String weaponName = convertItemIdToName(playersWeaponId);
        Map<String, String> replacements = Map.of("%WPN_NAME%", weaponName);
        getDialogueHandler().executeBotDialogueWithReplacementStrings("AnalyzePlayersWeapon", replacements, TutorialBot.this);
        return playersWeaponId;
    }

    private Equip lootPlayersDroppedWeapon(int weaponId) {
        List<MapObject> playersWeaponOnFloor = checkForItemOnFloor(getInteractors().getRespondant(), getInteractors().getRespondant().getPosition(), weaponId);
        Equip itm = null;
        for (MapObject mapObj : playersWeaponOnFloor) {
            MapItem mapItem = (MapItem) mapObj;
            if (mapItem.getItemId() == weaponId) {
                Item itemFound = mapItem.getItem();
                itm = (Equip) itemFound;
            }
        }
        if (!playersWeaponOnFloor.isEmpty()) {
            DropCommands.lootItemListOnFloor(getChr(), playersWeaponOnFloor);
        }

        return itm;
    }

    private void lootDialog(int weaponId) {
        String weaponName = convertItemIdToName(weaponId);
        Map<String, String> replacements = Map.of("%WPN_NAME%", weaponName);
        getDialogueHandler().executeBotDialogueWithReplacementStrings("LootDialogue", replacements, TutorialBot.this);
        BotHelpers.blockingSleep(6000);
    }

    private Equip scrollPlayersDroppedWeapon(Equip playersWep) {
        Item scrolledWep = ItemInformationProvider.getInstance().scrollEquipWithId(playersWep, 2043003, false, 0, false);
        scrolledWep.setOwner(getInteractors().getRespondant().getName());
        botScrollSuccess(getChr());
        BotEmote(getChr(), 2);
        BotHelpers.blockingSleep(3000);
        upgradeDialog();
        return (Equip) scrolledWep;
    }

    private void upgradeDialog() {
        getDialogueHandler().executeBotDialogue("UpgradeDialogue", TutorialBot.this);
    }

    private void returnUpgradedWeapon(Equip scrolledWep) {
        DropCommands.botThrowEquip(getChr(), scrolledWep, getInteractors().getRespondant().getPosition());
        returnDialog();
    }

    private void returnDialog() {
        getDialogueHandler().executeBotDialogue("ReturnDialogue", TutorialBot.this);
    }

    // Deliberate synchronous choreography — dialogue durations are YAML-driven.
    private void send_off() {
        send_off_dialog();
        escort_player_to_portal();
        send_off_parting_gift();
    }

    private void send_off_dialog() {
        getDialogueHandler().executeBotDialogue("SendoffDialogue", TutorialBot.this);
    }

    private void escort_player_to_portal() {
        // gms 移植：源 pathFinderBeta(getChr(), new Point(927,485))（录制引擎）；
        // 用 gcmove 图导航等价。TODO(录制引擎)：落地后恢复 pathFinderBeta。
        GCMovement.move(getChr(), 927, 485);
        BotHelpers.blockingSleep(2000);
    }

    private void send_off_parting_gift() {
        getDialogueHandler().executeBotDialogue("SendoffPartingGiftDialogue", TutorialBot.this);
        DropCommands.botThrowItem(getChr(), 2022179, getInteractors().getRespondant().getPosition());
        noobsHelped.add(getInteractors().getRespondant().getId());
        waitFor(1000); // trailing beat before RETURN ticks
    }

    private void returnOrigin() {
        returnToWaitingSpot();
    }

    private void returnToWaitingSpot() {
        // gms 移植：源读录制路径（getMovementRecording("tutorial2") + BotMoveStream）回等待点；
        // 录制引擎已移植（org.gms.server.bot.replay 包 + movementDataPackets/map10000/tutorial2.*），
        // 但此处未接入录制回放，TODO(录制回放)：待接入后恢复。当前广播站立帧并等待一拍。
        getChr().broadcastStance();
        waitFor(1000); // settle beat before RESET ticks
    }

    private void botWaitingForPlayerFlavorText() {
        if (Randomizer.nextInt(100) < 6) {
            getDialogueHandler().executeBotFlavorDialogue("Flavor", TutorialBot.this);
        }
    }
}
