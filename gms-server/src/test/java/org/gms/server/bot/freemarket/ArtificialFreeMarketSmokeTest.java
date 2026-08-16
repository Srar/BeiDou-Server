package org.gms.server.bot.freemarket;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.manager.ServerManager;
import org.gms.net.packet.Packet;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.ItemInformationProvider;
import org.gms.server.TimerManager;
import org.gms.server.bot.BotClientHolder;
import org.gms.server.bot.BotExecutors;
import org.gms.server.bot.BotStorage;
import org.gms.server.bot.BotTickService;
import org.gms.server.bot.event.BotEventBus;
import org.gms.server.bot.itempool.EquipMetadataCache;
import org.gms.server.maps.HiredMerchant;
import org.gms.server.maps.MapManager;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.PlayerShop;
import org.gms.server.maps.PlayerShopItem;
import org.gms.service.NpcService;
import org.gms.test.BotTestSupport;
import org.gms.util.PacketCreator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.awt.Point;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * 摆摊生成管线（ArtificialFreeMarket）离线端到端冒烟测试。
 *
 * <p><b>运行前提（缺一不可）：</b>
 * <ol>
 *   <li>测试 CWD = gms-server 模块根：{@code MapFactory}/{@code ItemInformationProvider} 按相对路径
 *       {@code wz/}（及 {@code wz-zh-CN/} 语言目录，缺失文件回落基目录）读取 XML 格式 WZ 数据，
 *       本类依赖真实的 {@code wz/Map.wz/Map/Map9/910000001.img.xml} 等数据文件；</li>
 *   <li>离线无 DB：{@code ItemInformationProvider} 构造器里的 {@code loadCardIdData} 与
 *       {@code MapFactory.loadLifeFromDb} 直连 {@code DataSource}，本类把 DataSource bean 桩为
 *       抛 {@link SQLException}（原实现 catch 后跳过），若桩缺失会因 mock 返回 null 连接而
 *       ExceptionInInitializerError；</li>
 *   <li>{@code PlayerNPC} 静态字段在类加载时取 {@code NpcService} bean，本类桩为返回空列表，
 *       避免加载真实地图时 {@code addPlayerNPCMapObject} 对 null 结果 NPE。</li>
 * </ol>
 *
 * <p><b>验证链路（分层，自底向上）：</b>
 * <ol>
 *   <li>{@code HiredMerchantArtificial} 对象构造：owner/描述/店铺类型可读写；</li>
 *   <li>{@code BotShopCatalog} 商品生成：返回非空且 itemId 全部经 ItemInformationProvider
 *       （WZ 名称表 + 整价表）校验存在；</li>
 *   <li>{@code BotPlayerStorePermit}（populate 的 bot-shop 分支摊位对象创建，同步可测）：
 *       真实 910000001 地图上产出 open 的 {@link PlayerShop}，商品数 &gt; 0、描述非空；</li>
 *   <li>{@code populateFreeMarketRoom(910000001)}（异步全链路，hired-merchant 分支）：
 *       轮询真实地图对象表，断言 HIRED_MERCHANT 摊位落图且 open、有商品、owner/描述非空；</li>
 *   <li>广播包可构造：对产出的摊位调 {@code PacketCreator.spawnHiredMerchantBox} /
 *       {@code updatePlayerShopBox}，断言不抛异常且字节非空。</li>
 * </ol>
 *
 * <p><b>桩的最小化说明：</b>mock 仅覆盖注册接缝——{@code Server} 单例（反射替换 + mockStatic 双保险，
 * 使 {@code BotExecutors} 虚拟线程内的 {@code Server.getInstance()} 也拿到 mock）、{@code World} /
 * {@code Channel}（registerHiredMerchant / addHiredMerchant / getMapFactory 等 no-op 或真实
 * {@link MapManager} 供图）。地图、WZ、物品池、PacketCreator 全部真实。
 */
class ArtificialFreeMarketSmokeTest {

    private static final int WORLD = 0;
    private static final int CHANNEL = 1;
    private static final int FM_ROOM = 910000001; // henesys FM 房间 1（热房：90% hired merchant 概率）
    private static final long FIXED_SERVER_TIME = 1_000_000L;
    private static final long POPULATE_WAIT_MS = 20_000;

