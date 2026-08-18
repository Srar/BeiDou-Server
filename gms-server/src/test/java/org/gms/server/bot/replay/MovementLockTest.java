package org.gms.server.bot.replay;

import org.gms.client.Character;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 移动锁互斥与释放单测（录制回放 vs gcmove 动态会话互斥的基础设施）。
 * <p>
 * 锁按 Character id 维度隔离：同一 bot 上 tryAcquire 成功后必须显式 release，
 * 期间再次 tryAcquire 失败（回放入口据此放弃本轮，避免与 gcmove 并发驱动）；
 * 不同 bot 之间互不影响。
 */
class MovementLockTest {

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    private static Character bot(long id) {
        Character chr = Mockito.mock(Character.class);
        Mockito.when(chr.getId()).thenReturn((int) id);
        return chr;
    }

    @Test
    void tryAcquireSucceedsThenBlocksReacquire() {
        Character chr = bot(2_000_001_001);
        assertTrue(MovementCommands.tryAcquireMovementLock(chr), "首次拿锁应成功");
        assertTrue(MovementCommands.isBotMoving(chr), "拿锁后应标记移动中");
        assertFalse(MovementCommands.tryAcquireMovementLock(chr), "同 bot 重复拿锁应失败（互斥）");
        MovementCommands.releaseMovementLock(chr);
    }

    @Test
    void releaseUnlocksForReacquire() {
        Character chr = bot(2_000_001_002);
        assertTrue(MovementCommands.tryAcquireMovementLock(chr));
        MovementCommands.releaseMovementLock(chr);
        assertFalse(MovementCommands.isBotMoving(chr), "释放后不应再标记移动中");
        assertTrue(MovementCommands.tryAcquireMovementLock(chr), "释放后可重新拿锁");
        MovementCommands.releaseMovementLock(chr);
    }

    @Test
    void locksAreIndependentPerCharacter() {
        Character a = bot(2_000_001_003);
        Character b = bot(2_000_001_004);
        assertTrue(MovementCommands.tryAcquireMovementLock(a));
        assertTrue(MovementCommands.tryAcquireMovementLock(b), "不同 bot 的锁互不影响");
        assertFalse(MovementCommands.tryAcquireMovementLock(a));
        MovementCommands.releaseMovementLock(a);
        MovementCommands.releaseMovementLock(b);
    }

    @Test
    void releaseWithoutAcquireIsNoOp() {
        Character chr = bot(2_000_001_005);
        MovementCommands.releaseMovementLock(chr); // 不得抛出，不得影响后续拿锁
        assertTrue(MovementCommands.tryAcquireMovementLock(chr));
        MovementCommands.releaseMovementLock(chr);
    }
}
