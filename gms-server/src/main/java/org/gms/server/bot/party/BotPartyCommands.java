package org.gms.server.bot.party;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.net.server.coordinator.world.InviteCoordinator;
import org.gms.net.server.coordinator.world.InviteCoordinator.InviteResult;
import org.gms.net.server.coordinator.world.InviteCoordinator.InviteResultType;
import org.gms.net.server.coordinator.world.InviteCoordinator.InviteType;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.net.server.world.PartyOperation;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;

@Slf4j
public class BotPartyCommands {

    public static boolean botMakeParty(Character fakechar) {
        if (fakechar.getParty() != null) {
            log.debug("botMakeParty: bot already in a party, skipping.");
            return false;
        }
        boolean created = Party.createParty(fakechar, false);
        log.debug("botMakeParty: created={}", created);
        return created;
    }

    // Server-side only leave. Skips player-client concerns (MCPQ, EventInstance,
    // MatchChecker, partySearch) since bots don't participate in any of those.
    public static void botLeaveParty(Character fakechar) {
        Party party = fakechar.getParty();
        if (party == null) {
            log.debug("botLeaveParty: no party, skipping.");
            return;
        }

        PartyCharacter botPC = fakechar.getMPC();
        if (botPC == null) {
            botPC = new PartyCharacter(fakechar);
        }

        World world = fakechar.getWorldServer();
        int partyId = party.getId();

        if (botPC.getId() == party.getLeaderId()) {
            world.removeMapPartyMembers(partyId);
            world.updateParty(partyId, PartyOperation.DISBAND, botPC);
            log.debug("botLeaveParty: bot={} disbanded party {}", fakechar.getName(), partyId);
        } else {
            MapleMap map = fakechar.getMap();
            if (map != null) {
                map.removePartyMember(fakechar, partyId);
            }
            world.updateParty(partyId, PartyOperation.LEAVE, botPC);
            log.debug("botLeaveParty: bot={} left party {}", fakechar.getName(), partyId);
        }

        fakechar.setParty(null);
    }

    /**
     * 同步接受（gms 侧消费点）。synchronized 串行化 accept/reject：answerInvite 的
     * check-then-act 在同一把锁内完成，并发的第二次消费（poll 与同步路径竞态）只会
     * 拿到 NOT_FOUND 而不会双 join。
     */
    public static synchronized boolean botAcceptPartyInvite(Character fakechar) {
        BotPartyQueue.PartyInviteEntry entry = BotPartyQueue.getInstance().getPartyInvite(fakechar);
        if (entry == null) {
            log.debug("botAcceptPartyInvite: no pending invite for {}", fakechar.getName());
            return false;
        }

        int partyId = entry.getPartyId();
        InviteResult res = InviteCoordinator.answerInvite(InviteType.PARTY, fakechar.getId(), partyId, true);
        BotPartyQueue.getInstance().removePartyInvite(fakechar);

        if (res.result == InviteResultType.ACCEPTED) {
            boolean joined = Party.joinParty(fakechar, partyId, false);
            if (!joined) {
                // joinParty fails silently to the inviter (party disbanded / full / bot already
                // partied) - tell them so a clean re-invite is the obvious next move.
                Character inviter = entry.getInviter();
                if (inviter != null) {
                    inviter.sendPacket(PacketCreator.serverNotice(5, fakechar.getName()
                            + " couldn't join your party (it was full or disbanded) - try inviting again."));
                }
            }
            log.debug("botAcceptPartyInvite: joined={} partyId={}", joined, partyId);
            return joined;
        }
        log.debug("botAcceptPartyInvite: invite expired/invalid, result={}", res.result);
        return false;
    }

    // synchronized 与 botAcceptPartyInvite 共用 BotPartyCommands.class 锁：
    // 串行化队列消费，杜绝 accept/reject 并发下的 check-then-act 双消费竞态。
    public static synchronized boolean botRejectPartyInvite(Character fakechar) {
        BotPartyQueue.PartyInviteEntry entry = BotPartyQueue.getInstance().getPartyInvite(fakechar);
        if (entry == null) {
            log.debug("botRejectPartyInvite: no pending invite, no-op.");
            return false;
        }

        InviteResult res = InviteCoordinator.answerInvite(InviteType.PARTY, fakechar.getId(), entry.getPartyId(), false);
        BotPartyQueue.getInstance().removePartyInvite(fakechar);

        // Only tell the inviter the bot declined when the coordinator entry actually existed and was
        // denied (DENIED). A NOT_FOUND means the invite was already gone/superseded, so the notice
        // would be misleading - clear the queue entry either way.
        Character inviter = entry.getInviter();
        if (inviter != null && res.result == InviteResultType.DENIED) {
            inviter.sendPacket(PacketCreator.serverNotice(5, fakechar.getName() + " has declined your party request."));
        }
        log.debug("botRejectPartyInvite: result={} inviter={}", res.result, inviter == null ? "?" : inviter.getName());
        return true;
    }

    // Bot sends a party invite to a real player.
    // If bot has no party, creates one (bot becomes leader).
    // If bot is already in a party but NOT the leader, the invite is refused.
    public static boolean botInvitePlayer(Character fakechar, Character target) {
        if (target == null) {
            log.debug("botInvitePlayer: target null.");
            return false;
        }
        if (target.getParty() != null) {
            log.debug("botInvitePlayer: target already in a party.");
            return false;
        }

        Party party = fakechar.getParty();
        if (party == null) {
            if (!Party.createParty(fakechar, false)) {
                log.debug("botInvitePlayer: failed to create party for bot.");
                return false;
            }
            party = fakechar.getParty();
        } else if (party.getLeaderId() != fakechar.getId()) {
            log.debug("botInvitePlayer: bot is in a party but not the leader, cannot invite.");
            return false;
        }

        if (party.getMembers().size() >= 6) {
            log.debug("botInvitePlayer: party is full.");
            return false;
        }

        if (InviteCoordinator.createInvite(InviteType.PARTY, fakechar, party.getId(), target.getId())) {
            target.sendPacket(PacketCreator.partyInvite(fakechar));
            log.debug("botInvitePlayer: invite sent to {} for partyId={}", target.getName(), party.getId());
            return true;
        }

        log.debug("botInvitePlayer: InviteCoordinator rejected (target already has pending invite).");
        return false;
    }
}
