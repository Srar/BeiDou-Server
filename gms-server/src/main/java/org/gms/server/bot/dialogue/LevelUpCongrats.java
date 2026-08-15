package org.gms.server.bot.dialogue;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotTiming;
import org.gms.server.bot.event.EventType;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 共享的升级祝贺。某角色（玩家或 bot）升级后数秒，附近的 bot 反应：转向、说句祝贺、做个表情。
 * SocialBot 与 TownWandererBot 都在 handleEvent 里对 LEVEL_UP 事件委托到这里。尽力而为的氛围——
 * 任何门控失败就丢弃。反刷屏靠概率 + 错开延迟（无全服应答上限）：适度单 bot 概率与 5-9s 抖动，
 * 让人群里散落几声「grats」而非整齐划一地齐喊。反应 bot 在事件到达时与台词触发时都必须可用
 * （不在对话/旅行中），且触发时地图仍被观察（玩家可能已走开）。
 */
@Slf4j
public final class LevelUpCongrats {

    private LevelUpCongrats() {}

    public static volatile double CONGRATS_CHANCE = 0.30;     // 每次升级，每个附近 bot 的概率
    public static volatile int CONGRATS_RADIUS = 350;         // px；只有这么近的 bot 反应
    public static volatile long CONGRATS_DELAY_MIN_MS = 5000;
    public static volatile long CONGRATS_DELAY_MAX_MS = 9000;

    private static final String NODE = "CongratsLevelUp";
    private static final int[] CONGRATS_EMOTES = {2, 6};

    /** 决定 THIS bot 是否祝贺升级者，若是则调度错开的反应。 */
    public static void react(BotSM bot, GameEvent event) {
        if (bot == null || event == null || event.getType() != EventType.LEVEL_UP) {
            return;
        }
        Character me = bot.getChr();
        if (me == null || me.getMap() == null) {
            return;
        }
        Character leveler = resolveLeveler(event, me);
        if (leveler == null) {
            return;
        }
        if (leveler.getId() == me.getId()) {
            return; // 从不祝贺自己（也阻止 bot 对自己升级反应）
        }
        if (!bot.isAvailableForAmbientActions()) {
            return; // 忙——对话中 / 换图中
        }
        if (leveler.getMap() != me.getMap()) {
            return; // 不同图实例（matchesFilter 通常已挡住）
        }
        double radius = CONGRATS_RADIUS;
        if (me.getPosition().distanceSq(leveler.getPosition()) > radius * radius) {
            return; // 太远，不足以注意到
        }
        if (ThreadLocalRandom.current().nextDouble() >= CONGRATS_CHANCE) {
            return; // 这次保持安静
        }
        BotTiming.afterRandom(CONGRATS_DELAY_MIN_MS, CONGRATS_DELAY_MAX_MS, () -> fire(bot, leveler));
    }

    /** gms GameEvent 只携带 sourceCharacterId；事件 map 范围与 bot 一致，从其所在图解析升级者。 */
    private static Character resolveLeveler(GameEvent event, Character me) {
        MapleMap map = me.getMap();
        return map == null ? null : map.getCharacterById(event.getSourceCharacterId());
    }

    private static void fire(BotSM bot, Character leveler) {
        Character me = bot.getChr();
        if (me == null || me.getMap() == null || leveler == null) {
            return;
        }
        if (!bot.isAvailableForAmbientActions()) {
            return; // 错开窗口内变忙
        }
        if (leveler.getMap() != me.getMap()) {
            return; // 升级者离图
        }
        if (!GCMovement.isMapObserved(me.getMapId())) {
            return; // 现在没人看见
        }
        String line;
        try {
            line = BotDialogueHandler.getRandomResolvedLine(bot, NODE, leveler);
        } catch (Exception e) {
            log.debug("LevelUpCongrats line resolution failed for bot {}",
                    bot != null && bot.getChr() != null ? bot.getChr().getId() : "null", e);
            line = null;
        }
        final String spoken = line;
        final int emote = CONGRATS_EMOTES[ThreadLocalRandom.current().nextInt(CONGRATS_EMOTES.length)];
        BotTiming.chain()
                .stopUnless(() -> bot.isAvailableForAmbientActions() && leveler.getMap() == me.getMap())
                .run(() -> faceToward(me, leveler))
                .pauseRandom(300, 700)
                .run(() -> {
                    if (spoken != null) {
                        botSpeak(me, spoken);
                    }
                })
                .pause(400)
                .run(() -> botEmote(me, emote))
                .start();
    }

    /** 等价 SoloMapling MovementCommands.botFaceTowardsPoint；旧引擎 bot 用 stance 翻转近似。 */
    private static void faceToward(Character me, Character target) {
        if (me == null || target == null) {
            return;
        }
        boolean left = target.getPosition().getX() < me.getPosition().getX();
        if (GCMovement.isEnabled(me)) {
            GCMovement.face(me, left);
        } else {
            me.broadcastStance(left ? 1 : 0);
        }
    }

    private static void botSpeak(Character character, String message) {
        if (character == null || character.getMap() == null) {
            return;
        }
        character.getMap().broadcastMessage(
                PacketCreator.getChatText(character.getId(), message, character.getWhiteChat(), 0));
    }

    private static void botEmote(Character character, int emote) {
        if (character == null || character.getMap() == null) {
            return;
        }
        character.getMap().broadcastMessage(PacketCreator.facialExpression(character, emote));
    }
}
