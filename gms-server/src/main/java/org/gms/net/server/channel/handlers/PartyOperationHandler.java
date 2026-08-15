/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.gms.net.server.channel.handlers;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.config.GameConfig;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.net.server.coordinator.world.InviteCoordinator;
import org.gms.net.server.coordinator.world.InviteCoordinator.InviteResult;
import org.gms.net.server.coordinator.world.InviteCoordinator.InviteResultType;
import org.gms.net.server.coordinator.world.InviteCoordinator.InviteType;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.net.server.world.PartyOperation;
import org.gms.net.server.world.World;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.party.BotPartyCommands;
import org.gms.server.bot.party.BotPartyQueue;
import org.gms.server.bot.party.BotRecruitManager;
import org.gms.util.PacketCreator;

import java.util.List;

public final class PartyOperationHandler extends AbstractPacketHandler {

    @Override
    public final void handlePacket(InPacket p, Client c) {
        int operation = p.readByte();
        Character player = c.getPlayer();
        World world = c.getWorldServer();
        Party party = player.getParty();
        switch (operation) {
            case 1: { // create
                Party.createParty(player, false);
                break;
            }
            case 2: { // leave/disband
                if (party != null) {
                    List<Character> partymembers = player.getPartyMembersOnline();

                    Party.leaveParty(party, c);
                    player.updatePartySearchAvailability(true);
                    player.partyOperationUpdate(party, partymembers);
                }
                break;
            }
            case 3: { // join
                int partyid = p.readInt();

                InviteResult inviteRes = InviteCoordinator.answerInvite(InviteType.PARTY, player.getId(), partyid, true);
                InviteResultType res = inviteRes.result;
                if (res == InviteResultType.ACCEPTED) {
                    Party.joinParty(player, partyid, false);
                } else {
                    c.sendPacket(PacketCreator.serverNotice(5, "You couldn't join the party due to an expired invitation request."));
                }
                break;
            }
            case 4: { // invite
                String name = p.readString();
                Character invited = world.getPlayerStorage().getCharacterByName(name);
                if (invited != null) {
                    if (invited.getLevel() < 10 && (!GameConfig.getServerBoolean("use_party_for_starters") || player.getLevel() >= 10)) { //min requirement is level 10
                        c.sendPacket(PacketCreator.serverNotice(5, "The player you have invited does not meet the requirements."));
                        return;
                    }
                    if (GameConfig.getServerBoolean("use_party_for_starters") && invited.getLevel() >= 10 && player.getLevel() < 10) {    //trying to invite high level
                        c.sendPacket(PacketCreator.serverNotice(5, "The player you have invited does not meet the requirements."));
                        return;
                    }

                    if (invited.getParty() == null) {
                        if (party == null) {
                            if (!Party.createParty(player, false)) {
                                return;
                            }

                            party = player.getParty();
                        }
                        if (party.getMembers().size() < 6) {
                            // 直接右键邀请 bot：邀请即掷骰（无冷却）。未命中不创建邀请、
                            // 不弹邀请窗，黄字提示拒绝；命中才走正常邀请流程。
                            if (BotHelpers.isBot(invited) && !BotRecruitManager.rollDirectPartyInvite(invited, player)) {
                                player.sendPacket(PacketCreator.serverNotice(5, invited.getName() + " has declined your party request."));
                                return;
                            }
                            if (InviteCoordinator.createInvite(InviteType.PARTY, player, party.getId(), invited.getId())) {
                                invited.sendPacket(PacketCreator.partyInvite(player));
                                if (BotHelpers.isBot(invited)) {
                                    BotPartyQueue.getInstance().addPartyInvite(invited, player, party.getId());
                                    BotSM bot = BotStorage.getBotById(invited.getId());
                                    String botType = bot == null ? null : bot.getBotType();
                                    // SocialBot/TrainingBot：ARMED 已写入，下一拍 pollInvites 按
                                    // inviter id 匹配接受（完整保留转 Follower / 台词 / PartyJoined 语义）。
                                    // FollowerBot：不写 ARMED，下一拍 pollLeaderInvite（750ms tick）
                                    // 按 leader 裁决（leader 邀请接受、非 leader 礼貌拒绝），不同步接受。
                                    // 其他类型（含 OPQBot 等无 poll 消费点的）：同步立即接受。
                                    if (!"SocialBot".equals(botType) && !"TrainingBot".equals(botType)
                                            && !"FollowerBot".equals(botType)) {
                                        if (BotPartyCommands.botAcceptPartyInvite(invited)) {
                                            // 同步接受成功即消费武装窗口，防止 ARMED 泄漏拒掉后续合法邀请。
                                            BotRecruitManager.clearArmed(invited.getId());
                                        }
                                    }
                                }
                            } else {
                                // createInvite 失败（通常对方已有待处理邀请）：
                                // 若本轮掷骰刚命中（武装者就是本玩家），立即回滚 ARMED，
                                // 防止幻影武装窗口在邀请从未生效的情况下拒掉他人的合法邀请。
                                if (BotHelpers.isBot(invited)
                                        && BotRecruitManager.armedInviterId(invited.getId()) == player.getId()) {
                                    BotRecruitManager.clearArmed(invited.getId());
                                }
                                c.sendPacket(PacketCreator.partyStatusMessage(22, invited.getName()));
                            }
                        } else {
                            c.sendPacket(PacketCreator.partyStatusMessage(17));
                        }
                    } else {
                        c.sendPacket(PacketCreator.partyStatusMessage(16));
                    }
                } else {
                    c.sendPacket(PacketCreator.partyStatusMessage(19));
                }
                break;
            }
            case 5: { // expel
                int cid = p.readInt();
                Party.expelFromParty(party, c, cid);
                break;
            }
            case 6: { // change leader
                int newLeader = p.readInt();
                PartyCharacter newLeadr = party.getMemberById(newLeader);
                world.updateParty(party.getId(), PartyOperation.CHANGE_LEADER, newLeadr);
                break;
            }
        }
    }
}