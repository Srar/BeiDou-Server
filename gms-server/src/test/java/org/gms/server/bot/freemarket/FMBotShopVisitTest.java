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
 *       在真实 HiredMerchant/PlayerShop 上完成购买（botBuy 虚拟买家结算）：商品 bundles
 *       减少/售罄移除、卖家入账、成交记录；headless 买家免检 meso/库存。</li>
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
 * ItemInformationProvider 单例，类加载即炸）；HiredMerchant botBuy 的卖家结算按
 * 在线（Server→World→PlayerStorage 返回卖家）与离线（DatabaseConnection 落库）两种
 * 场景分别 stub。
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
        try (MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class);
             MockedStatic<Server> srv = Mockito.mockStatic(Server.class)) {
            // 卖家（bot 摊主）在线：结算走 addMerchantMesos 分支
            Character owner = merchantOwnerMock();
            PlayerStorage storage = mock(PlayerStorage.class);
            when(storage.getCharacterByName("OwnerShop")).thenReturn(owner);
            World world = mock(World.class);
            when(world.getPlayerStorage()).thenReturn(storage);
            Server server = mock(Server.class);
            when(server.getWorld(0)).thenReturn(world);
            srv.when(Server::getInstance).thenReturn(server);

            HiredMerchant merchant = new HiredMerchant(owner, "bot stall", 5030000);
            merchant.setOpen(true);
            PlayerShopItem pItem = new PlayerShopItem(new Item(ITEM_ID_POTION, (short) 1, (short) 100), (short) 2, PRICE);
            merchant.addItem(pItem);

            Character buyer = headlessBuyerMock("BuyerX");
            new HiredMerchantAdapter(merchant).botBuyItem(buyer, pItem, (short) 1);

            assertEquals(1, pItem.getBundles(), "购买 1 组后 bundles 应从 2 减到 1");
            assertTrue(pItem.isExist(), "仍有库存的商品不应被移除");
            assertEquals(1, merchant.getSold().size(), "成交记录应有 1 条");
            assertEquals("BuyerX", merchant.getSold().get(0).getBuyer());
            assertEquals(ITEM_ID_POTION, merchant.getSold().get(0).getItemId());
            assertEquals(PRICE, merchant.getSold().get(0).getMesos());
            verify(owner).addMerchantMesos(PRICE); // 卖家入账（headless 买家不扣款）
        }
    }

    @Test
    void hiredMerchantAdapterBuyOutLastBundleRemovesItem() {
        try (MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class);
             MockedStatic<Server> srv = Mockito.mockStatic(Server.class);
             MockedStatic<DatabaseConnection> db = Mockito.mockStatic(DatabaseConnection.class)) {
            stubOfflineSellerPersistence(srv, db, "OwnerShop");

            HiredMerchant merchant = new HiredMerchant(merchantOwnerMock(), "bot stall", 5030000);
            merchant.setOpen(true);
            PlayerShopItem pItem = new PlayerShopItem(new Item(ITEM_ID_POTION, (short) 1, (short) 100), (short) 1, PRICE);
            merchant.addItem(pItem);

            Character buyer = headlessBuyerMock("BuyerX");
            new HiredMerchantAdapter(merchant).botBuyItem(buyer, pItem, (short) 1);

            assertEquals(0, pItem.getBundles());
            assertFalse(pItem.isExist(), "最后一组售出后商品应标记不存在");
            assertEquals(1, merchant.getSold().size());
        }
    }

    @Test
    void hiredMerchantAdapterBotBuyDelegatesToMerchant() {
        try (MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class)) {
            PlayerShopItem pItem = new PlayerShopItem(new Item(ITEM_ID_POTION, (short) 1, (short) 100), (short) 1, PRICE);
            HiredMerchant merchant = mock(HiredMerchant.class);

            Character buyer = headlessBuyerMock("BuyerX");
            new HiredMerchantAdapter(merchant).botBuyItem(buyer, pItem, (short) 1);

            // bot 购买走 botBuy(Character, ...) 虚拟买家结算（对齐 SoloMapling，headless client 无 player）
            verify(merchant).botBuy(buyer, pItem, (short) 1);
        }
    }

    @Test
    void playerShopAdapterBuySettlesMesosAndReducesBundles() {
        try (MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class)) {
            Character owner = playerShopOwnerMock();
            PlayerShop shop = new PlayerShop(owner, "bot stall", 5030000);
            shop.setOpen(true);
            PlayerShopItem pItem = new PlayerShopItem(new Item(ITEM_ID_POTION, (short) 1, (short) 100), (short) 2, PRICE);
            shop.addItem(pItem);

            Character buyer = headlessBuyerMock("BuyerX");
            assertTrue(shop.visitShop(buyer), "open 摊位应允许 bot 进店");
            assertTrue(shop.isVisitor(buyer), "进店后买家应登记为 visitor");

            new PlayerShopAdapter(shop).botBuyItemPlayerShop(buyer, pItem, 0, (short) 1);

            assertEquals(1, pItem.getBundles(), "购买 1 组后 bundles 应从 2 减到 1");
            assertEquals(1, shop.getSold().size());
            verify(buyer, never()).gainMeso(anyInt(), anyBoolean()); // 虚拟买家不扣款
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
             MockedStatic<Server> srv = Mockito.mockStatic(Server.class);
             MockedStatic<DatabaseConnection> db = Mockito.mockStatic(DatabaseConnection.class);
             MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class)) {
            stubFsmStatics(lod, logic, gc, helpers, econ, dialogue);
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
            verify(chr, never()).gainMeso(anyInt(), anyBoolean()); // 虚拟买家不扣款
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
             MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class)) {
            stubFsmStatics(lod, logic, gc, helpers, econ, dialogue);

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
            verify(chr, never()).gainMeso(anyInt(), anyBoolean()); // 虚拟买家不扣款
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
             MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class)) {
            stubFsmStatics(lod, logic, gc, helpers, econ, dialogue);

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
        try (MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class)) {
            Character owner = playerShopOwnerMock();
            PlayerShop shop = new PlayerShop(owner, "bot stall", 5030000);
            shop.setOpen(true);
            PlayerShopItem pItem = new PlayerShopItem(new Item(ITEM_ID_POTION, (short) 1, (short) 100), (short) 1, PRICE);
            shop.addItem(pItem);

            Character buyer = headlessBuyerMock("BuyerX");
            assertTrue(shop.visitShop(buyer));

            new PlayerShopAdapter(shop).botBuyItemPlayerShop(buyer, pItem, 0, (short) 1);

            assertFalse(pItem.isExist());
            assertFalse(shop.isOpen(), "售罄后摊位自动关闭");
            assertTrue(allNull(shop.getVisitors()), "关店应清空访客");

            assertFalse(shop.visitShop(buyer), "售罄关闭的摊位应拒绝再次进店");
            verify(buyer).dropMessage(eq(1), eq("This store is not yet open."));
            assertFalse(shop.botBuy(buyer, pItem, 0, (short) 1), "售罄关店后 botBuy 的 open 门控应拒绝成交");
            verify(buyer, never()).gainMeso(anyInt(), anyBoolean()); // 虚拟买家全程不扣款
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

    // ─────────────────────── 环节 5：NPE 回归（headless client / bot 无 CashShop） ───────────────────────

    @Test
    void playerShopAdapterChatWithHeadlessClientDoesNotThrow() {
        try (MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class)) {
            Character owner = playerShopOwnerMock();
            PlayerShop shop = new PlayerShop(owner, "bot stall", 5030000);
            shop.setOpen(true);

            // bot 共用 headless client：从未绑定 player（BotClientHolder 单例语义，getPlayer() 恒为 null）
            Client headless = mock(Client.class);
            when(headless.getPlayer()).thenReturn(null);
            Character bot = mock(Character.class);
            when(bot.getId()).thenReturn(5);
            when(bot.getName()).thenReturn("BuyerX");
            when(bot.getClient()).thenReturn(headless);

            assertTrue(shop.visitShop(bot), "open 摊位应允许 bot 进店");
            new PlayerShopAdapter(shop).chat(bot, "买了买了");

            // 聊天应记入 chatLog（重载路径不依赖 client.player；修复前此处 getVisitorSlot 直接 NPE）
            assertEquals(1, readChatLogSize(shop), "bot 聊天应记入摊位聊天记录");
        }
    }

    @Test
    void playerShopChatClientOverloadDefendsNullPlayer() {
        PlayerShop shop = new PlayerShop(playerShopOwnerMock(), "bot stall", 5030000);
        Client headless = mock(Client.class);
        when(headless.getPlayer()).thenReturn(null);

        shop.chat(headless, "不应炸"); // 防御：headless client（修复前在 chatSlot.put(c.getPlayer().getId()) 处 NPE）
        shop.chat((Client) null, "也不应炸"); // 防御：c == null 分支

        assertEquals(0, readChatLogSize(shop), "null player / null client 的聊天应被入口防御拦截");
    }

    @Test
    void charInfoWithBotLikeCharacterDoesNotThrow() {
        // bot 由 Character.getDefault 构造：cashShop 与 monsterBook 均未初始化（null），
        // charInfo 修复后应判空写 0 正常产出封包（修复前 getCashShop() 处必炸）
        Character bot = Character.getDefault(mock(Client.class));
        byte[] data = PacketCreator.charInfo(bot).getBytes();

        // 封包布局：2B opcode + 4B id + 1B level + 2B job + 2B fame + 1B marriageRing
        //           + 2B guildName(空) + 2B allianceName(空) + 1B pMedalInfo
        //           + 1B pets 结束标记 + 1B mount(0) = 愿望单计数位于 data[19]
        assertTrue(data.length > 20, "封包应含完整固定头部与后续段");
        assertEquals(0, data[19], "null cashShop 的愿望单计数应写 0");
    }

    @Test
    void realPlayerBuysFromHiredMerchantArtificialWithoutNpe() {
        try (MockedStatic<InventoryManipulator> inv = Mockito.mockStatic(InventoryManipulator.class);
             MockedStatic<PacketCreator> pc = Mockito.mockStatic(PacketCreator.class);
             MockedStatic<Server> srv = Mockito.mockStatic(Server.class);
             MockedStatic<DatabaseConnection> db = Mockito.mockStatic(DatabaseConnection.class)) {
            stubBuyerInventoryOk(inv);

            // 在线卖家（bot 摊主名）注册进真实 PlayerStorage：只有父类 ownerName 正确写入时
            // buy 才会命中在线分支（修复前 ownerName 为 null，getCharacterByName(null) NPE）
            Character onlineOwner = mock(Character.class);
            when(onlineOwner.getName()).thenReturn("ShopOwnerBot");
            PlayerStorage storage = new PlayerStorage();
            storage.addPlayer(onlineOwner);
            World world = mock(World.class);
            when(world.getPlayerStorage()).thenReturn(storage);
            Server server = mock(Server.class);
            when(server.getWorld(0)).thenReturn(world);
            srv.when(Server::getInstance).thenReturn(server);
            db.when(DatabaseConnection::getConnection).thenThrow(new SQLException("unit test: no database"));

            // bot 摊位创建方式（对齐 ArtificialFreeMarket.spawnHiredMerchantStore 修复后：
            // HiredMerchant 构造前必须 setId + setName，否则父类 ownerName 为 null）
            Client botClient = mock(Client.class);
            when(botClient.getChannel()).thenReturn(1);
            Character newchar = Character.getDefault(botClient);
            newchar.setWorld(0);
            newchar.setId(9_800_001); // 独立区段：与 NEXT_BOT_ID（9_700_000 起）隔离，仅作人工摊位 owner 合成 id
            newchar.setName("ShopOwnerBot");
            newchar.setPosition(new Point(5, 0));

            HiredMerchantArtificial merchant = new HiredMerchantArtificial(newchar, "bot stall", 5030000, 9_800_001, "ShopOwnerBot");
            merchant.setOpen(true);
            PlayerShopItem pItem = new PlayerShopItem(new Item(ITEM_ID_POTION, (short) 1, (short) 100), (short) 1, PRICE);
            merchant.addItem(pItem);

            // 真实玩家购买：buy(Client) → 卖家结算 getCharacterByName(ownerName)
            Character player = buyerMock("PlayerX");
            merchant.buy(player.getClient(), 0, (short) 1);

            assertEquals(0, pItem.getBundles(), "真实玩家应买走最后 1 组");
            assertFalse(pItem.isExist());
            assertEquals(1, merchant.getSold().size());
            assertEquals("PlayerX", merchant.getSold().get(0).getBuyer());
            verify(onlineOwner).addMerchantMesos(PRICE); // 命中在线卖家结算分支（ownerName 正确）
        }
    }

    @Test
    void playerStorageGetCharacterByNameDefendsNullName() {
        assertEquals(null, new PlayerStorage().getCharacterByName(null), "null 名字查找应安全返回 null");
    }

    // ─────────────────────────── helpers ───────────────────────────

    private static void stubBuyerInventoryOk(MockedStatic<InventoryManipulator> inv) {
        inv.when(() -> InventoryManipulator.checkSpace(any(), anyInt(), anyInt(), any())).thenReturn(true);
        inv.when(() -> InventoryManipulator.addFromDrop(any(), any(), anyBoolean())).thenReturn(true);
    }

    /** HiredMerchant botBuy 卖家离线分支：世界内查无此人 → 走 DB 落库（测试环境直接让连接失败被吞）。 */
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
        // 生产语义：bot 共用 headless client（BotClientHolder 单例），getPlayer() 恒为 null
        Client client = mock(Client.class);
        when(client.getChannel()).thenReturn(1);
        when(client.getPlayer()).thenReturn(null);
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

    /** 生产语义的 bot 买家：headless client（getPlayer() 恒为 null），购买走 botBuy 虚拟买家结算。 */
    private static Character headlessBuyerMock(String name) {
        Character buyer = mock(Character.class);
        when(buyer.getId()).thenReturn(5);
        when(buyer.getName()).thenReturn(name);
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(null);
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

    private static int readChatLogSize(PlayerShop shop) {
        try {
            Field f = PlayerShop.class.getDeclaredField("chatLog");
            f.setAccessible(true);
            return ((List<?>) f.get(shop)).size();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to read PlayerShop.chatLog", e);
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
