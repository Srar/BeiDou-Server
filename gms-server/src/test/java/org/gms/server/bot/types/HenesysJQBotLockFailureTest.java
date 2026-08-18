package org.gms.server.bot.types;

import org.gms.client.Character;
import org.gms.server.bot.replay.InPacketReader;
import org.gms.server.bot.replay.MovementCommands;
import org.gms.server.bot.replay.MovementRecording;
import org.gms.test.BotTestSupport;
import org.gms.util.Randomizer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JQ 攀爬锁竞争成功判定回归（m2-R2）：attemptJQ 的 lastAttemptSuccess 曾在
 * tryAcquireMovementLock 之前赋值——tier7 回放因锁被占放弃本轮时也宣告成功，
 * recover() 据此置 hasReachedTop 并跳成功庆祝。修复后回放成功才赋值，锁失败必须保持 false。
 */
class HenesysJQBotLockFailureTest {

    private static final int BOT_ID = 2_000_003_201;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @Test
    void attemptJQKeepsLastAttemptSuccessFalseWhenLockBusy() throws Exception {
        Character chr = Mockito.mock(Character.class);
        Mockito.when(chr.getId()).thenReturn(BOT_ID);
        Mockito.when(chr.getName()).thenReturn("TestJQBot");
        Mockito.when(chr.getPosition()).thenReturn(new Point(0, 0));
        Mockito.when(chr.getMapId()).thenReturn(0);
        Mockito.when(chr.getMap()).thenReturn(null);

        HenesysJQBot bot = new HenesysJQBot(chr);

        // 模拟 gcmove 动态会话（或任何他方）持锁：回放拿锁必然失败
        assertTrue(MovementCommands.tryAcquireMovementLock(chr), "测试前置：应能取得移动锁");
        try {
            // 录制品读取钉死为成功（不抛异常），让流程走到 tryAcquire 的 lockBusy 分支；
            // Randomizer.nextInt 钉死为 1：跳过 BotTiming.after 与 scheduleMidJQChat 的调度。
            try (MockedStatic<InPacketReader> recMock = Mockito.mockStatic(InPacketReader.class);
                 MockedStatic<Randomizer> randMock = Mockito.mockStatic(Randomizer.class)) {
                recMock.when(() -> InPacketReader.getMovementRecording(Mockito.anyInt(), Mockito.anyString()))
                        .thenReturn(Mockito.mock(MovementRecording.class));
                randMock.when(() -> Randomizer.nextInt(Mockito.anyInt())).thenReturn(1);

                Method attemptJQ = HenesysJQBot.class.getDeclaredMethod("attemptJQ");
                attemptJQ.setAccessible(true);
                attemptJQ.invoke(bot);
            }

            Field f = HenesysJQBot.class.getDeclaredField("lastAttemptSuccess");
            f.setAccessible(true);
            assertFalse((Boolean) f.get(bot),
                    "锁被占放弃回放时 lastAttemptSuccess 必须为 false（不得宣告成功）");
        } finally {
            MovementCommands.releaseMovementLock(chr);
        }
    }
}
