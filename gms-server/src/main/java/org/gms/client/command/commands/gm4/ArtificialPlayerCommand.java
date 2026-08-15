package org.gms.client.command.commands.gm4;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Job;
import org.gms.client.command.Command;
import org.gms.client.inventory.BodyPart;
import org.gms.server.bot.BotExecutors;
import org.gms.server.bot.BotGeneration;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.DefaultBotServerAccess;
import org.gms.server.bot.attack.BotAttackDriver;
import org.gms.server.bot.attack.BotBuffConfig;
import org.gms.server.bot.attack.BotBuffDriver;
import org.gms.server.bot.attack.BotBuffEffects;
import org.gms.server.bot.decorate.BotDecorateBody;
import org.gms.server.bot.decorate.BotDecorateEquips;
import org.gms.server.bot.decorate.BotDecorateNX;
import org.gms.server.bot.grind.MapMobIndex;
import org.gms.server.bot.grind.RestSpotFinder;
import org.gms.server.bot.messaging.QueueMonitor;
import org.gms.server.bot.party.BotPartyCommands;
import org.gms.server.bot.party.BotPartyQueue;
import org.gms.server.bot.party.BotRecruitManager;
import org.gms.server.bot.replay.MovementCommands;
import org.gms.server.bot.replay.MovementRecording;
import org.gms.server.bot.types.TrainingBot;
import org.gms.server.bot.types.blackjack.BlackjackDealerBot;
import org.gms.server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.gms.server.bot.BotCustomization.EquipBot;
import static org.gms.server.bot.BotGeneration.removeBotFromServer;
import static org.gms.server.bot.BotHelpers.convertItemIdToName;
import static org.gms.server.bot.BotLogic.readPlayerCashEquipBySlotName;
import static org.gms.server.bot.BotLogic.readPlayerEquipBySlotName;
import static org.gms.server.bot.BotTypeManager.BotType.BLACKJACK_DEALER;
import static org.gms.server.bot.BotTypeManager.BotType.BUYING_MERCHANT_BOT;
import static org.gms.server.bot.BotTypeManager.BotType.DICE_BOT;
import static org.gms.server.bot.BotTypeManager.BotType.DROP_GAME_BOT;
import static org.gms.server.bot.BotTypeManager.BotType.FM_BOT;
import static org.gms.server.bot.BotTypeManager.BotType.GACHA_BOT;
import static org.gms.server.bot.BotTypeManager.BotType.GAME_ZONE_HOST_BOT;
import static org.gms.server.bot.BotTypeManager.BotType.HENESYS_BOT;
import static org.gms.server.bot.BotTypeManager.BotType.HENESYS_JQ_BOT;
import static org.gms.server.bot.BotTypeManager.BotType.NX_MERCHANT_BOT;
import static org.gms.server.bot.BotTypeManager.BotType.OPQ_BOT;
import static org.gms.server.bot.BotTypeManager.BotType.SCROLL_BOT;
import static org.gms.server.bot.BotTypeManager.BotType.SELLING_MERCHANT_BOT;
import static org.gms.server.bot.BotTypeManager.BotType.TEST_ATTACK_BOT;
import static org.gms.server.bot.BotTypeManager.BotType.TRAINING_BOT;
import static org.gms.server.bot.BotTypeManager.BotType.TUTORIAL_BOT;
import static org.gms.server.bot.BotTypeManager.BotType.FOLLOWER_BOT;
import static org.gms.server.bot.BotTypeManager.convertBotType;
import static org.gms.server.bot.BotTypeManager.manuallyStartBot;
import static org.gms.server.bot.BotTypeManager.manuallyStopBot;
import static org.gms.server.bot.commands.DropCommands.botLoot;
import static org.gms.server.bot.commands.DropCommands.botLootLocation;
import static org.gms.server.bot.commands.DropCommands.botLootOwnerItems;
import static org.gms.server.bot.commands.DropCommands.botLootTargetCharactersItems;
import static org.gms.server.bot.commands.DropCommands.botThrowToOwnerMeso;
import static org.gms.server.bot.commands.MegaphoneCommands.BotAvatarMegaphone;
import static org.gms.server.bot.commands.MegaphoneCommands.BotMapleTV;
import static org.gms.server.bot.commands.MegaphoneCommands.BotMapleTVPartner;
import static org.gms.server.bot.commands.MegaphoneCommands.BotSuperMegaphone;
import static org.gms.server.bot.commands.SocialCommands.BotChatbubbleTyping;
import static org.gms.server.bot.commands.SocialCommands.BotSpeak;
import static org.gms.server.bot.commands.SocialCommands.displayPlayerChatCommands;
import static org.gms.server.bot.commands.SocialCommands.expirePlayerChatCommands;
import static org.gms.server.bot.commands.WarpCommands.botWarpMapOnPortal;
import static org.gms.server.bot.freemarket.NXCodeManager.createCompleteNXCode;
import static org.gms.server.bot.replay.DebugUtilities.debugprint;
import static org.gms.server.bot.replay.InPacketReader.getMovementRecording;
import static org.gms.server.bot.replay.MovementCommands.BotMoveStream;

/**
 * GM4 命令 !bot（扩展）：ArtificialPlayer 生命周期/类型/移动/战斗/外观/组队等 GM 测试子命令。
 * 逐子命令对照 SoloMapling ArtificialPlayerCommand 移植；gm6 BotCommand 已有等价子命令
 * （type / dc）跳过。spawn 的 idle class-spawn 语义由 gm6 BotCommand 在识别到 class 名时转发回本命令。
 */
public class ArtificialPlayerCommand extends Command {
    {
        setDescription("Artificial Player Commands Test.");
    }

