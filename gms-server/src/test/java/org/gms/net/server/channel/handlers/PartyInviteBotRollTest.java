package org.gms.net.server.channel.handlers;

import org.gms.client.Character;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotSM;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.party.BotRecruitManager;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PartyOperationHandler 直接右键邀请 bot 的掷骰入口 rollDirectPartyInvite（gms 增强）：
 * 类型→概率映射（SocialBot 0.70 / TrainingBot 0.80 / 未知 0.30 / FollowerBot 恒拒 / OPQBot 恒收）、
 * 命中写 ARMED 武装窗口、未命中不写冷却（被拒后立刻再邀仍有完整机会）。
 * <p>
 * 不写 PartyOperationHandler 集成测试（netty/DB 太重）；FOLLOWER_CAP 分支跳过——
 * 该分支依赖 activeFollowerCount() 扫描 BotStorage 中 FollowerBot 类型的注册 bot，
 * 需注册 30 个 mock FollowerBot 才能压线，收益低且与掷骰主语义无关。
 */
class PartyInviteBotRollTest {

    private static final int PLAYER_ID = 4242;
    private static final int MAX_ROLLS = 30;
    private static final AtomicInteger NEXT_BOT_ID = new AtomicInteger(BotHelpers.BOT_BASE_ID + 1);

    private int botId;
    private Character botChr;
    private Character player;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        botId = NEXT_BOT_ID.incrementAndGet();
        botChr = Mockito.mock(Character.class);
        Mockito.when(botChr.getId()).thenReturn(botId);
        Mockito.when(botChr.getName()).thenReturn("RollBot" + botId);
        Mockito.when(botChr.getLevel()).thenReturn(30);

