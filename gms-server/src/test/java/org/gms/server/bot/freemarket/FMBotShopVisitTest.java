package org.gms.server.bot.freemarket;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.world.World;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.BotLogic;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.commands.WarpCommands;
import org.gms.server.bot.dialogue.BotDialogueHandler;
import org.gms.server.bot.gcmove.GCMovement;
import org.gms.server.bot.gcmove.LodCounts;
import org.gms.server.bot.types.FMBot;
import org.gms.server.maps.HiredMerchant;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.PlayerShop;
import org.gms.server.maps.PlayerShopItem;
import org.gms.test.BotTestSupport;
import org.gms.util.DatabaseConnection;
import org.gms.util.PacketCreator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「摆摊 ↔ 逛店购买」闭环验证（T3）。
 *
 * <p>覆盖环节（全部走真实主代码，仅 mock 外部边界）：
 * <ol>
 *   <li>FMBot 摊位发现：{@link FMBot#processRoom} 按 MapObjectType 收集
 *       PlayerShop/HiredMerchant 并收进 visitQueue（按距离排序）。</li>
 *   <li>进店购买：{@link HiredMerchantAdapter#botBuyItem} / {@link PlayerShopAdapter#botBuyItemPlayerShop}
 *       在真实 HiredMerchant/PlayerShop 上完成购买：商品 bundles 减少/售罄移除、
 *       买家扣款、卖家入账、成交记录。</li>
 *   <li>闭环集成：真实 FMBot 状态机全链路驱动（RESET→NAV→PROCESS_ROOM→BROWSING→
 *       ENTER_SHOP→PROCESS_SHOP→EXIT_SHOP→EXIT_FM_ROOM），完成「发现→进店→购买→离店」。
 *       人工摊位侧未直接调用 ArtificialFreeMarket.BotPlayerStorePermit：其产出链依赖
 *       ItemSelector(itemConfig YAML) + ItemInformationProvider(wz 数据文件)，
 *       ItemInformationProvider 类加载即读取 wz 文件并触碰数据库（静态构造），无法在
 *       mock 环境确定性地运行；故以「与 BotPlayerStorePermit 产出形状一致」的人工摊位
 *       （地图上的 open PlayerShop/HiredMerchant + PlayerShopItem）替代，报告详见测试注释。</li>
 *   <li>边界：售罄后 closeShop/visitShop 返回 false；非 open 摊位 visitShop 拒绝；
 *       FMBot 对 closed PlayerShop 的购买被 PlayerShop.buy 的 visitor 门控兜底拒绝。</li>
 * </ol>
 *
 * <p>mock 要点：{@link PacketCreator} 全静态拦截（其 addCharLook→addCharEquips 触碰
 * ItemInformationProvider 单例，类加载即炸）；购买结算路径 mock
 * InventoryManipulator（库存落包）/Server+DatabaseConnection（卖家离线 mesos 落库）。
 */
class FMBotShopVisitTest {

    private static final int FM_ROOM_1 = 910000001;
    private static final int FM_ENTRANCE = 910000000;
    private static final int ITEM_ID_POTION = 2000000;
    private static final int PRICE = 500;
    private static final int MARKET_VALUE = 1000; // ratio 0.5 → shouldPurchaseItem 恒买

    private static final AtomicInteger NEXT_BOT_ID = new AtomicInteger(9_700_000);
    private final int botId = NEXT_BOT_ID.incrementAndGet();

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @AfterEach
    void tearDown() {
        BotStorage.removeActiveBot(botId);
    }

    // ─────────────────────────── 环节 1：摊位发现 ───────────────────────────

    @Test
    void processRoomCollectsShopsAndMerchantsIntoVisitQueueByDistance() {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(FM_ROOM_1);

        Character chr = mock(Character.class);
        when(chr.getMap()).thenReturn(map);
        when(chr.getPosition()).thenReturn(new Point(0, 0));

        PlayerShop shop = mock(PlayerShop.class);
        when(shop.getPosition()).thenReturn(new Point(5, 0));
        HiredMerchant merchant = mock(HiredMerchant.class);
        when(merchant.getPosition()).thenReturn(new Point(1, 0));

        when(map.getMapObjectsInRange(any(), anyDouble(), any())).thenReturn(List.of(shop, merchant));

        FMBot bot = new FMBot(chr);
        invokeProcessRoom(bot);

        List<ShopKeeper> queue = readVisitQueue(bot);
        assertEquals(2, queue.size(), "visitQueue 应收进图上的 PlayerShop 与 HiredMerchant");
        // 同一行摊位按距离排序：HiredMerchant(1,0) 近于 PlayerShop(5,0)，deckCut 对 2 项保持顺序
        assertTrue(queue.get(0) instanceof HiredMerchantAdapter, "更近的 HiredMerchant 应排在前面");
        assertTrue(queue.get(1) instanceof PlayerShopAdapter, "较远的 PlayerShop 应排在后面");
        assertSame(merchant, ((HiredMerchantAdapter) queue.get(0)).getMerchant(), "adapter 应包装原对象");
        assertSame(shop, ((PlayerShopAdapter) queue.get(1)).getShop(), "adapter 应包装原对象");
    }

    // ─────────────────────────── 环节 2：进店购买（adapter 直购） ───────────────────────────

    @Test
    void hiredMerchantAdapterBuyReducesBundlesAndSettlesMesos() {
        try (MockedStatic<InventoryManipulator> inv = Mockito.mockStatic(InventoryManipulator.class);
             MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class);
             MockedStatic<Server> srv = Mockito.mockStatic(Server.class);
             MockedStatic<DatabaseConnection> db = Mockito.mockStatic(DatabaseConnection.class)) {
            stubBuyerInventoryOk(inv);
            stubOfflineSellerPersistence(srv, db, "OwnerShop");

            Character owner = merchantOwnerMock();
            HiredMerchant merchant = new HiredMerchant(owner, "bot stall", 5030000);
            PlayerShopItem pItem = new PlayerShopItem(new Item(ITEM_ID_POTION, (short) 1, (short) 100), (short) 2, PRICE);
            merchant.addItem(pItem);

            Character buyer = buyerMock("BuyerX");
            new HiredMerchantAdapter(merchant).botBuyItem(buyer, pItem, (short) 1);

            assertEquals(1, pItem.getBundles(), "购买 1 组后 bundles 应从 2 减到 1");
            assertTrue(pItem.isExist(), "仍有库存的商品不应被移除");
            assertEquals(1, merchant.getSold().size(), "成交记录应有 1 条");
            assertEquals("BuyerX", merchant.getSold().get(0).getBuyer());
            assertEquals(ITEM_ID_POTION, merchant.getSold().get(0).getItemId());
            assertEquals(PRICE, merchant.getSold().get(0).getMesos());
            verify(buyer).gainMeso(-PRICE, false); // 买家结算被触发
        }
    }

    @Test
    void hiredMerchantAdapterBuyOutLastBundleRemovesItem() {
        try (MockedStatic<InventoryManipulator> inv = Mockito.mockStatic(InventoryManipulator.class);
             MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class);
             MockedStatic<Server> srv = Mockito.mockStatic(Server.class);
             MockedStatic<DatabaseConnection> db = Mockito.mockStatic(DatabaseConnection.class)) {
            stubBuyerInventoryOk(inv);
            stubOfflineSellerPersistence(srv, db, "OwnerShop");

            HiredMerchant merchant = new HiredMerchant(merchantOwnerMock(), "bot stall", 5030000);
            PlayerShopItem pItem = new PlayerShopItem(new Item(ITEM_ID_POTION, (short) 1, (short) 100), (short) 1, PRICE);
            merchant.addItem(pItem);

            Character buyer = buyerMock("BuyerX");
            new HiredMerchantAdapter(merchant).botBuyItem(buyer, pItem, (short) 1);

            assertEquals(0, pItem.getBundles());
            assertFalse(pItem.isExist(), "最后一组售出后商品应标记不存在");
            assertEquals(1, merchant.getSold().size());
        }
    }

    @Test
    void hiredMerchantAdapterLocatesSlotByIndexOfAndCallsBuy() {
        try (MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class)) {
            PlayerShopItem pItem = new PlayerShopItem(new Item(ITEM_ID_POTION, (short) 1, (short) 100), (short) 1, PRICE);
            HiredMerchant merchant = mock(HiredMerchant.class);
            when(merchant.getItems()).thenReturn(List.of(pItem));

            Character buyer = mock(Character.class);
            Client client = mock(Client.class);
            when(buyer.getClient()).thenReturn(client);

            new HiredMerchantAdapter(merchant).botBuyItem(buyer, pItem, (short) 1);

            // gms 底座差异：原生 buy(Client, int slot, short) 以列表下标定位，adapter 先 indexOf 再买
            verify(merchant).buy(client, 0, (short) 1);
        }
    }

    @Test
    void playerShopAdapterBuySettlesMesosAndReducesBundles() {
        try (MockedStatic<InventoryManipulator> inv = Mockito.mockStatic(InventoryManipulator.class);
             MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class)) {
            stubBuyerInventoryOk(inv);

            Character owner = playerShopOwnerMock();
            PlayerShop shop = new PlayerShop(owner, "bot stall", 5030000);
            shop.setOpen(true);
            PlayerShopItem pItem = new PlayerShopItem(new Item(ITEM_ID_POTION, (short) 1, (short) 100), (short) 2, PRICE);
            shop.addItem(pItem);

            Character buyer = buyerMock("BuyerX");
            assertTrue(shop.visitShop(buyer), "open 摊位应允许进店");
            assertTrue(shop.isVisitor(buyer), "进店后买家应登记为 visitor");

            new PlayerShopAdapter(shop).botBuyItemPlayerShop(buyer, pItem, 0, (short) 1);

            assertEquals(1, pItem.getBundles(), "购买 1 组后 bundles 应从 2 减到 1");
            assertEquals(1, shop.getSold().size());
            verify(buyer).gainMeso(-PRICE, false); // 买家扣款
            verify(owner).gainMeso(PRICE, true);   // 卖家入账
        }
    }

    // ─────────────────────────── 环节 3：闭环集成（FMBot 状态机全链路） ───────────────────────────

    @Test
    void fmBotStateMachineBuysFromHiredMerchantStall() {
        try (MockedStatic<LodCounts> lod = Mockito.mockStatic(LodCounts.class);
             MockedStatic<BotLogic> logic = Mockito.mockStatic(BotLogic.class);
             MockedStatic<GCMovement> gc = Mockito.mockStatic(GCMovement.class);
             MockedStatic<BotHelpers> helpers = Mockito.mockStatic(BotHelpers.class);
             MockedStatic<BotEconomy> econ = Mockito.mockStatic(BotEconomy.class);
             MockedStatic<BotDialogueHandler> dialogue = Mockito.mockStatic(BotDialogueHandler.class);
             MockedStatic<WarpCommands> warp = Mockito.mockStatic(WarpCommands.class);
             MockedStatic<InventoryManipulator> inv = Mockito.mockStatic(InventoryManipulator.class);
             MockedStatic<Server> srv = Mockito.mockStatic(Server.class);
             MockedStatic<DatabaseConnection> db = Mockito.mockStatic(DatabaseConnection.class);
             MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class)) {
            stubFsmStatics(lod, logic, gc, helpers, econ, dialogue);
            stubBuyerInventoryOk(inv);
            stubOfflineSellerPersistence(srv, db, "OwnerShop");

            MapleMap map = fsmMap();
            Character chr = fsmBotCharacter(map);

            Character owner = merchantOwnerMock();
            HiredMerchant merchant = new HiredMerchant(owner, "bot stall", 5030000);
            merchant.setOpen(true);
            PlayerShopItem pItem = new PlayerShopItem(new Item(ITEM_ID_POTION, (short) 1, (short) 100), (short) 1, PRICE);
            merchant.addItem(pItem);
            when(map.getMapObjectsInRange(any(), anyDouble(), any())).thenReturn(List.of(merchant));

            FMBot bot = runFsm(chr, 9);

            assertEquals(0, pItem.getBundles(), "FMBot 应按价买走最后 1 组");
            assertFalse(pItem.isExist());
            assertEquals(1, merchant.getSold().size(), "HiredMerchant 应有成交记录");
            verify(chr).gainMeso(-PRICE, false);
            verify(chr).setHiredMerchant(merchant); // 进店登记
            assertTrue(allNull(merchant.getVisitorCharacters()), "离店后应移除 visitor");
            warp.verify(() -> WarpCommands.botExitFMRoom(any(), anyInt()));
            assertEquals("NAV_TO_FM_ROOM", readFmBotState(bot), "逛完应退出房间进入下一个导航周期");
        }
    }

    @Test
    void fmBotStateMachineBuysFromPlayerShopStall() {
        try (MockedStatic<LodCounts> lod = Mockito.mockStatic(LodCounts.class);
             MockedStatic<BotLogic> logic = Mockito.mockStatic(BotLogic.class);
             MockedStatic<GCMovement> gc = Mockito.mockStatic(GCMovement.class);
             MockedStatic<BotHelpers> helpers = Mockito.mockStatic(BotHelpers.class);
             MockedStatic<BotEconomy> econ = Mockito.mockStatic(BotEconomy.class);
             MockedStatic<BotDialogueHandler> dialogue = Mockito.mockStatic(BotDialogueHandler.class);
             MockedStatic<WarpCommands> warp = Mockito.mockStatic(WarpCommands.class);
             MockedStatic<InventoryManipulator> inv = Mockito.mockStatic(InventoryManipulator.class);
             MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class)) {
            stubFsmStatics(lod, logic, gc, helpers, econ, dialogue);
            stubBuyerInventoryOk(inv);

            MapleMap map = fsmMap();
            Character chr = fsmBotCharacter(map);

            Character owner = playerShopOwnerMock();
            PlayerShop shop = new PlayerShop(owner, "bot stall", 5030000);
            shop.setOpen(true);
            PlayerShopItem pItem = new PlayerShopItem(new Item(ITEM_ID_POTION, (short) 1, (short) 100), (short) 1, PRICE);
            shop.addItem(pItem);
            when(map.getMapObjectsInRange(any(), anyDouble(), any())).thenReturn(List.of(shop));

            FMBot bot = runFsm(chr, 9);

            assertEquals(0, pItem.getBundles(), "FMBot 应买走 PlayerShop 的最后 1 组");
            assertFalse(pItem.isExist());
            assertEquals(1, shop.getSold().size(), "PlayerShop 应有成交记录");
            assertFalse(shop.isOpen(), "售罄后摊位应自动关闭");
            verify(chr).gainMeso(-PRICE, false);
            verify(owner).gainMeso(PRICE, true);
            verify(owner, atLeastOnce()).setPlayerShop(null); // 售罄关店路径
            assertEquals("NAV_TO_FM_ROOM", readFmBotState(bot), "逛完应退出房间进入下一个导航周期");
        }
    }

    // ─────────────────────────── 环节 4：边界 ───────────────────────────

    @Test
    void fmBotCannotBuyFromClosedPlayerShop() {
        try (MockedStatic<LodCounts> lod = Mockito.mockStatic(LodCounts.class);
             MockedStatic<BotLogic> logic = Mockito.mockStatic(BotLogic.class);
             MockedStatic<GCMovement> gc = Mockito.mockStatic(GCMovement.class);
             MockedStatic<BotHelpers> helpers = Mockito.mockStatic(BotHelpers.class);
             MockedStatic<BotEconomy> econ = Mockito.mockStatic(BotEconomy.class);
             MockedStatic<BotDialogueHandler> dialogue = Mockito.mockStatic(BotDialogueHandler.class);
             MockedStatic<WarpCommands> warp = Mockito.mockStatic(WarpCommands.class);
             MockedStatic<InventoryManipulator> inv = Mockito.mockStatic(InventoryManipulator.class);
             MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class)) {
            stubFsmStatics(lod, logic, gc, helpers, econ, dialogue);
            stubBuyerInventoryOk(inv);

            MapleMap map = fsmMap();
            Character chr = fsmBotCharacter(map);

            Character owner = playerShopOwnerMock();
            PlayerShop shop = new PlayerShop(owner, "bot stall", 5030000);
            // setOpen(false)：摊位未开张
            PlayerShopItem pItem = new PlayerShopItem(new Item(ITEM_ID_POTION, (short) 1, (short) 100), (short) 2, PRICE);
            shop.addItem(pItem);
            when(map.getMapObjectsInRange(any(), anyDouble(), any())).thenReturn(List.of(shop));

            FMBot bot = runFsm(chr, 9);

            assertEquals(2, pItem.getBundles(), "非 open 摊位商品必须原封不动");
            assertTrue(pItem.isExist());
            assertTrue(shop.getSold().isEmpty(), "非 open 摊位不得产生成交");
            verify(chr, never()).gainMeso(anyInt(), anyBoolean()); // 不扣款
            verify(chr).dropMessage(eq(1), eq("This store is not yet open."));
            assertEquals("NAV_TO_FM_ROOM", readFmBotState(bot), "逛完应退出房间进入下一个导航周期");
        }
    }

    @Test
    void soldOutPlayerShopClosesAndRejectsFurtherVisitsAndBuys() {
        try (MockedStatic<InventoryManipulator> inv = Mockito.mockStatic(InventoryManipulator.class);
             MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class)) {
            stubBuyerInventoryOk(inv);

            Character owner = playerShopOwnerMock();
            PlayerShop shop = new PlayerShop(owner, "bot stall", 5030000);
            shop.setOpen(true);
            PlayerShopItem pItem = new PlayerShopItem(new Item(ITEM_ID_POTION, (short) 1, (short) 100), (short) 1, PRICE);
            shop.addItem(pItem);

            Character buyer = buyerMock("BuyerX");
            Client buyerClient = buyer.getClient();
            assertTrue(shop.visitShop(buyer));

            new PlayerShopAdapter(shop).botBuyItemPlayerShop(buyer, pItem, 0, (short) 1);

            assertFalse(pItem.isExist());
            assertFalse(shop.isOpen(), "售罄后摊位自动关闭");
            assertTrue(allNull(shop.getVisitors()), "关店应清空访客");

            assertFalse(shop.visitShop(buyer), "售罄关闭的摊位应拒绝再次进店");
            verify(buyer).dropMessage(eq(1), eq("This store is not yet open."));
            assertFalse(shop.buy(buyerClient, 0, (short) 1), "售罄后不得再成交");
            verify(buyer, times(1)).gainMeso(-PRICE, false); // 全程只结算一次
            verify(owner, atLeastOnce()).setPlayerShop(null);
        }
    }

    @Test
    void closedShopsRejectVisitors() {
        try (MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class)) {
            // PlayerShop：默认 open=false
            Character owner = playerShopOwnerMock();
            PlayerShop shop = new PlayerShop(owner, "bot stall", 5030000);
            Character buyer = buyerMock("BuyerX");

            assertFalse(shop.visitShop(buyer), "非 open 的 PlayerShop 应拒绝进店");
            assertFalse(shop.isVisitor(buyer));
            assertTrue(allNull(shop.getVisitors()));
            verify(buyer).dropMessage(eq(1), eq("This store is not yet open."));

            // HiredMerchant：默认 open=false
            HiredMerchant merchant = new HiredMerchant(merchantOwnerMock(), "bot stall", 5030000);
            Character visitor = buyerMock("BuyerY");
            merchant.visitShop(visitor);

            assertTrue(allNull(merchant.getVisitorCharacters()), "非 open 的 HiredMerchant 不得登记访客");
            verify(visitor, never()).setHiredMerchant(any());
        }
    }

    // ─────────────────────────── helpers ───────────────────────────

    private static void stubBuyerInventoryOk(MockedStatic<InventoryManipulator> inv) {
        inv.when(() -> InventoryManipulator.checkSpace(any(), anyInt(), anyInt(), any())).thenReturn(true);
        inv.when(() -> InventoryManipulator.addFromDrop(any(), any(), anyBoolean())).thenReturn(true);
    }

    /** HiredMerchant.buy 卖家离线分支：世界内查无此人 → 走 DB 落库（测试环境直接让连接失败被吞）。 */
    private static void stubOfflineSellerPersistence(MockedStatic<Server> srv, MockedStatic<DatabaseConnection> db, String ownerName) {
        Server server = mock(Server.class);
        World world = mock(World.class);
        PlayerStorage storage = mock(PlayerStorage.class);
        srv.when(Server::getInstance).thenReturn(server);
        when(server.getWorld(0)).thenReturn(world);
        when(world.getPlayerStorage()).thenReturn(storage);
        when(storage.getCharacterByName(ownerName)).thenReturn(null);
        db.when(DatabaseConnection::getConnection).thenThrow(new SQLException("unit test: no database"));
    }

    private static void stubFsmStatics(MockedStatic<LodCounts> lod, MockedStatic<BotLogic> logic,
                                       MockedStatic<GCMovement> gc, MockedStatic<BotHelpers> helpers,
                                       MockedStatic<BotEconomy> econ, MockedStatic<BotDialogueHandler> dialogue) {
        lod.when(LodCounts::trackerRunning).thenReturn(false);
        logic.when(() -> BotLogic.isInsideFMRooms(any())).thenReturn(true);
        gc.when(() -> GCMovement.isMoving(any())).thenReturn(false);
        helpers.when(() -> BotHelpers.convertItemIdToName(ITEM_ID_POTION)).thenReturn("Red Potion");
        econ.when(() -> BotEconomy.getItemMarketValue(any(Item.class))).thenReturn(MARKET_VALUE);
        dialogue.when(() -> BotDialogueHandler.getRandomResolvedLine(any(), anyString())).thenReturn(null);
    }

    /** FSM 9 tick：RESET→NAV→PROCESS_ROOM→BROWSING→ACTION→ENTER_SHOP→PROCESS_SHOP→EXIT_SHOP→EXIT_FM_ROOM→NAV。 */
    private FMBot runFsm(Character chr, int ticks) {
        FMBot bot = new FMBot(chr);
        BotStorage.addActiveBot(botId, bot);
        bot.setRunning(true);
        for (int i = 0; i < ticks; i++) {
            bot.updateState();
        }
        return bot;
    }

    private Character fsmBotCharacter(MapleMap map) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(botId);
        when(chr.getName()).thenReturn("BotX" + botId);
        when(chr.getWorld()).thenReturn(0);
        Client client = mock(Client.class);
        when(client.getChannel()).thenReturn(1);
        when(client.getPlayer()).thenReturn(chr);
        when(chr.getClient()).thenReturn(client);
        when(chr.getMap()).thenReturn(map);
        when(chr.getMapId()).thenReturn(FM_ROOM_1);
        when(chr.getPosition()).thenReturn(new Point(0, 0));
        when(chr.getMeso()).thenReturn(1_000_000);
        return chr;
    }

    private static MapleMap fsmMap() {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(FM_ROOM_1);
        when(map.getCharacters()).thenReturn(List.of());
        return map;
    }

    private static Character merchantOwnerMock() {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(100);
        when(owner.getName()).thenReturn("OwnerShop");
        when(owner.getWorld()).thenReturn(0);
        when(owner.getPosition()).thenReturn(new Point(5, 0));
        Client client = mock(Client.class);
        when(client.getChannel()).thenReturn(1);
        when(owner.getClient()).thenReturn(client);
        MapleMap map = fsmMap();
        when(owner.getMap()).thenReturn(map);
        return owner;
    }

    private static Character playerShopOwnerMock() {
        Character owner = mock(Character.class);
        when(owner.getName()).thenReturn("OwnerShop");
        when(owner.getMapId()).thenReturn(FM_ROOM_1);
        when(owner.getPosition()).thenReturn(new Point(5, 0));
        when(owner.getClient()).thenReturn(mock(Client.class));
        MapleMap map = fsmMap();
        when(owner.getMap()).thenReturn(map);
        when(owner.canHoldMeso(anyInt())).thenReturn(true);
        return owner;
    }

    private static Character buyerMock(String name) {
        Character buyer = mock(Character.class);
        when(buyer.getId()).thenReturn(5);
        when(buyer.getName()).thenReturn(name);
        when(buyer.getMeso()).thenReturn(1_000_000);
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(buyer);
        when(buyer.getClient()).thenReturn(client);
        return buyer;
    }

    private static void invokeProcessRoom(FMBot bot) {
        try {
            Method m = FMBot.class.getDeclaredMethod("processRoom");
            m.setAccessible(true);
            m.invoke(bot);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to invoke FMBot.processRoom", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ShopKeeper> readVisitQueue(FMBot bot) {
        try {
            Field f = FMBot.class.getDeclaredField("visitQueue");
            f.setAccessible(true);
            return (List<ShopKeeper>) f.get(bot);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to read FMBot.visitQueue", e);
        }
    }

    private static String readFmBotState(FMBot bot) {
        try {
            Field f = FMBot.class.getDeclaredField("fmBotState");
            f.setAccessible(true);
            return String.valueOf(f.get(bot));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to read FMBot.fmBotState", e);
        }
    }

    private static boolean allNull(Object[] visitors) {
        for (Object visitor : visitors) {
            if (visitor != null) {
                return false;
            }
        }
        return true;
    }
}
