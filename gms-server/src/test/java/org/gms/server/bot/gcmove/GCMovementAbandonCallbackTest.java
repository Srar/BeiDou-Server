package org.gms.server.bot.gcmove;

import org.gms.client.Character;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GCMovement 移动失败回调：abandonMove 丢弃到达回调并触发 onAbandon（仅一次）；
 * fireArrival 只触发到达回调。回调注册表经反射注入，避免驱动引擎起 tick。
 */
class GCMovementAbandonCallbackTest {

    private static final int BOT_ID = 888_000_777;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @AfterEach
    void tearDown() throws Exception {
        arrivalCallbacks().remove(BOT_ID);
        abandonCallbacks().remove(BOT_ID);
    }

    @Test
    void abandonMoveFiresAbandonCallbackOnceAndDropsArrival() throws Exception {
        AtomicInteger arrived = new AtomicInteger();
        AtomicInteger abandoned = new AtomicInteger();
        arrivalCallbacks().put(BOT_ID, arrived::incrementAndGet);
        abandonCallbacks().put(BOT_ID, abandoned::incrementAndGet);

        Character bot = Mockito.mock(Character.class);
        Mockito.when(bot.getId()).thenReturn(BOT_ID);
        BotMovementState entry = new BotMovementState(bot, null);

        GCMovement.abandonMove(entry);
        assertEquals(0, arrived.get(), "abandon 不得触发到达回调");
        assertEquals(1, abandoned.get(), "abandon 应触发一次失败兜底回调");

        GCMovement.abandonMove(entry);
        assertEquals(1, abandoned.get(), "回调只应触发一次（取出后不再重复）");
    }

    @Test
    void fireArrivalFiresArrivalCallbackOnly() throws Exception {
        AtomicInteger arrived = new AtomicInteger();
        AtomicInteger abandoned = new AtomicInteger();
        arrivalCallbacks().put(BOT_ID, arrived::incrementAndGet);
        abandonCallbacks().put(BOT_ID, abandoned::incrementAndGet);

        Character bot = Mockito.mock(Character.class);
        Mockito.when(bot.getId()).thenReturn(BOT_ID);
        BotMovementState entry = new BotMovementState(bot, null);

        GCMovement.fireArrival(entry);
        assertEquals(1, arrived.get(), "到达应触发到达回调");
        assertEquals(0, abandoned.get(), "到达不得触发失败兜底回调");
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Runnable> arrivalCallbacks() throws Exception {
        return (Map<Integer, Runnable>) callbacksField("ARRIVAL_CALLBACKS").get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Runnable> abandonCallbacks() throws Exception {
        return (Map<Integer, Runnable>) callbacksField("ABANDON_CALLBACKS").get(null);
    }

    private static Field callbacksField(String name) throws NoSuchFieldException {
        Field f = GCMovement.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
