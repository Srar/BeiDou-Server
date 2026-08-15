package org.gms.server.bot.trade;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;

@Slf4j
public class BotTradeHandler {

    Character chr;
    Character tradePartner;

    public BotTradeHandler(Character chr) {
        this.chr = chr;
    }

    public void setTradePartner(Character player) {
        this.tradePartner = player;
    }

    public void resetTradePartner() {
        setTradePartner(null);
    }

    protected Character getTradePartnerConfirmed() {
        try {
            return this.tradePartner;
        } catch (Exception e) {
            // expected no-partner path guarded via try/catch; log at debug (per-tick null guard)
            log.debug("BotTradeHandler.getTradePartnerConfirmed for bot {}", chr != null ? chr.getId() : "null", e);
            return null;
        }
    }

    public Character getTradePartnerRaw() {
        try {
            return chr.getTrade().getPartner().getChr();
        } catch (Exception e) {
            // expected no-trade path guarded via try/catch; log at debug (per-tick null guard)
            log.debug("BotTradeHandler.getTradePartnerRaw for bot {}", chr != null ? chr.getId() : "null", e);
            return null;
        }
    }

//    protected boolean checkTradeRequests() {
//        if (getTradePartnerRaw() == null || !verifyPlayerOnSameMap(getTradePartnerRaw())) {
//            resetTradePartner();
//            return false;
//        }
//        setTradePartner(getTradePartnerRaw());
//        return true;
//    }

    public boolean verifyTradePartner() {

        boolean haveTradePartner = getTradePartnerRaw() == null;
        Character tradePartner = getTradePartnerConfirmed();
        boolean haveTradePartnerConfirmed = tradePartner == null;
        boolean tradePartnerOnSameMap;
        if (tradePartner != null) {
            tradePartnerOnSameMap = verifyPlayerOnSameMap(tradePartner);
        } else {
            tradePartnerOnSameMap = false;
        }
//        debugprint("tradePartner, tradePartnerConfirmed, tradePartnerSameMap: ",
//                haveTradePartner, haveTradePartnerConfirmed, tradePartnerOnSameMap);

        if (getTradePartnerRaw() == null ||
                getTradePartnerConfirmed() == null ||
                !verifyPlayerOnSameMap(getTradePartnerConfirmed())) {
//            debugprint("verify trade partner false. ");
            return false;
        }
        return true;
    }

    protected boolean insideAcceptedTrade() {
//        if ()
        return true;
    }

    protected boolean verifyPlayerOnSameMap(Character player) {
        return player.getMapId() == chr.getMapId();
    }

}