        player = Mockito.mock(Character.class);
        Mockito.when(player.getId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getName()).thenReturn("Inviter");
    }

    @AfterEach
    void tearDown() {
        BotRecruitManager.clearArmed(botId);
        BotRecruitManager.clearHandoffs(botId);
        BotStorage.removeActiveBot(botId);
    }

    /** 注册一个 getBotType() 返回指定类型的 mock BotSM（getChr() 为 null，addActiveBot 跳过 map 索引）。 */
    private void registerBot(String botType) {
        BotSM botSM = Mockito.mock(BotSM.class);
        Mockito.when(botSM.getBotType()).thenReturn(botType);
        BotStorage.addActiveBot(botId, botSM);
    }

    /** 循环掷骰直到命中（上限 MAX_ROLLS 次），返回最终命中状态。 */
    private boolean rollUntilHit() {
        boolean hit = false;
        for (int i = 0; i < MAX_ROLLS && !hit; i++) {
            hit = BotRecruitManager.rollDirectPartyInvite(botChr, player);
        }
        return hit;
    }

    @Test
    void socialBotHitArmsWindowForInviter() {
        registerBot("SocialBot");

        assertTrue(rollUntilHit(), "SocialBot (0.70) must hit within " + MAX_ROLLS + " rolls");
        assertTrue(BotRecruitManager.isArmed(botId), "hit must write the ARMED window");
        assertEquals(PLAYER_ID, BotRecruitManager.armedInviterId(botId),
                "armed inviter must be the direct inviter");
    }

    @Test
    void trainingBotHitArmsWindowForInviter() {
        registerBot("TrainingBot");

        assertTrue(rollUntilHit(), "TrainingBot (0.80) must hit within " + MAX_ROLLS + " rolls");
        assertTrue(BotRecruitManager.isArmed(botId), "hit must write the ARMED window");
        assertEquals(PLAYER_ID, BotRecruitManager.armedInviterId(botId),
                "armed inviter must be the direct inviter");
    }

    @Test
    void noCooldownAfterDecline() {
        registerBot("SocialBot");

        // 先强制一次未命中（SocialBot 未命中率 0.30）。每次掷骰前先 clearArmed：
        // 否则一旦命中，武装短路（同玩家兑现）会让后续 roll 恒 true，miss 永远不可达。
        boolean declined = false;
        for (int i = 0; i < MAX_ROLLS && !declined; i++) {
            BotRecruitManager.clearArmed(botId);
            declined = !BotRecruitManager.rollDirectPartyInvite(botChr, player);
        }
        assertTrue(declined, "SocialBot (0.30 miss) must miss within " + MAX_ROLLS + " rolls");
        assertFalse(BotRecruitManager.isArmed(botId), "clean slate: bot must not be armed before the re-invite");

        // 防「写冷却但直接路径不读」的实现变体：若实现误写 DECLINED_UNTIL，对话路径
        // rollPartyAsk（chance=1.0 排除随机性）会返回 ON_COOLDOWN 而非 ACCEPTED。
        assertEquals(BotRecruitManager.RecruitAnswer.ACCEPTED,
                BotRecruitManager.rollPartyAsk(botChr, player, 1.0, false),
                "direct-invite decline must not poison the dialogue cooldown");

        // 紧接着立刻再邀：若无冷却（未写 DECLINED_UNTIL）则仍能命中；
        // 若实现误写冷却，此循环会一直 false 直到耗尽 MAX_ROLLS 而失败。
        assertTrue(rollUntilHit(),
                "no cooldown: an immediate re-invite after a decline must be able to hit again");
        assertTrue(BotRecruitManager.isArmed(botId), "re-invite hit must arm the window");
        assertEquals(PLAYER_ID, BotRecruitManager.armedInviterId(botId));
    }

    @Test
    void followerBotRollsTrueWithoutArming() {
        registerBot("FollowerBot");

        // FollowerBot 放行入队（true）但不写 ARMED——接受与否交 pollLeaderInvite
        // （750ms tick）按 leader 裁决，直接邀请不做同步接受
        assertTrue(BotRecruitManager.rollDirectPartyInvite(botChr, player));
        assertFalse(BotRecruitManager.isArmed(botId), "FollowerBot must not write the ARMED window");
        assertEquals(-1, BotRecruitManager.armedInviterId(botId), "no armed inviter for FollowerBot");
    }

    @Test
    void opqBotAlwaysAccepts() {
        registerBot("OPQBot");

        // OPQBot 无条件接受（与 BotPartyLogic.checkPartyQueue 语义一致），单次调用即恒 true
        assertTrue(BotRecruitManager.rollDirectPartyInvite(botChr, player));
        // OPQ 无条件接受不依赖 ARMED 武装窗口：不写 ARMED
        assertFalse(BotRecruitManager.isArmed(botId), "OPQBot must not write the ARMED window");
        assertEquals(-1, BotRecruitManager.armedInviterId(botId), "no armed inviter for OPQBot");
    }

    @Test
    void nullBotSmFallsBackToDefaultChance() {
        // 不注册 BotSM：getBotById 返回 null → 0.30 默认概率路径
        assertTrue(rollUntilHit(), "null BotSM must hit via 0.30 fallback within " + MAX_ROLLS + " rolls");
        assertTrue(BotRecruitManager.isArmed(botId), "fallback hit must arm the window");
        assertEquals(PLAYER_ID, BotRecruitManager.armedInviterId(botId));
    }

    @Test
    void unknownBotTypeFallsBackToDefaultChance() {
        registerBot("IDLE_BOT"); // 非 Social/Training/Follower/OPQ 的未知类型

        assertTrue(rollUntilHit(), "unknown type must hit via 0.30 fallback within " + MAX_ROLLS + " rolls");
        assertTrue(BotRecruitManager.isArmed(botId), "fallback hit must arm the window");
        assertEquals(PLAYER_ID, BotRecruitManager.armedInviterId(botId));
    }

    @Test
    void subLevelTenDeclinesWithoutArming() {
        registerBot("SocialBot");
        Mockito.when(botChr.getLevel()).thenReturn(9);

        // 防御分支：handler 已挡 sub-10 邀请，这里双保险直接拒绝且不武装
        assertFalse(BotRecruitManager.rollDirectPartyInvite(botChr, player));
        assertFalse(BotRecruitManager.isArmed(botId));
    }

    @Test
    void levelTenExactlyPassesGate() {
        registerBot("SocialBot");
        Mockito.when(botChr.getLevel()).thenReturn(10);

        // sub-10 gate 不挡 10：level==10 应可正常命中并武装
        assertTrue(rollUntilHit(), "level 10 bot must be able to hit within " + MAX_ROLLS + " rolls");
        assertTrue(BotRecruitManager.isArmed(botId), "level 10 hit must arm the window");
        assertEquals(PLAYER_ID, BotRecruitManager.armedInviterId(botId));
    }

    @Test
    void armedSameInviterSkipsSecondRoll() {
        registerBot("SocialBot");

        // 对话掷骰 chance=1.0 确定命中 → 武装窗口写入（inviter = player）
        assertEquals(BotRecruitManager.RecruitAnswer.ACCEPTED,
                BotRecruitManager.rollPartyAsk(botChr, player, 1.0, false));
        assertEquals(PLAYER_ID, BotRecruitManager.armedInviterId(botId));

        // 同玩家直接右键邀请：短路兑现对话承诺（100%），不二次掷骰、不覆盖窗口
        assertTrue(BotRecruitManager.rollDirectPartyInvite(botChr, player));
        assertTrue(BotRecruitManager.isArmed(botId), "armed window must stay intact");
        assertEquals(PLAYER_ID, BotRecruitManager.armedInviterId(botId),
                "armed inviter must be unchanged (no second roll, no overwrite)");
    }

    @Test
    void armedOtherInviterProtectedFromOverride() {
        registerBot("SocialBot");

        // 玩家 A 对话武装成功
        assertEquals(BotRecruitManager.RecruitAnswer.ACCEPTED,
                BotRecruitManager.rollPartyAsk(botChr, player, 1.0, false));
        assertEquals(PLAYER_ID, BotRecruitManager.armedInviterId(botId));

        // 玩家 B 在有效窗口内直接邀请：必须被拒绝且不得覆盖/偷走武装窗口
        Character playerB = Mockito.mock(Character.class);
        Mockito.when(playerB.getId()).thenReturn(PLAYER_ID + 1);
        Mockito.when(playerB.getName()).thenReturn("InviterB");

        assertFalse(BotRecruitManager.rollDirectPartyInvite(botChr, playerB),
                "armed window must protect the bot from a different inviter");
        assertEquals(PLAYER_ID, BotRecruitManager.armedInviterId(botId),
                "armed inviter must remain player A (no override)");
    }
}