    private MapleMap fmMap;
    private World worldMock;
    private Channel channelMock;
    private PlayerStorage storage;
    private MockedStatic<Server> serverStatic;
    private MockedStatic<TimerManager> timerStatic;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();

        // 离线桩 1：DataSource.getConnection 抛 SQLException → 生产代码 catch(SQLException) 跳过 DB 段。
        // 若不桩：ctx 通用回答返回的 DataSource mock 其 getConnection() 为 null，
        // ItemInformationProvider 构造器 try-with-resources 里 con.prepareStatement 会 NPE（非 SQLException），
        // 直接炸掉静态单例（ExceptionInInitializerError）。
        ApplicationContext ctx = ServerManager.getApplicationContext();
        DataSource dataSource = Mockito.mock(DataSource.class);
        try {
            Mockito.when(dataSource.getConnection()).thenThrow(new SQLException("offline test: no DB"));
        } catch (SQLException e) {
            throw new AssertionError("stubbing DataSource must not fail", e);
        }
        Mockito.when(ctx.getBean(DataSource.class)).thenReturn(dataSource);

        // 离线桩 2：PlayerNPC 静态字段在类加载时取 NpcService；加载真实地图时
        // addPlayerNPCMapObject 调 getPlayerNPC——桩为空列表，跳过玩家 NPC 落图。
        NpcService npcService = Mockito.mock(NpcService.class);
        Mockito.when(npcService.getPlayerNPC(Mockito.any())).thenReturn(Collections.emptyList());
        Mockito.when(ctx.getBean(NpcService.class)).thenReturn(npcService);

        // 预热：真实加载一次 FM 房间（同时验证前提成立；LifeFactory 静态 WZ 解析只在首次发生）。
        try (MockedStatic<Server> warmupStatic = Mockito.mockStatic(Server.class)) {
            Server warmupServer = Mockito.mock(Server.class);
            Mockito.when(warmupServer.getCurrentTime()).thenReturn(FIXED_SERVER_TIME);
            warmupStatic.when(Server::getInstance).thenReturn(warmupServer);
            MapleMap warmup = new MapManager(null, WORLD, CHANNEL).getMap(FM_ROOM);
            assertNotNull(warmup, "warmup: FM room 910000001 must load from real wz");
        }

