package org.gms.server.bot;

import org.gms.client.Character;
import org.gms.server.bot.gcmove.LodCounts;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * 换装广播节流单测（2026-08-17 客户端崩溃事故的降载修复防线）：
 * <ul>
 *   <li>时间窗口纯逻辑：每 bot 换装广播最小间隔 {@link BotCustomization#MIN_LOOK_BROADCAST_INTERVAL_MS}，
 *       窗口内多次换装只产生 1 次广播，窗口结束后补发 1 次最终状态；</li>
 *   <li>观察门控：无真实玩家观察的图跳过广播（且不调度补发）；</li>
 *   <li>落图门控：bot 尚未落图（装饰先于 spawn）时跳过广播且不调度补发——spawn 包自带
 *       完整外观，look 广播只会把尚未 spawn 的 cid 外观包发给客户端；</li>
 *   <li>补发防泄漏：补发任务执行时 bot 已销毁/换图/真人已离开则不广播，只清 dirty。</li>
 * </ul>
 * 断言面为 {@link BotCustomization#scheduleLookBroadcast}（等价于「装备落库后的一次换装广播
 * 决策」）：EquipItem 全路径依赖 ItemInformationProvider 单例，其静态初始化在离线单测下无法
 * 完成（DatabaseConnection 返回 null），故装备落库动作不做 mock 覆盖——「装备照常穿上」由
 * EquipItem 的代码结构保证（addItemFromDB 在 scheduleLookBroadcast 之前执行，节流只影响广播）。
 * 广播副作用（equipchanged/updateLocalStats/messenger）封装在 Character.equipChanged 内整体节流，
 * 以 mock Character 上的 equipChanged 调用次数为断言面。
 */
class BotCustomizationTest {

    private static final int BOT_ID = 2_000_000_001;
    private static final int MAP_ID = 100000000;

    private MockedStatic<BotExecutors> executorsStatic;
    private MockedStatic<LodCounts> lodStatic;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
        // 预热 Character 类加载（其静态初始化经 mock 的 applicationContext 走完）：
        // 若首次 mock(Character.class) 发生在某个未完成的 when(...) 链内部，mockito 会误报
        // UnfinishedStubbingException，导致 Character 类初始化失败并殃及后续所有用例。
        Mockito.mock(Character.class);
    }

    @BeforeEach
    void setUp() {
        BotCustomization.resetLookBroadcastState();
        executorsStatic = Mockito.mockStatic(BotExecutors.class);
        // 固定观察轮未运行：hasRealPlayerObserver 走线性扫描路径，门控完全由
        // map.getCharacters() 的内容决定（不受其它测试类启动的观察轮状态污染）。
        lodStatic = Mockito.mockStatic(LodCounts.class);
        lodStatic.when(LodCounts::trackerRunning).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        // 逐个判空：若 setUp 中途失败（某 mock 注册失败），已注册的仍需关闭，
        // 否则同一线程上下一个用例会报 "static mocking is already registered"。
        if (lodStatic != null) {
            lodStatic.close();
        }
        if (executorsStatic != null) {
            executorsStatic.close();
        }
    }

    @Test
    void timeWindowPureLogic() {
        // 首次换装（无上次广播记录）恒可广播
        assertTrue(BotCustomization.shouldBroadcastNow(BOT_ID, 0));

        BotCustomization.noteBroadcast(BOT_ID);
        long now = System.currentTimeMillis();

        // 窗口内（< 3000ms）不广播，窗口边界（>= 3000ms）恢复广播
        assertFalse(BotCustomization.shouldBroadcastNow(BOT_ID));
        assertFalse(BotCustomization.shouldBroadcastNow(BOT_ID, now + 2999));
        assertTrue(BotCustomization.shouldBroadcastNow(BOT_ID, now + 3000));
        assertTrue(BotCustomization.shouldBroadcastNow(BOT_ID, now + 5000));
    }

    @Test
    void equipItemBroadcastsOnceWithinWindowThenFlushesOnce() {
        MapleMap map = observedMap(MAP_ID);
        Character bot = botOnMap(map);

        // 窗口内连续两次换装广播决策：装备照常落库（调用方职责），广播只发首条
        BotCustomization.scheduleLookBroadcast(bot);
        BotCustomization.scheduleLookBroadcast(bot);

        Mockito.verify(bot, Mockito.times(1)).equipChanged();

        // 第二次换装应调度一个补发任务，延迟落在窗口剩余时间内
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
        executorsStatic.verify(
                () -> BotExecutors.schedule(taskCaptor.capture(), delayCaptor.capture()),
                Mockito.times(1));
        long delay = delayCaptor.getValue();
        assertTrue(delay > 0 && delay <= BotCustomization.MIN_LOOK_BROADCAST_INTERVAL_MS,
                "flush delay must be within (0, 3000], got " + delay);

        // 窗口结束补发：最终状态广播一次
        taskCaptor.getValue().run();
        Mockito.verify(bot, Mockito.times(2)).equipChanged();

        // 补发后进入新窗口：紧接着的换装再次节流并调度新补发（而非立即广播）
        BotCustomization.scheduleLookBroadcast(bot);
        Mockito.verify(bot, Mockito.times(2)).equipChanged();
        executorsStatic.verify(
                () -> BotExecutors.schedule(any(Runnable.class), anyLong()),
                Mockito.times(2));
    }

    @Test
    void equipItemSkipsBroadcastWithoutRealPlayerObserver() {
        MapleMap map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getId()).thenReturn(MAP_ID);
        Mockito.when(map.getCharacters()).thenReturn(List.of()); // 无真人观察
        Character bot = botOnMap(map);

        BotCustomization.scheduleLookBroadcast(bot);
        BotCustomization.scheduleLookBroadcast(bot);

        // 无人图：不广播、不记录时间、不调度补发（进图/spawn 时自然同步）
        Mockito.verify(bot, Mockito.never()).equipChanged();
        executorsStatic.verify(
                () -> BotExecutors.schedule(any(Runnable.class), anyLong()),
                Mockito.never());
    }

    @Test
    void equipItemSkipsBroadcastWhenBotNotPlacedOnMap() {
        MapleMap map = observedMap(MAP_ID);
        // 装饰阶段先于 spawn：fakechar 已指向目标图，但图上还查不到该 bot
        // （getCharacterById 返回 null，即列表不含该 bot）
        Character bot = Mockito.mock(Character.class);
        Mockito.when(bot.getId()).thenReturn(BOT_ID);
        Mockito.when(bot.getMap()).thenReturn(map);
        Mockito.when(map.getCharacterById(BOT_ID)).thenReturn(null);

        BotCustomization.scheduleLookBroadcast(bot);
        BotCustomization.scheduleLookBroadcast(bot);

        // 未落图：不广播、不调度补发（spawn 包自带完整外观，look 广播是冗余+风险包）
        Mockito.verify(bot, Mockito.never()).equipChanged();
        executorsStatic.verify(
                () -> BotExecutors.schedule(any(Runnable.class), anyLong()),
                Mockito.never());
    }

    @Test
    void equipItemSkipsBroadcastWhenBotMapIsNull() {
        // bot 已销毁/尚未关联任何地图：getMap() 为 null
        Character bot = Mockito.mock(Character.class);
        Mockito.when(bot.getId()).thenReturn(BOT_ID);
        Mockito.when(bot.getMap()).thenReturn(null);

        BotCustomization.scheduleLookBroadcast(bot);

        Mockito.verify(bot, Mockito.never()).equipChanged();
        executorsStatic.verify(
                () -> BotExecutors.schedule(any(Runnable.class), anyLong()),
                Mockito.never());
    }

    @Test
    void flushSkipsWhenBotLeftOrChangedMap() {
        MapleMap map = observedMap(MAP_ID);
        // 目标图 mock 提前创建：不能在 thenReturn(...) 实参里构造 mock（嵌套 stubbing 会被 mockito 拒绝）
        MapleMap otherMap = observedMap(MAP_ID + 1);
        Character bot = botOnMap(map);

        BotCustomization.scheduleLookBroadcast(bot);
        BotCustomization.scheduleLookBroadcast(bot);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        executorsStatic.verify(
                () -> BotExecutors.schedule(taskCaptor.capture(), anyLong()),
                Mockito.times(1));

        // 补发执行时 bot 已换图：清 dirty 不广播
        Mockito.when(bot.getMap()).thenReturn(otherMap);
        taskCaptor.getValue().run();
        Mockito.verify(bot, Mockito.times(1)).equipChanged();

        // bot 已销毁（getMap()==null）：同样不广播
        BotCustomization.resetLookBroadcastState();
        Mockito.when(bot.getMap()).thenReturn(map);
        BotCustomization.scheduleLookBroadcast(bot);
        BotCustomization.scheduleLookBroadcast(bot);
        ArgumentCaptor<Runnable> taskCaptor2 = ArgumentCaptor.forClass(Runnable.class);
        executorsStatic.verify(
                () -> BotExecutors.schedule(taskCaptor2.capture(), anyLong()),
                Mockito.times(2));
        Mockito.when(bot.getMap()).thenReturn(null);
        taskCaptor2.getValue().run();
        Mockito.verify(bot, Mockito.times(2)).equipChanged();
    }

    @Test
    void flushSkipsWhenObserverLeftDuringWindow() {
        MapleMap map = observedMap(MAP_ID);
        Character bot = botOnMap(map);

        BotCustomization.scheduleLookBroadcast(bot);
        BotCustomization.scheduleLookBroadcast(bot);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        executorsStatic.verify(
                () -> BotExecutors.schedule(taskCaptor.capture(), anyLong()),
                Mockito.times(1));

        // 窗口期间真人离开：补发时重新检查观察者，无人图不广播
        Mockito.when(map.getCharacters()).thenReturn(List.of());
        taskCaptor.getValue().run();
        Mockito.verify(bot, Mockito.times(1)).equipChanged();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** 有真人观察的地图 mock：getCharacters 返回一个非 bot 角色（id=0 不在 bot 区段）。 */
    private MapleMap observedMap(int mapId) {
        // realPlayer 先于 stubbing 创建（Character 类已在 @BeforeAll 预热），
        // 避免在 when(...) 未完成时触发类加载被 mockito 误判为嵌套 stubbing。
        Character realPlayer = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getId()).thenReturn(mapId);
        Mockito.when(map.getCharacters()).thenReturn(List.of(realPlayer));
        return map;
    }

    /** 站在指定图上的 bot mock（仅广播侧依赖的 id/getMap/equipChanged）；同时登记为已落图。 */
    private Character botOnMap(MapleMap map) {
        Character bot = Mockito.mock(Character.class);
        Mockito.when(bot.getId()).thenReturn(BOT_ID);
        Mockito.when(bot.getMap()).thenReturn(map);
        Mockito.when(map.getCharacterById(BOT_ID)).thenReturn(bot);
        return bot;
    }
}
