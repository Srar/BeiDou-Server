package org.gms.server.bot.trade;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class BotTradeQueue {

    // Written from netty threads (Trade.inviteTrade) and read from bot tick virtual threads.
    private final Map<Character, Character> queues;
    private static final BotTradeQueue botTradeQueue = new BotTradeQueue();

    private BotTradeQueue() {
        queues = new ConcurrentHashMap<>();
    }

    public static BotTradeQueue getInstance() {
        return botTradeQueue;
    }

    public void addTradeRequest(Character fakechar, Character partner) {
        log.debug("addTradeRequest");
        queues.putIfAbsent(fakechar, partner);
    }

    public Character getTradeRequest(Character fakechar) {
        if (hasPendingTrades(fakechar)) {
            return queues.get(fakechar);
        }
        return null;
    }

    public boolean hasPendingTrades(Character fakechar) {
        if (queues.containsKey(fakechar)) {
            return true;
        }
        return false;
    }

    public void removeTradeRequest(Character fakechar) {
        if (hasPendingTrades(fakechar)) {
            queues.remove(fakechar);
        }
    }


}