        // 预热装备存在性索引（EquipMetadataCache 懒加载）：供 BotShopCatalog 装备条目做 O(1) 存在校验。
        // 全量扫描 String.wz 物品目录 + 逐件读 Item.wz，单次数秒~数十秒，仅本类内一次。
        assertTrue(EquipMetadataCache.get().equipExists(1040000),
                "equip metadata cache must index wz-existing equips (e.g. 1040000)");
    }

    @BeforeEach
    void setUp() {
        // ── Server/World/Channel 注册桩 ─────────────────────────────────────
        // 反射替换真实单例：BotExecutors 虚拟线程（populate 的异步编排）里 Server.getInstance()
        // 落回真实单例，mockStatic 线程局部对它无效；反射保证所有线程都拿到同一个 mock。
        Server serverMock = Mockito.mock(Server.class);
        worldMock = Mockito.mock(World.class);
        channelMock = Mockito.mock(Channel.class);
        storage = new PlayerStorage();
        Mockito.lenient().when(serverMock.getCurrentTime()).thenReturn(FIXED_SERVER_TIME);
        Mockito.lenient().when(serverMock.getWorld(anyInt())).thenReturn(worldMock);
        Mockito.lenient().when(serverMock.getChannel(anyInt(), anyInt())).thenReturn(channelMock);
        // createMerchantObject：newchar.getWorldServer().registerHiredMerchant + getChannel(1).addHiredMerchant
        Mockito.lenient().when(worldMock.getChannel(anyInt())).thenReturn(channelMock);
        Mockito.lenient().when(worldMock.getPlayerStorage()).thenReturn(storage);
        // spawnHiredMerchantStore：Server.getInstance().getChannel(w,c).getMapFactory().getMap(mapId)
        // → 真实 MapManager 供图（910000001 走真实 wz，而非 mock 空地图）。
        MapManager mapManager = new MapManager(null, WORLD, CHANNEL);
        Mockito.lenient().when(channelMock.getMapFactory()).thenReturn(mapManager);
        fmMap = mapManager.getMap(FM_ROOM);
        assertNotNull(fmMap, "FM room 910000001 must load from real wz");
        setServerInstance(serverMock);
        serverStatic = Mockito.mockStatic(Server.class);
        serverStatic.when(Server::getInstance).thenReturn(serverMock);

        // ── TimerManager：虚拟线程用真实调度器；测试线程 mock 掉（吞掉 populate 里
        //    bot-shop 分支的 schedule，使其不被真实调度，测试只验证 hired-merchant 异步分支）。──
        TimerManager.getInstance().start();
        timerStatic = Mockito.mockStatic(TimerManager.class);
        TimerManager timerManagerMock = Mockito.mock(TimerManager.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ScheduledFuture future = Mockito.mock(ScheduledFuture.class);
        Mockito.lenient().when(timerManagerMock.register(any(), anyLong(), anyLong())).thenReturn(future);
        Mockito.lenient().when(timerManagerMock.schedule(any(Runnable.class), anyLong())).thenReturn(future);
        timerStatic.when(TimerManager::getInstance).thenReturn(timerManagerMock);

        // 摆摊管线生产配置：bot-shop 分支的 schedule 在本测试线程被 mock 吞掉（见上），
        // 不会真正走到 BotGeneration.createBot，无需装饰接缝桩。
    }

    @AfterEach
    void tearDown() {
        timerStatic.close();
        serverStatic.close();
        setServerInstance(null);

        // 恢复共享单例，避免跨用例污染（setDecorator 为 bot 包包私有，本类不在 bot 包，
        // 而 bot-shop 分支在本类中不会被真正调度，无需触碰装饰接缝）。
        BotClientHolder.reset();
        BotExecutors.resetForShutdown();
        for (int id : List.copyOf(BotStorage.getAllBots().keySet())) {
            BotStorage.removeActiveBot(id);
            BotTickService.unregister(id);
        }
        BotEventBus.getInstance().reset();
    }

    // ── 链路 1：HiredMerchantArtificial 对象构造 ────────────────────────────

    @Test
    void hiredMerchantArtificialExposesOwnerAndShopTypes() {
        Character owner = Character.getDefault(Client.createMock());
        owner.setName("HmaOwner");
        owner.setPosition(new Point(100, 100));

        HiredMerchantArtificial merchant =
                new HiredMerchantArtificial(owner, "smoke desc", 5030000, 42_001, "HmaOwner");

        assertEquals(42_001, merchant.getOwnerId(), "ownerId must be readable via override");
        assertEquals("HmaOwner", merchant.getOwner(), "owner name must be readable via override");
        assertEquals("smoke desc", merchant.getDescription(), "description must round-trip");
        assertEquals(5030000, merchant.getItemId(), "shop item id must round-trip");
        assertEquals(new Point(100, 100), merchant.getPos(), "position must come from owner");

        merchant.setTier("S");
        merchant.setMapId(FM_ROOM);
        merchant.setPrimary(HiredMerchantArtificial.shopTypes.Scroll);
        merchant.setSecondary(HiredMerchantArtificial.shopTypes.Potion);

        assertEquals("S", merchant.getTier());
        assertEquals(FM_ROOM, merchant.getMapIdArtificial());
        assertEquals(1, merchant.getRoomNumber(), "910000001 % 100 == 1");
        assertEquals(HiredMerchantArtificial.shopTypes.Scroll, merchant.getPrimary());
        assertEquals(HiredMerchantArtificial.shopTypes.Potion, merchant.getSecondary());
    }

    // ── 链路 2：BotShopCatalog 商品生成 + WZ 存在性校验 ──────────────────────

    @Test
    void botShopCatalogProducesWzExistingItems() {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        List<FMItem> scrolls = BotShopCatalog.generateScrollsList("S");
        List<FMItem> darkScrolls = BotShopCatalog.generateDarkScrollsList("A");
        List<FMItem> stars = BotShopCatalog.generateThiefStarsList("S");
        List<FMItem> potions = BotShopCatalog.generatePotionsList("B");

        assertEquals(4, scrolls.size(), "scrolls list must hold 4 items");
        assertEquals(4, darkScrolls.size(), "dark scrolls list must hold 4 items");
        assertEquals(2, stars.size(), "thief stars list must hold 2 items");
        assertEquals(3, potions.size(), "potions list must hold 3 items");

        for (FMItem item : java.util.stream.Stream.concat(
                java.util.stream.Stream.concat(scrolls.stream(), darkScrolls.stream()),
                java.util.stream.Stream.concat(stars.stream(), potions.stream())).toList()) {
            int itemId = item.getItemId();
            assertTrue(itemId > 0, "generated itemId must be positive");
            assertTrue(item.getPrice() > 0, "generated price must be positive for " + itemId);
            // wz 存在性：String.wz 名称表可解析 + Item.wz 整价表返回正价。
            assertNotNull(ii.getName(itemId), "item " + itemId + " must exist in String.wz");
            assertTrue(ii.getWholePrice(itemId) > 0, "item " + itemId + " must have a positive wz whole price");
        }

        // 装备条目：经 EquipMetadataCache（真实 wz 索引，@BeforeAll 已预热）O(1) 存在校验。
        List<FMEquip> commonEquips = BotShopCatalog.generateCommonEquipList("B");
        assertFalse(commonEquips.isEmpty(), "common equip list must be non-empty");
        for (FMEquip equip : commonEquips) {
            assertTrue(EquipMetadataCache.equipExists(equip.getEquip().getItemId()),
                    "equip " + equip.getEquip().getItemId() + " must exist in wz equip index");
        }
    }

    // ── 链路 3：BotPlayerStorePermit——populate bot-shop 分支的摊位对象创建（同步） ──

    @Test
    void botPlayerStorePermitCreatesVisibleShopOnRealFMRoom() {
        Character fakechar = shopOwnerCharacter("SmokeBotShop", new Point(-129, -416));

        ArtificialFreeMarket.BotPlayerStorePermit(fakechar);

        PlayerShop shop = fakechar.getPlayerShop();
        assertNotNull(shop, "BotPlayerStorePermit must attach a PlayerShop to the bot");
        assertTrue(shop.isOpen(), "shop must be open right after permit is planted");
        assertNotNull(shop.getDescription(), "shop description must not be null");
        assertFalse(shop.getDescription().isBlank(), "shop description must be non-blank");
        assertFalse(shop.getItems().isEmpty(), "shop must contain generated items");
        assertTrue(shop.getItems().size() <= 16, "shop items must fit the 16-slot cap");
        assertEquals(fakechar, shop.getOwner(), "shop owner must be the fake bot character");

        // 地图对象可见性：SHOP 类型对象已登记进真实 FM 房间的对象表。
        List<MapObject> shops = fmMap.getMapObjects().stream()
                .filter(o -> o.getType() == MapObjectType.SHOP)
                .toList();
        assertFalse(shops.isEmpty(), "FM room object table must contain the planted SHOP");
        assertTrue(shops.contains(shop), "planted shop instance must be among visible map objects");
    }

    // ── 链路 4：populateFreeMarketRoom——异步全链路（hired-merchant 分支） ──

    @Test
    void populateFreeMarketRoomSpawnsVisibleMerchantsOnRealFMRoom() {
        ArtificialFreeMarket.populateFreeMarketRoom(FM_ROOM);

        // 910000001 是热房：90% 摊位走 hired merchant（runAsync 立即执行）。
        // 轮询真实地图对象表，直到 HIRED_MERCHANT 出现或超时。
        List<MapObject> merchants = awaitObjects(MapObjectType.HIRED_MERCHANT, POPULATE_WAIT_MS);
        assertFalse(merchants.isEmpty(),
                "populateFreeMarketRoom must land HIRED_MERCHANT objects on the real FM room map");

        HiredMerchant first = (HiredMerchant) merchants.get(0);
        assertTrue(first.isOpen(), "spawned merchant must be open for players");
        assertEquals(FM_ROOM, first.getMapId(), "merchant must be registered on the FM room map");
        assertNotNull(first.getOwner(), "merchant owner name must be present");
        assertFalse(first.getOwner().isBlank(), "merchant owner name must be non-blank");
        assertNotNull(first.getDescription(), "merchant description must be present");
        assertFalse(first.getDescription().isBlank(), "merchant description must be non-blank");
        assertFalse(first.getItems().isEmpty(), "merchant must carry generated shop items");
        // 生成管线会按概率把部分商品标记为已售出（buyoutSomeItems，isExist=false），
        // 这是生产预期行为；断言至少一件存活商品（全部售罄概率 ~9%^5，可忽略）。
        assertTrue(first.getItems().stream().anyMatch(PlayerShopItem::isExist),
                "merchant must have at least one live (isExist) item at spawn time");

        // 产出的摊位对象可直接构造出生广播包（玩家可见性的最后一跳）。
        Packet spawn = PacketCreator.spawnHiredMerchantBox(first);
        assertNotNull(spawn);
        assertTrue(spawn.getBytes().length > 0, "spawnHiredMerchantBox must produce non-empty bytes");
    }

    // ── 链路 5：广播包可构造 ─────────────────────────────────────────────────

    @Test
    void spawnAndUpdatePacketsBuildNonEmptyBytes() {
        // PlayerShop 摊位（链路 3 的轻量版）。
        Character owner = shopOwnerCharacter("SmokeBroadcast", new Point(166, -206));
        ArtificialFreeMarket.BotPlayerStorePermit(owner);
        PlayerShop shop = owner.getPlayerShop();
        Packet updateBox = PacketCreator.updatePlayerShopBox(shop);
        Packet removeShopBox = PacketCreator.removePlayerShopBox(shop);
        assertTrue(updateBox.getBytes().length > 0, "updatePlayerShopBox must produce non-empty bytes");
        assertTrue(removeShopBox.getBytes().length > 0, "removePlayerShopBox must produce non-empty bytes");

        // HiredMerchant 摊位（带一件商品，链路 1 的扩展）。
        Character hmaOwner = Character.getDefault(Client.createMock());
        hmaOwner.setName("SmokeMerchant");
        hmaOwner.setPosition(new Point(328, -416));
        HiredMerchantArtificial merchant =
                new HiredMerchantArtificial(hmaOwner, "packet smoke", 5030000, 42_002, "SmokeMerchant");
        merchant.addItem(new PlayerShopItem(new org.gms.client.inventory.Item(2000000, (short) 1, (short) 1), (short) 1, 50));
        Packet spawnBox = PacketCreator.spawnHiredMerchantBox(merchant);
        Packet removeBox = PacketCreator.removeHiredMerchantBox(merchant.getOwnerId());
        assertTrue(spawnBox.getBytes().length > 0, "spawnHiredMerchantBox must produce non-empty bytes");
        assertTrue(removeBox.getBytes().length > 0, "removeHiredMerchantBox must produce non-empty bytes");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** 构造 bot-shop 摊主角色：共享 bot client + 真实 FM 房间地图（测试线程，Server mock 生效）。 */
    private Character shopOwnerCharacter(String name, Point position) {
        Client client = BotClientHolder.getBotClient(WORLD, CHANNEL);
        Character chr = Character.getDefault(client);
        chr.setWorld(WORLD);
        chr.setName(name);
        chr.setPosition(position);
        chr.setMap(fmMap);
        return chr;
    }

    /** 轮询地图对象表直至出现至少一个指定类型对象或超时。 */
    private List<MapObject> awaitObjects(MapObjectType type, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<MapObject> found = fmMap.getMapObjects().stream()
                    .filter(o -> o.getType() == type)
                    .toList();
            if (!found.isEmpty()) {
                return found;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return fmMap.getMapObjects().stream()
                .filter(o -> o.getType() == type)
                .toList();
    }

    /** 反射替换 Server.instance：使虚拟线程内的 Server.getInstance() 也返回测试 mock。 */
    private static void setServerInstance(Server mock) {
        try {
            Field f = Server.class.getDeclaredField("instance");
            f.setAccessible(true);
            f.set(null, mock);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to set Server.instance", e);
        }
    }
}