    private static final int SPAWN_MAX_COUNT = 20;

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            player.yellowMessage("Please input an integer for cid. Try !bot help");
            return;
        }
        String first = params[0].toLowerCase();
        if (first.equals("spawn") || first.equals("spawnatk")) {
            handleSpawn(params, first.equals("spawnatk"), c);
            return;
        }
        if (first.equals("trainhere") || first.equals("traintest")) {
            handleTrainHere(params, c);
            return;
        }
        if (first.equals("grindstyle")) {
            handleGrindStyle(params, c);
            return;
        }
        if (params.length == 1) {
            handleDirectCommand(params[0], c);
            return;
        }
        if (params.length == 2) {
            if (isInteger(params[1])) {
                int commandNum = Integer.parseInt(params[1]);
                handleNumberedCommand(params[0], commandNum, c);
            } else {
                player.yellowMessage("Second input not an integer");
            }
            return;
        }
        if (params.length == 3) {
            boolean massCommand = params[0].toLowerCase().contains("mass");
            if (massCommand) {
                int commandNum = Integer.parseInt(params[1]);
                int commandNum2 = Integer.parseInt(params[2]);
                BotExecutors.runAsync(() -> handleMassCommand(params[0], commandNum, commandNum2, c));
            } else if (isInteger(params[1]) && isInteger(params[2])) {
                int commandNum = Integer.parseInt(params[1]);
                int commandNum2 = Integer.parseInt(params[2]);
                handleTwoNumberedCommand(params[0], commandNum, commandNum2, c);
            } else if (isInteger(params[1])) {
                int commandNum = Integer.parseInt(params[1]);
                String commandString = params[2];
                handleTwoObjectCommand(params[0], commandNum, commandString, c);
            } else {
                player.yellowMessage("Second input not an integer");
            }
        }
    }

    /**
     * 供 gm6 BotCommand 在 default 分支收口前判断：该首参数是否属于本命令支持的子命令。
     * 仅做首 token 白名单匹配（不校验参数个数），避免把未知子命令转发过来破坏 usage 提示。
     */
    public static boolean supports(String firstToken) {
        if (firstToken == null || firstToken.isEmpty()) {
            return false;
        }
        return switch (firstToken.toLowerCase()) {
            // 顶层（execute 提前分发）
            case "spawnatk", "trainhere", "traintest", "grindstyle" -> true;
            // 直接命令（单参数）
            case "help", "create", "dcmap", "dcallmap", "dchere", "hint", "expirehint",
                    "testslotinfo", "viewqueue", "nxcode" -> true;
            // 带 bot cid 的编号命令
            case "meso", "buff", "attack", "attackaoe", "attackult", "dicebot",
                    "tutorialbot", "tutbot", "setfmbot", "fmbot", "scrollingbot", "scrollbot",
                    "merchantbot", "sellingbot", "buyingbot", "nxbot", "nxmerchantbot",
                    "gachabot", "henesysbot", "gamezonehost", "gzhbot", "bjbot", "bjdealerbot",
                    "blackjackbot", "dropgamebot", "dgbot", "opqbot", "jqbot", "henesysjqbot",
                    "trainbot", "trainingbot", "breaknow", "restspot", "followbot", "followerbot",
                    "manualstart", "manualstop", "convertfmbot", "convertscrollbot", "loot",
                    "disconnect", "remove",
                    "lootwide", "lootlocation", "lootownitems", "loottargetsitems", "warphere",
                    "warpbothere", "enterportal", "randombody", "randomequips", "decoratenx",
                    "nxdecorate", "faceme", "nudge", "nudgeoverlap", "makeparty", "leaveparty",
                    "acceptinv", "acceptparty", "rejectinv", "rejectparty", "inviteparty",
                    "botinviteme", "checkpartyqueue" -> true;
            // mass 批量命令
            case "masscreate", "massfmbot", "massmanualstart", "massmanualstop" -> true;
            // 两个整数参数命令
            case "castbuff", "givebuff", "extbuff", "setlevel", "setclass", "setjob", "equip",
                    "sethair", "bjplayer", "bjplayerbot", "bjaddplayer" -> true;
            // 整数 + 字符串参数命令
            case "chat", "bubbletype", "playrecording", "playrec", "smega", "avatarsmega",
                    "tv", "tvpartner", "tvp" -> true;
            default -> false;
        };
    }

    private void handleDirectCommand(String input, Client c) {
        Character player = c.getPlayer();
        switch (input.toLowerCase()) {
            case "help":
                printHelp(player);
                break;
            case "create":
                BotGeneration.createBot(player.getPosition(), botChannelMap(player));
                player.yellowMessage("Created a bot at your position.");
                break;
            case "dcmap":
            case "dcallmap":
            case "dchere":
                removeBotsOnMyMap(player);
                break;
            case "hint":
                List<String> lstr = List.of("Hey", "Test", "Cho");
                displayPlayerChatCommands(player, lstr);
                break;
            case "expirehint":
                expirePlayerChatCommands(player);
                break;
            case "testslotinfo":
                testEqBySlot(player);
                break;
            case "viewqueue":
                BotExecutors.runAsync(() -> {
                    try {
                        new QueueMonitor().run();
                    } catch (Throwable t) {
                        player.yellowMessage("viewqueue failed: " + t.getMessage());
                    }
                });
                break;
            case "nxcode":
                createCompleteNXCode("GERALTYENNEFER69");
                break;
            default:
                player.yellowMessage("Invalid command - Direct Command");
                break;
        }
    }

    private void handleNumberedCommand(String input, int input2, Client c) {
        Character player = c.getPlayer();
        Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null");
            return;
        }

        switch (input.toLowerCase()) {
            case "meso":
                testDropCommands(fakechar, c);
                break;
            case "buff": {
                List<Integer> buffs = BotBuffConfig.buffsForJob(fakechar.getJob());
                int casted = BotBuffDriver.forceBuff(fakechar);
                player.yellowMessage("Buff " + fakechar.getName() + " lv" + fakechar.getLevel()
                        + " (job " + fakechar.getJob() + "): cast " + casted + " of " + buffs.size()
                        + " " + buffs + (buffs.isEmpty() ? " - this job has no buffs configured" : ""));
                if (fakechar.getMapId() != player.getMapId()) {
                    player.yellowMessage("Note: bot is on map " + fakechar.getMapId() + ", you are on "
                            + player.getMapId() + " - the cast animation only shows to players on the bot's map.");
                }
                break;
            }
            case "attack":
                reportAttack(player, fakechar, BotAttackDriver.forceSingle(fakechar), "attack");
                break;
            case "attackaoe":
                reportAttack(player, fakechar, BotAttackDriver.forceAoe(fakechar), "attackaoe");
                break;
            case "attackult":
                reportAttack(player, fakechar, BotAttackDriver.forceUltimate(fakechar), "attackult");
                break;
            case "dicebot":
                DICE_BOT.createAndSetBot(fakechar);
                break;
            case "tutorialbot":
            case "tutbot":
                TUTORIAL_BOT.createAndSetBot(fakechar);
                break;
            case "setfmbot":
            case "fmbot":
                FM_BOT.createAndSetBot(fakechar);
                break;
            case "scrollingbot":
            case "scrollbot":
                SCROLL_BOT.createAndSetBot(fakechar);
                break;
            case "merchantbot":
            case "sellingbot":
                SELLING_MERCHANT_BOT.createAndSetBot(fakechar);
                break;
            case "buyingbot":
                BUYING_MERCHANT_BOT.createAndSetBot(fakechar);
                break;
            case "nxbot":
            case "nxmerchantbot":
                NX_MERCHANT_BOT.createAndSetBot(fakechar);
                break;
            case "gachabot":
                GACHA_BOT.createAndSetBot(fakechar);
                break;
            case "henesysbot":
                HENESYS_BOT.createAndSetBot(fakechar);
                break;
            case "gamezonehost":
            case "gzhbot":
                GAME_ZONE_HOST_BOT.createAndSetBot(fakechar);
                break;
            case "bjbot":
            case "bjdealerbot":
            case "blackjackbot":
                BLACKJACK_DEALER.createAndSetBot(fakechar);
                break;
            case "dropgamebot":
            case "dgbot":
                DROP_GAME_BOT.createAndSetBot(fakechar);
                break;
            case "opqbot":
                OPQ_BOT.createAndSetBot(fakechar);
                break;
            case "jqbot":
            case "henesysjqbot":
                HENESYS_JQ_BOT.createAndSetBot(fakechar);
                break;
            case "trainbot":
            case "trainingbot":
                convertBotType(fakechar, TRAINING_BOT);
                player.yellowMessage("Bot " + fakechar.getId() + " is now a TrainingBot (town↔grind loop).");
                break;
            case "breaknow": {
                BotSM maybeTrainer = BotStorage.getBotById(fakechar.getId());
                if (maybeTrainer instanceof TrainingBot tb) {
                    player.yellowMessage(tb.forceBreakNow()
                            ? fakechar.getName() + " will take its break on the next grind tick."
                            : fakechar.getName() + " isn't grinding right now (breaks only fire mid-GRIND).");
                } else {
                    player.yellowMessage("Not a TrainingBot.");
                }
                break;
            }
            case "restspot": {
                BotSM maybeTrainer = BotStorage.getBotById(fakechar.getId());
                if (maybeTrainer instanceof TrainingBot) {
                    for (String line : RestSpotFinder.debug(fakechar)) {
                        player.yellowMessage(line);
                    }
                } else {
                    player.yellowMessage("Not a TrainingBot.");
                }
                break;
            }
            case "followbot":
            case "followerbot": {
                BotRecruitManager.setPendingLeader(fakechar.getId(), c.getPlayer().getId());
                boolean converted = convertBotType(fakechar, FOLLOWER_BOT);
                player.yellowMessage(converted
                        ? "Bot " + fakechar.getName() + " is now a FollowerBot following YOU."
                        : "Conversion refused - bot is mid-trade.");
                break;
            }
            case "manualstart":
                manuallyStartBot(fakechar);
                break;
            case "manualstop":
                manuallyStopBot(fakechar);
                break;
            case "disconnect":
            case "remove":
                manuallyStopBot(fakechar);
                removeBotFromServer(fakechar);
                break;
            case "convertfmbot":
                convertBotType(fakechar, FM_BOT);
                break;
            case "convertscrollbot":
                convertBotType(fakechar, SCROLL_BOT);
                break;
            case "loot":
                botLoot(fakechar, 1000);
                break;
            case "lootwide":
                botLoot(fakechar, 9000);
                break;
            case "lootlocation":
                botLootLocation(fakechar, c.getPlayer().getPosition());
                break;
            case "lootownitems":
                botLootOwnerItems(fakechar, fakechar.getPosition(), 12000);
                break;
            case "loottargetsitems":
                botLootTargetCharactersItems(fakechar, c.getPlayer(), c.getPlayer().getPosition(), 9000);
                break;
            case "warphere":
            case "warpbothere": {
                MapleMap map = botChannelMap(player);
                Point pos = c.getPlayer().getPosition();
                warpBotToLocation(fakechar, pos, map);
                break;
            }
            case "enterportal":
                botWarpMapOnPortal(fakechar);
                break;
            case "randombody":
                BotDecorateBody.decorateBotBody(fakechar);
                break;
            case "randomequips":
                BotDecorateEquips.decorateBotEquips(fakechar);
                break;
            case "decoratenx":
            case "nxdecorate":
                BotDecorateNX.applyForced(fakechar);
                player.yellowMessage("NX decoration applied to bot " + fakechar.getId()
                        + " (" + fakechar.getName() + ") tier=" + fakechar.getTier()
                        + " gender=" + fakechar.getGender());
                break;
            case "faceme":
                MovementCommands.botFaceTowardsPoint(fakechar, c.getPlayer().getPosition());
                player.yellowMessage("Bot " + fakechar.getId() + " facing towards you");
                break;
            case "nudge":
                MovementCommands.nudgeSmall(fakechar);
                player.yellowMessage("Nudged bot " + fakechar.getName() + " by 20 units.");
                break;
            case "nudgeoverlap":
                boolean nudged = MovementCommands.nudgeAwayFromOverlap(fakechar);
                player.yellowMessage(nudged
                        ? "Bot " + fakechar.getName() + " nudged away from nearby bot."
                        : "No overlapping bot found near " + fakechar.getName() + ".");
                break;
            case "makeparty":
                player.yellowMessage("makeparty result: " + BotPartyCommands.botMakeParty(fakechar));
                break;
            case "leaveparty":
                BotPartyCommands.botLeaveParty(fakechar);
                break;
            case "acceptinv":
            case "acceptparty":
                player.yellowMessage("acceptPartyInvite result: " + BotPartyCommands.botAcceptPartyInvite(fakechar));
                break;
            case "rejectinv":
            case "rejectparty":
                player.yellowMessage("rejectPartyInvite result: " + BotPartyCommands.botRejectPartyInvite(fakechar));
                break;
            case "inviteparty":
            case "botinviteme":
                player.yellowMessage("botInvitePlayer result: " + BotPartyCommands.botInvitePlayer(fakechar, c.getPlayer()));
                break;
            case "checkpartyqueue": {
                BotPartyQueue.PartyInviteEntry entry = BotPartyQueue.getInstance().getPartyInvite(fakechar);
                if (entry == null) {
                    player.yellowMessage("No pending party invite for " + fakechar.getName());
                } else {
                    player.yellowMessage("Pending invite: from=" + entry.getInviter().getName() + " partyId=" + entry.getPartyId());
                }
                break;
            }
            default:
                player.yellowMessage("Invalid command - NumberedCommand");
                break;
        }
    }

    private void handleMassCommand(String input, int input2, int input3, Client c) {
        Character player = c.getPlayer();
        player.yellowMessage("Mass Command: " + input + ", num: " + input2 + ", num2: " + input3);
        switch (input.toLowerCase()) {
            case "masscreate":
                massCreateBots(input2, input3, player);
                break;
            case "massfmbot":
                massFMBots(input2, input3);
                break;
            case "massmanualstart":
                massManualStart(input2, input3);
                break;
            case "massmanualstop":
                massManualStop(input2, input3);
                break;
        }
    }

    private void handleTwoNumberedCommand(String input, int input2, int input3, Client c) {
        Character player = c.getPlayer();
        Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null");
            return;
        }

        player.yellowMessage("Command: " + input2 + ", arg: " + input3);

        switch (input.toLowerCase()) {
            case "castbuff": {
                boolean applied = BotBuffDriver.castSkill(fakechar, input3);
                player.yellowMessage("castbuff " + fakechar.getName() + " (job " + fakechar.getJob()
                        + ") skill " + input3 + " -> " + (applied ? "applied" : "FAILED (no such skill/effect)"));
                if (fakechar.getMapId() != player.getMapId()) {
                    player.yellowMessage("Note: bot is on map " + fakechar.getMapId() + ", you are on "
                            + player.getMapId() + " - the cast animation only shows to players on the bot's map.");
                }
                break;
            }
            case "givebuff":
                BotBuffEffects.givePartyBuff(fakechar, input3, List.of(player));
                player.yellowMessage("givebuff: " + fakechar.getName() + " -> you (skill " + input3 + ")");
                if (fakechar.getMapId() != player.getMapId()) {
                    player.yellowMessage("Note: bot is on map " + fakechar.getMapId() + ", you are on "
                            + player.getMapId() + " - you still get the buff, but the bot's cast animation only shows on its map.");
                }
                break;
            case "extbuff":
                BotBuffEffects.giveExtendedBuff(fakechar, input3, List.of(player),
                        BotBuffEffects.EXTENDED_DURATION_MS);
                player.yellowMessage("extbuff: " + fakechar.getName() + " -> you (skill " + input3 + ", 10 min)");
                if (fakechar.getMapId() != player.getMapId()) {
                    player.yellowMessage("Note: bot is on map " + fakechar.getMapId() + ", you are on "
                            + player.getMapId() + " - you still get the buff, but the bot's cast animation only shows on its map.");
                }
                break;
            case "setlevel":
                if (input3 < 1 || input3 > 200) {
                    player.yellowMessage("setlevel: level must be 1-200");
                    break;
                }
                fakechar.setLevel(input3);
                player.yellowMessage("Set " + fakechar.getName() + " to level " + input3);
                break;
            case "setclass":
            case "setjob": {
                Job newJob = Job.getById(input3);
                if (newJob == null) {
                    player.yellowMessage("setjob: unknown job id " + input3
                            + " (e.g. 100 warrior, 200 magician, 230 cleric, 232 bishop, 412 nightlord)");
                    break;
                }
                fakechar.setJob(newJob);
                player.yellowMessage("Set " + fakechar.getName() + " to job " + newJob + " (" + input3
                        + ") - buffs: " + BotBuffConfig.buffsForJob(newJob));
                break;
            }
            case "equip":
                EquipBot(fakechar, input3);
                break;
            case "sethair":
                fakechar.setHair(input3);
                break;
            case "bjplayer":
            case "bjplayerbot":
            case "bjaddplayer": {
                BotSM dealerBot = BotStorage.getBotById(input2);
                Character playerToAdd = DefaultBotServerAccess.INSTANCE.getCharacterById(input3);
                if (dealerBot instanceof BlackjackDealerBot && playerToAdd != null) {
                    BlackjackDealerBot bjDealer = (BlackjackDealerBot) dealerBot;
                    boolean added = bjDealer.getTable().addPlayer(playerToAdd);
                    bjDealer.getInteractors().setRespondant(playerToAdd);
                    player.yellowMessage(added
                            ? playerToAdd.getName() + " added to blackjack table."
                            : "Table is full or player already added.");
                } else {
                    player.yellowMessage("Dealer bot not found or not a BlackjackDealerBot, or player bot not found.");
                }
                break;
            }
            case "loot":
                break;
            default:
                player.yellowMessage("Invalid command - Two Number");
                break;
        }
    }

    private void handleTwoObjectCommand(String input, int input2, String str, Client c) {
        Character player = c.getPlayer();
        Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null");
            return;
        }

        switch (input.toLowerCase()) {
            case "chat":
                BotSpeak(fakechar, str);
                break;
            case "bubbletype":
                String dd = "Be very cautious when dealing with other players. You won't know their true intentions.";
                BotChatbubbleTyping(fakechar, dd, 150);
                break;
            case "playrecording":
            case "playrec":
                player.yellowMessage("Playing recording '" + str + "' on bot " + fakechar.getName() + " (map " + fakechar.getMapId() + ")");
                BotExecutors.runAsync(() -> {
                    try {
                        MovementRecording mvr = getMovementRecording(fakechar.getMapId(), str);
                        BotMoveStream(mvr, fakechar);
                    } catch (Exception e) {
                        player.yellowMessage("Failed to play recording: " + e.getMessage());
                    }
                });
                break;
            case "smega":
                BotSuperMegaphone(fakechar, str);
                break;
            case "avatarsmega":
                BotAvatarMegaphone(fakechar, str);
                break;
            case "tv":
                BotMapleTV(fakechar, str);
                break;
            case "tvpartner":
            case "tvp":
                BotMapleTVPartner(fakechar, str, c.getPlayer());
                break;
            default:
                player.yellowMessage("Invalid command Two Object");
                break;
        }
    }

    private void handleSpawn(String[] params, boolean autoAttack, Client c) {
        Character player = c.getPlayer();
        String verb = params[0].toLowerCase();
        if (params.length < 3) {
            player.yellowMessage("Usage: !bot " + verb
                    + " <warrior|mage|bow|thief> <jobtier 1-4 | level 10-200> [count]");
            return;
        }

        Integer baseClass = parseGenre(params[1]);
        if (baseClass == null) {
            player.yellowMessage("Unknown class '" + params[1] + "'. Use warrior | mage | bow | thief.");
            return;
        }

        if (!isInteger(params[2])) {
            player.yellowMessage("Second arg must be a job tier (1-4) or an exact level (10-200).");
            return;
        }
        int[] band = resolveLevelBand(Integer.parseInt(params[2]));
        if (band == null) {
            player.yellowMessage("Second arg must be job tier 1-4 or level 10-200 (got " + params[2] + ").");
            return;
        }

        int count = 1;
        if (params.length >= 4 && isInteger(params[3])) {
            count = Integer.parseInt(params[3]);
        }
        if (count < 1) {
            count = 1;
        }
        if (count > SPAWN_MAX_COUNT) {
            count = SPAWN_MAX_COUNT;
            player.yellowMessage("Count capped at " + SPAWN_MAX_COUNT + ".");
        }

        MapleMap map = botChannelMap(player);
        Point pos = c.getPlayer().getPosition();

        List<Integer> spawned = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // 装饰已并入 5 参 createBot（baseClass>0 分支），避免 createBot+setBotVariables 双重装饰。
            int botId = BotGeneration.createBot(pos, map, baseClass, band[0], band[1]);
            if (autoAttack) {
                Character bot = DefaultBotServerAccess.INSTANCE.getCharacterById(botId);
                if (bot != null) {
                    TEST_ATTACK_BOT.createAndSetBot(bot);
                    manuallyStartBot(bot);
                }
            }
            spawned.add(botId);
        }

        String bandDesc = (band[0] == band[1]) ? ("lv" + band[0]) : ("lv" + band[0] + "-" + band[1]);
        player.yellowMessage("Spawned " + spawned.size() + " " + genreName(baseClass) + " bot(s) "
                + bandDesc + (autoAttack ? " [auto-attack]" : "") + ": cids " + spawned);
        if (!autoAttack) {
            player.yellowMessage("Drive with !bot attack <cid> / !bot attackaoe <cid>.");
        }
    }

    private static Integer parseGenre(String s) {
        switch (s.toLowerCase()) {
            case "warrior", "war", "fighter" -> { return 1; }
            case "mage", "mag", "magician", "wizard" -> { return 2; }
            case "bow", "bowman", "archer", "bowmen" -> { return 3; }
            case "thief", "sin", "rogue", "assassin" -> { return 4; }
            default -> { return null; }
        }
    }

    /** 源 class 名白名单（parseGenre）；供 gm6 BotCommand 把 !bot spawn <class> 转发回 idle class-spawn 语义。 */
    public static boolean isSpawnClass(String s) {
        return parseGenre(s) != null;
    }

    private static String genreName(int baseClass) {
        return switch (baseClass) {
            case 1 -> "warrior";
            case 2 -> "mage";
            case 3 -> "bowman";
            case 4 -> "thief";
            default -> "unknown";
        };
    }

    private static int[] resolveLevelBand(int tierOrLevel) {
        switch (tierOrLevel) {
            case 1: return new int[]{10, 29};
            case 2: return new int[]{30, 69};
            case 3: return new int[]{70, 119};
            case 4: return new int[]{120, 200};
            default:
                if (tierOrLevel >= 10 && tierOrLevel <= 200) {
                    return new int[]{tierOrLevel, tierOrLevel};
                }
                return null;
        }
    }

    private void handleTrainHere(String[] params, Client c) {
        Character player = c.getPlayer();
        if (params.length < 2) {
            player.yellowMessage("Usage: !bot trainhere <job> [level] [count]");
            player.yellowMessage("  job: name (hermit, nightlord, chiefbandit, shadower, assassin, bandit, "
                    + "fpwizard, ilwizard, cleric, fpmage, ilmage, priest, bishop) or a job id (e.g. 411).");
            return;
        }
        Job job = resolveJob(params[1]);
        if (job == null) {
            player.yellowMessage("Unknown job '" + params[1] + "'. Try a name (hermit, nightlord, ilmage...) or a job id.");
            return;
        }
        int baseClass = job.getJobNiche();
        if (baseClass < 1 || baseClass > 4) {
            player.yellowMessage(job.name() + " isn't a supported training class (warrior/mage/bow/thief only).");
            return;
        }
        MapleMap map = botChannelMap(player);
        if (map == null || MapMobIndex.level(map.getId()) < 0) {
            player.yellowMessage("Stand on a map WITH MOBS (not a town) — 'grind here' needs mobs on the map.");
            return;
        }
        int level = defaultLevelForTier(job.getJobTier());
        if (params.length >= 3 && isInteger(params[2])) {
            int lv = Integer.parseInt(params[2]);
            if (lv >= 1 && lv <= 200) {
                level = lv;
            }
        }
        int count = 1;
        if (params.length >= 4 && isInteger(params[3])) {
            count = Integer.parseInt(params[3]);
        }
        count = Math.max(1, Math.min(count, SPAWN_MAX_COUNT));

        Point pos = c.getPlayer().getPosition();
        List<Integer> spawned = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // 装饰已并入 6 参 createBot（forcedJobId 钉死精确职业），避免 createBot+setBotVariables 双重装饰。
            int botId = BotGeneration.createBot(pos, map, baseClass, level, level, job.getId());
            Character bot = DefaultBotServerAccess.INSTANCE.getCharacterById(botId);
            if (bot == null) {
                continue;
            }
            BotRecruitManager.markStationHere(botId);
            TRAINING_BOT.createAndSetBot(bot);
            manuallyStartBot(bot);
            spawned.add(botId);
        }
        player.yellowMessage("Spawned " + spawned.size() + " " + job.name() + " TrainingBot(s) lv" + level
                + " pinned to this map (" + map.getId() + "); they start grinding here in ~7-10s. cids " + spawned);
    }

    private void handleGrindStyle(String[] params, Client c) {
        Character player = c.getPlayer();
        if (params.length < 3) {
            player.yellowMessage("Usage: !bot grindstyle <cid|map> <camp|patrol|roam|stack|auto>");
            return;
        }
        String target = params[1];
        String styleName = params[2];
        List<TrainingBot> targets = new ArrayList<>();
        if (target.equalsIgnoreCase("map")) {
            MapleMap map = botChannelMap(player);
            if (map != null) {
                for (Character chr : map.getCharacters()) {
                    if (BotStorage.getBotById(chr.getId()) instanceof TrainingBot tb) {
                        targets.add(tb);
                    }
                }
            }
        } else if (isInteger(target)) {
            if (BotStorage.getBotById(Integer.parseInt(target)) instanceof TrainingBot tb) {
                targets.add(tb);
            }
        }
        if (targets.isEmpty()) {
            player.yellowMessage("No training bot found for '" + target + "' (need a TrainingBot cid, or 'map').");
            return;
        }
        int done = 0;
        String lastLine = null;
        for (TrainingBot tb : targets) {
            String line = tb.forceGrindStyle(styleName);
            if (line == null) {
                player.yellowMessage("Unknown grind style '" + styleName + "'. Use camp, patrol, roam, stack, or auto.");
                return;
            }
            lastLine = line;
            done++;
        }
        player.yellowMessage(done == 1 ? lastLine
                : "Forced grind style '" + styleName.toUpperCase() + "' on " + done + " training bot(s) on this map.");
    }

    private static int defaultLevelForTier(int tier) {
        return switch (tier) {
            case 1 -> 25;
            case 2 -> 45;
            case 3 -> 90;
            case 4 -> 140;
            default -> 50;
        };
    }

    private static Job resolveJob(String s) {
        if (isInteger(s)) {
            return Job.getById(Integer.parseInt(s));
        }
        String k = s.toLowerCase().replaceAll("[^a-z0-9]", "");
        switch (k) {
            case "assassin", "sin": return Job.ASSASSIN;
            case "hermit": return Job.HERMIT;
            case "nightlord", "nl": return Job.NIGHTLORD;
            case "bandit": return Job.BANDIT;
            case "chiefbandit", "cb": return Job.CHIEFBANDIT;
            case "shadower", "shad": return Job.SHADOWER;
            case "fpwizard", "fpwiz": return Job.FP_WIZARD;
            case "ilwizard", "ilwiz": return Job.IL_WIZARD;
            case "cleric": return Job.CLERIC;
            case "fpmage": return Job.FP_MAGE;
            case "ilmage": return Job.IL_MAGE;
            case "priest": return Job.PRIEST;
            case "fparchmage": return Job.FP_ARCHMAGE;
            case "ilarchmage": return Job.IL_ARCHMAGE;
            case "bishop", "bish": return Job.BISHOP;
            default:
                try {
                    return Job.valueOf(s.toUpperCase().replaceAll("[^A-Z0-9]", "_"));
                } catch (IllegalArgumentException e) {
                    return null;
                }
        }
    }

    private void removeBotsOnMyMap(Character player) {
        MapleMap map = botChannelMap(player);
        if (map == null) {
            return;
        }
        List<Character> bots = new ArrayList<>();
        for (Character chr : map.getCharacters()) {
            if (BotHelpers.isBot(chr)) {
                bots.add(chr);
            }
        }
        for (Character bot : bots) {
            removeBotFromServer(bot);
        }
        player.yellowMessage("Removed " + bots.size() + " bot(s) from map " + player.getMapId() + ".");
    }

    private void reportAttack(Character player, Character fakechar, BotAttackDriver.AttackResult res, String verb) {
        if (res.hit()) {
            player.yellowMessage(verb + " " + fakechar.getName() + " lv" + fakechar.getLevel()
                    + " -> hit '" + res.monsterName() + "' for " + res.damage()
                    + (res.killed() ? " (KILLED)" : ""));
        } else {
            player.yellowMessage(verb + " " + fakechar.getName() + " -> no hit: " + res.reason());
        }
        if (fakechar.getMapId() != player.getMapId()) {
            player.yellowMessage("Note: bot is on map " + fakechar.getMapId() + ", you are on "
                    + player.getMapId() + " - the swing only shows to players on the bot's map.");
        }
    }

    private void printHelp(Character player) {
        player.yellowMessage("---- Bot Commands (!bot) ----");
        player.yellowMessage("-- Creation & Lifecycle --");
        player.yellowMessage("!bot create                      - create bots");
        player.yellowMessage("!bot manualstart <cid>           - start bot manually");
        player.yellowMessage("!bot manualstop <cid>            - stop bot manually");
        player.yellowMessage("!bot dcmap/dchere                - remove ALL bots on your current map");
        player.yellowMessage("!bot masscreate <start> <end>    - create multiple bots");
        player.yellowMessage("!bot massmanualstart <s> <e>     - start multiple bots");
        player.yellowMessage("!bot massmanualstop <s> <e>      - stop multiple bots");
        player.yellowMessage("-- Class Spawn (attack testing) --");
        player.yellowMessage("!bot spawnatk <class> <tier> [n] - spawn n & auto-attack here");
        player.yellowMessage("   class: warrior|mage|bow|thief  tier: 1-4 or exact lv 10-200  n<=20");
        player.yellowMessage("!bot trainhere <job> [lv] [n]    - spawn n TrainingBots of a job, grind THIS map");
        player.yellowMessage("   job: name (hermit, nightlord, chiefbandit, priest, ilmage...) or job id; needs a mob map");
        player.yellowMessage("!bot grindstyle <cid|map> <s>    - force grind archetype: camp|patrol|roam|stack|auto ('map' = all here)");
        player.yellowMessage("-- Set Bot Type --");
        player.yellowMessage("!bot fmbot <cid>                 - set as FM bot");
        player.yellowMessage("!bot scrollbot <cid>             - set as scroll bot");
        player.yellowMessage("!bot henesysbot <cid>            - set as Henesys bot");
        player.yellowMessage("!bot tutbot <cid>                - set as tutorial bot");
        player.yellowMessage("!bot dicebot <cid>               - set as dice bot");
        player.yellowMessage("!bot sellingbot <cid>            - set as selling merchant");
        player.yellowMessage("!bot buyingbot <cid>             - set as buying merchant");
        player.yellowMessage("!bot nxbot <cid>                 - set as NX merchant");
        player.yellowMessage("!bot gachabot <cid>              - set as gacha bot");
        player.yellowMessage("!bot gzhbot <cid>                - set as game zone host");
        player.yellowMessage("!bot blackjackbot <cid>          - set as blackjack dealer");
        player.yellowMessage("!bot dgbot <cid>                 - set as drop game bot");
        player.yellowMessage("!bot opqbot <cid>                - set as OPQ bot");
        player.yellowMessage("!bot jqbot <cid>                 - set as JQ bot");
        player.yellowMessage("-- Bot Conversion --");
        player.yellowMessage("!bot convertfmbot <cid>          - convert to FM bot");
        player.yellowMessage("!bot convertscrollbot <cid>      - convert to scroll bot");
        player.yellowMessage("!bot trainbot <cid>              - convert to TrainingBot");
        player.yellowMessage("!bot followbot <cid>             - convert to FollowerBot (follows YOU)");
        player.yellowMessage("!bot breaknow <cid>              - force a grinding TrainingBot's rest break");
        player.yellowMessage("!bot restspot <cid>              - dump ranked rest-spot candidates (why a break spot won)");
        player.yellowMessage("!bot massfmbot <start> <end>     - mass create FM bots");
        player.yellowMessage("-- Loot --");
        player.yellowMessage("!bot loot <cid>                  - loot at feet");
        player.yellowMessage("!bot lootwide <cid>              - loot wide area");
        player.yellowMessage("!bot lootlocation <cid>          - loot at your position");
        player.yellowMessage("!bot lootownitems <cid>          - loot bot's own items");
        player.yellowMessage("!bot loottargetsitems <cid>      - loot your items");
        player.yellowMessage("-- Combat --");
        player.yellowMessage("!bot attack <cid>                - force single-target attack");
        player.yellowMessage("!bot attackaoe <cid>             - force sustained AoE attack (or says none)");
        player.yellowMessage("!bot attackult <cid>             - force full-map ultimate (or says none)");
        player.yellowMessage("-- Appearance --");
        player.yellowMessage("!bot randombody <cid>            - random body decoration");
        player.yellowMessage("!bot randomequips <cid>          - random equip decoration");
        player.yellowMessage("!bot decoratenx <cid>            - apply NX decoration");
        player.yellowMessage("!bot equip <cid> <itemid>        - equip item on bot");
        player.yellowMessage("!bot sethair <cid> <hairid>      - set bot hair style");
        player.yellowMessage("-- Movement --");
        player.yellowMessage("!bot warphere <cid>              - warp bot to you");
        player.yellowMessage("!bot enterportal <cid>           - bot enters nearest portal");
        player.yellowMessage("!bot faceme <cid>                - bot faces towards you");
        player.yellowMessage("!bot nudge <cid>                 - nudge bot 20 units");
        player.yellowMessage("!bot nudgeoverlap <cid>          - nudge bot from overlap");
        player.yellowMessage("-- Party --");
        player.yellowMessage("!bot makeparty <cid>             - bot creates party");
        player.yellowMessage("!bot leaveparty <cid>            - bot leaves party");
        player.yellowMessage("!bot acceptparty <cid>           - bot accepts party invite");
        player.yellowMessage("!bot rejectparty <cid>           - bot rejects party invite");
        player.yellowMessage("!bot botinviteme <cid>           - bot invites you to party");
        player.yellowMessage("!bot checkpartyqueue <cid>       - check pending invite");
        player.yellowMessage("-- Blackjack --");
        player.yellowMessage("!bot bjaddplayer <dealer> <cid>  - add player to BJ table");
        player.yellowMessage("-- Chat & Megaphone --");
        player.yellowMessage("!bot chat <cid> <message>        - bot speaks in chat");
        player.yellowMessage("!bot bubbletype <cid> <msg>      - bot types in chat bubble");
        player.yellowMessage("!bot smega <cid> <message>       - super megaphone");
        player.yellowMessage("!bot avatarsmega <cid> <msg>     - avatar megaphone");
        player.yellowMessage("!bot tv <cid> <message>          - MapleTV");
        player.yellowMessage("!bot tvpartner <cid> <msg>       - MapleTV with partner");
        player.yellowMessage("-- Recordings --");
        player.yellowMessage("!bot playrec <cid> <name>        - play movement recording");
        player.yellowMessage("-- Utility --");
        player.yellowMessage("!bot meso <cid>                  - test drop commands");
        player.yellowMessage("!bot hint                        - display chat hint commands");
        player.yellowMessage("!bot expirehint                  - expire chat hint");
        player.yellowMessage("!bot testslotinfo                - test equip by slot");
        player.yellowMessage("!bot viewqueue                   - monitor message queue");
        player.yellowMessage("!bot nxcode                      - create test NX code");
    }

    private void testEqBySlot(Character chr) {
        String owner = readPlayerEquipBySlotName(chr, BodyPart.WEAPON).getOwner();
        String itemName = convertItemIdToName(readPlayerEquipBySlotName(chr, BodyPart.WEAPON).getItemId());
        debugprint(owner, itemName);
        readPlayerCashEquipBySlotName(chr, BodyPart.WEAPON);

        readPlayerEquipBySlotName(chr, BodyPart.SHOES);
        readPlayerCashEquipBySlotName(chr, BodyPart.SHOES);

        readPlayerEquipBySlotName(chr, BodyPart.CAP);
        readPlayerCashEquipBySlotName(chr, BodyPart.CAP);

        readPlayerEquipBySlotName(chr, BodyPart.COAT);
        readPlayerCashEquipBySlotName(chr, BodyPart.COAT);

        readPlayerEquipBySlotName(chr, BodyPart.PANTS);
        readPlayerCashEquipBySlotName(chr, BodyPart.PANTS);

        readPlayerEquipBySlotName(chr, BodyPart.EAR_ACCESSORY);
        readPlayerCashEquipBySlotName(chr, BodyPart.EAR_ACCESSORY);

        readPlayerEquipBySlotName(chr, BodyPart.MEDAL);
    }

    private void testDropCommands(Character fakechar, Client c) {
        botThrowToOwnerMeso(fakechar, 100, c.getPlayer());
    }

    // ── 本地辅助（gms 缺失的源方法在此内联实现） ──────────────────────────

    /** bot 所在频道/世界的「我的地图」实例（避免 GM 与 bot 跨频道地图错位）。 */
    private static MapleMap botChannelMap(Character player) {
        return DefaultBotServerAccess.INSTANCE.getMap(
                DefaultBotServerAccess.resolveBotWorld(), DefaultBotServerAccess.resolveBotChannel(), player.getMapId());
    }

    /** 等价 SoloMapling BotGeneration.warpBotToLocation（placeBotOnMap 语义）。 */
    private static void warpBotToLocation(Character fakechar, Point pos, MapleMap map) {
        if (fakechar.getMap() == map) {
            fakechar.getMap().removePlayer(fakechar);
        }
        fakechar.setMap(map);
        fakechar.setPosition(pos);
        fakechar.setStance(5);
        map.addPlayer(fakechar);
    }

    private static void massCreateBots(int start, int end, Character player) {
        MapleMap map = botChannelMap(player);
        Point pos = player.getPosition();
        for (int x = start; x < end; x++) {
            BotGeneration.createBot(pos, map);
            BotHelpers.blockingSleep(50);
        }
    }

    private static void massFMBots(int start, int end) {
        for (int x = start; x <= end; x++) {
            Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(x);
            if (fakechar == null) {
                continue;
            }
            FM_BOT.createAndSetBot(fakechar);
        }
    }

    private static void massManualStart(int start, int end) {
        for (int x = start; x <= end; x++) {
            Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(x);
            if (fakechar == null) {
                continue;
            }
            manuallyStartBot(fakechar);
        }
    }

    private static void massManualStop(int start, int end) {
        for (int x = start; x <= end; x++) {
            Character fakechar = DefaultBotServerAccess.INSTANCE.getCharacterById(x);
            if (fakechar == null) {
                continue;
            }
            manuallyStopBot(fakechar);
            BotHelpers.blockingSleep(150);
        }
    }

    private static boolean isInteger(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
