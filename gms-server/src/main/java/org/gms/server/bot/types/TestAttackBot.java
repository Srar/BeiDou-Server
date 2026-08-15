package org.gms.server.bot.types;

import org.gms.client.Character;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.attack.BotAttackDriver;

/**
 * Minimal attack test bot: each tick, swing at the nearest in-reach mob with whatever
 * attack its class + weapon resolve to. No movement, no sub-states - it stands where it
 * spawned and attacks. Used to verify the per-class attack routes on Henesys Hunting
 * Ground 1. Mobs only move when a real player is on the map, so keep a GM there to feed
 * targets into reach.
 */
public class TestAttackBot extends BotSM {

    public TestAttackBot(Character character) {
        super(character);
        botType = "TestAttackBot";
    }

    @Override
    public void updateState() {
        super.updateState();
        // gms 移植：SoloMapling 的 checkIfNotRunningOrPaused() 未移植；等价判定为
        // !getRunning() || getState() == BotState.PAUSE。
        if (!getRunning() || getState() == BotState.PAUSE) {
            return;
        }
        BotAttackDriver.botAttack(getChr());
    }
}
