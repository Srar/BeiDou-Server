package org.gms.server.bot.freemarket;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Job;
import org.gms.net.server.Server;
import org.gms.server.bot.BotClientHolder;
import org.gms.server.bot.BotExecutors;
import org.gms.server.bot.BotGeneration;
import org.gms.server.bot.BotHelpers;
import org.gms.server.bot.DefaultBotServerAccess;
import org.gms.server.maps.HiredMerchant;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.PlayerShop;
import org.gms.server.maps.PlayerShopItem;
import org.gms.util.PacketCreator;

import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.gms.server.bot.BotCustomization.getRandomChairId;
import static org.gms.server.bot.BotCustomization.getRandomStorePermitId;
import static org.gms.server.bot.freemarket.ArtificialShopGenerator.applyAdditionalShopMods;
import static org.gms.server.bot.freemarket.ArtificialShopGenerator.applyCheapSaleDiscount;
import static org.gms.server.bot.freemarket.ArtificialShopGenerator.applyQuittingSaleDiscount;
import static org.gms.server.bot.freemarket.ArtificialShopGenerator.buyoutSomeItems;
import static org.gms.server.bot.freemarket.ArtificialShopGenerator.generateSecondaryShop;
import static org.gms.server.bot.freemarket.ArtificialShopGenerator.generateShop;
import static org.gms.server.bot.freemarket.ArtificialShopGenerator.setOneMesoShop;
import static org.gms.server.bot.freemarket.BotEconomy.getTierForRoom;
import static org.gms.server.bot.freemarket.BotEconomy.isHotRoom;
import static org.gms.server.bot.freemarket.FMShopDescGen.getRandomShopOwnerIGN;
import static org.gms.server.bot.freemarket.FMShopDescriptionManager.appendMerchantDescription;
import static org.gms.server.bot.freemarket.FMShopDescriptionManager.appendRoomNumber;
import static org.gms.server.bot.freemarket.FMShopDescriptionManager.setMerchantDescription;
import static org.gms.server.bot.freemarket.FMShopInfoManager.getRegionByMapId;
import static org.gms.server.bot.freemarket.FMShopTypeManager.getSecondaryShopItemTypes;
import static org.gms.server.bot.freemarket.FMShopTypeManager.selectHotRoomShopType;
import static org.gms.server.bot.freemarket.FMShopTypeManager.selectShopTypeWeightedProbability;
import static org.gms.server.bot.freemarket.HiredMerchantArtificial.shopTypes.*;
import static org.gms.server.bot.replay.DebugUtilities.debugprint;
import static org.gms.server.bot.replay.MovementCommands.botSitChair;
import static org.gms.server.bot.replay.MovementCommands.microTurnAround;

/**
 * 移植自 SoloMapling FreeMarket.ArtificialFreeMarket（逐行照搬）。
 *
 * <p>移植说明（gms 底座差异）：
 * <ul>
 *   <li>ExecutorServiceManager.runAsync / getScheduledExecutorService().schedule →
 *       {@link BotExecutors#runAsync} / {@link BotExecutors#schedule}（毫秒制，无 TimeUnit）。</li>
 *   <li>BotGeneration.createBotPollReadiness → 本类私有 {@link #createBotPollReadiness}：
 *       gms BotGeneration.createBot(Point, MapleMap) + DefaultBotServerAccess.getCharacterById 轮询
 *       （参考 EnvironmentManager.createBotWithRetry 模式，30 次 × 100ms）。</li>
 *   <li>SPAWN_CHOREOGRAPHY_MAX_MS 用 gms {@link BotGeneration#SPAWN_CHOREOGRAPHY_MAX_MS} 的值。</li>
 *   <li>spawnHiredMerchantStore：gms Client.createMock() 的 world/channel 为 -123，会使
 *       Character.getWorldServer() 返回 null 且 HiredMerchant 构造读到错误频道；改用
 *       BotClientHolder.getBotClient(world, channel) 共享 bot client 并显式 setWorld(world)。</li>
 *   <li>gms PlayerShop 无 setItems → BotPlayerStorePermit 逐条 addItem 等价替代。</li>
 *   <li>gms Channel.closeAllMerchants() 为 private（源自认 "doesnt work"）→ destroyAllShops 降级为 no-op。</li>
 *   <li>SoloMaplingUtilities.chance → 本类私有 {@link #chance}（实现逐行照搬）。</li>
 * </ul>
 */
public class ArtificialFreeMarket {

    static List<Integer> hiredMerchantsList = Arrays.asList(5030000, 5030001, 5030002, 5030004, 5030008, 5030010); // 5030012 - tiki torch
    private static final Random random = new Random();
    public static FMShopInfoManager fmInfo = new FMShopInfoManager();

    // 源 createBotPollReadiness 的轮询参数（EnvironmentManager.createBotWithRetry 同构）
    private static final int CREATE_BOT_POLL_MAX = 30;
    private static final long CREATE_BOT_POLL_INTERVAL_MS = 100;

    /**
     * T4-P1：人工雇佣商人摊的 mock owner id 唯一分配器。源实现 ownerId = 20000 + random(10001)
     * 小区段随机，会撞真人 id 且多摊重复；gms 中 {@code Channel.addHiredMerchant} 的 key 为
     * 角色 id（同 id 只注册成功 1 摊）、{@code World.registerHiredMerchant} 的 key 为
     * hm.getOwnerId()，两者都必须是唯一值。区段取 BotHelpers.BOT_BASE_ID(2_000_000_000)
     * + 10_000_000：远离真人 id（数据库自增自 1 起）、普通 bot id（BOT_BASE_ID + 100 起自增）
     * 与 Console 傀儡 id（BOT_BASE_ID + 100_000_000），且远小于 Integer.MAX_VALUE 无溢出。
     */
    private static final AtomicInteger artificialMerchantIdCounter =
            new AtomicInteger(BotHelpers.BOT_BASE_ID + 10_000_000);

    public static void populateFreeMarketSpot(Client c) {
        spawnHiredMerchantStore(c.getPlayer().getMapId(), c.getPlayer().getPosition());
    }

    public static void populateFreeMarketFull() {
        List<String> regions = List.of("henesys", "ludi", "perion", "elnath");
        for (String region : regions) {
            populateFreeMarketRegion(region);
        }
    }

    public static void populateFreeMarketRegion(String region) {
        // Fire every room in the region in parallel. Each room's internal work is
        // already offloaded to executors, so this just kicks them off concurrently
        // instead of sleeping 5s between maps.
        for (int mapId : fmInfo.getRegionFMMapId(region)) {
            BotExecutors.runAsync(() -> populateFreeMarketRoom(mapId));
        }
    }

    public static void populateFreeMarketRoom(int mapId) {
        String region = getRegionByMapId(mapId);
        debugprint("Populating room: ", mapId);

        List<Point> positions = fmInfo.getRegionFMSpots(region);
        AtomicInteger delayCounter = new AtomicInteger(0);

        double hiredMerchantChance = getHiredMerchantChance(mapId);

        for (Point position : positions) {
            // ~2% per-spot skip. Use continue so we don't abandon the rest of the room.
            if (chance(2)) {
                continue;
            }
            if (Math.random() < hiredMerchantChance) {
                BotExecutors.runAsync(() -> spawnHiredMerchantStore(mapId, position));
            } else {
                // Stagger bot-shop creation with small delays instead of blocking
                int delay = delayCounter.getAndIncrement() * 200; // 200ms between each
                BotExecutors.schedule(() -> createBotShopAtLocation(position, mapId), delay);
            }
        }
    }

    private static void spawnHiredMerchantStore(int mapId, Point position) {
        if (calculateSkippedSpot(mapId)) {
            return;
        }

        // T4-P7：world/channel 改为 bot 配置解析（原硬编码 0/1，配置化后不一致会注册到错误频道）。
        int world = DefaultBotServerAccess.resolveBotWorld();
        int channel = DefaultBotServerAccess.resolveBotChannel();

        // T4-P1：唯一 owner id（见 artificialMerchantIdCounter 注释）。
        int ownerId = artificialMerchantIdCounter.incrementAndGet();
        String ownerName = getRandomShopOwnerIGN();
        String description = "";

        int shop_item_id = hiredMerchantsList.get(random.nextInt(hiredMerchantsList.size())); // 5030000;
        // 引擎差异适配：gms Client.createMock() 的 world/channel 为 -123，会让 getWorldServer() 返回 null、
        // HiredMerchant 构造读到错误频道，改用共享 bot client 并显式设 world。
        Client cm = BotClientHolder.getBotClient(world, channel);
        Character newchar = Character.getDefault(cm);
        newchar.setWorld(world);
        newchar.setId(ownerId); // Channel.addHiredMerchant 以角色 id 为 key，必须唯一
        // 必须与 setId 对称：HiredMerchant 构造将 owner.getName() 写进父类私有 ownerName，
        // 漏设会导致真实玩家购买时 getCharacterByName(null) NPE。
        newchar.setName(ownerName);
        newchar.setPosition(position);
        newchar.setMap(Server.getInstance().getChannel(world, channel).getMapFactory().getMap(mapId));

        generateHiredMerchantShopData(newchar, ownerName, ownerId, description, shop_item_id, mapId);

        addMerchantToChannel(newchar);
    }

    private static HiredMerchantArtificial generateHiredMerchantShopData(Character newchar, String ownerName, int ownerId, String description, int shop_item_id, int mapId) {
        HiredMerchantArtificial newMerch = createMerchantObject(newchar, ownerName, ownerId, description, shop_item_id);

        String tier = getTierForRoom(mapId);

        newMerch.setTier(tier);
        newMerch.setMapId(mapId);

        /* todo add all scrolls weps for class type shops */
        boolean hotRoom = isHotRoom(mapId);
        HiredMerchantArtificial.shopTypes primaryShopType = hotRoom
                ? selectHotRoomShopType()
                : selectShopTypeWeightedProbability();
        newMerch.setPrimary(primaryShopType);

        generatePrimaryShop(primaryShopType, newMerch);

        if (hotRoom) {
            generateBonusEquipsForHotRoom(newMerch);
            generateSecondaryShopItems(newMerch);
            generateSecondaryShopItems(newMerch);
            fillHotRoomToMinimum(newMerch);
        } else {
            generateSecondaryShopItems(newMerch);
            generateTertiaryBackupShop(newMerch);
        }

        new FMShopDescriptionManager().generateDescription(newMerch);

        // special rules shop modifications
        applyAdditionalShopMods(newMerch);
        buyoutSomeItems(newMerch);
        applySpecialShopType(newMerch);

        appendRoomNumber(newMerch);
        return newMerch;
    }

    private static void generatePrimaryShop(HiredMerchantArtificial.shopTypes choice, HiredMerchantArtificial merchant) {
        Runnable action = switch (choice) {
            case Warrior -> () -> {
                generateShop(merchant, Job.WARRIOR);
            };
            case Mage -> () -> {
                generateShop(merchant, Job.MAGICIAN);
            };
            case Bowman -> () -> {
                generateShop(merchant, Job.BOWMAN);
            };
            case Thief -> () -> {
                generateShop(merchant, Job.THIEF);
            };
            case Common -> () -> {
                generateShop(merchant, Job.BEGINNER);
            };
            case Pirate -> () -> {
                debugprint("Generating pirate shop");
                generateShop(merchant, Job.BEGINNER);
            };
            case Scroll -> () -> {
                repeatAction(3, () -> generateSecondaryShop(merchant, Scroll));
            };
            case DarkScroll -> () -> {
                repeatAction(3, () -> generateSecondaryShop(merchant, DarkScroll));
            };
            case Potion -> () -> {
                repeatAction(3, () -> generateSecondaryShop(merchant, Potion));
            };
            case ETC -> () -> {
                repeatAction(3, () -> generateSecondaryShop(merchant, ETC));
            };
            case Chair -> () -> {
                repeatAction(5, () -> generateSecondaryShop(merchant, Chair));
            };
            case Mastery -> () -> {
                repeatAction(3, () -> generateSecondaryShop(merchant, Mastery));
            };
            default -> throw new IllegalArgumentException("Invalid shop type: " + choice);
        };
        action.run();
    }

    private static void generateSecondaryShopItems(HiredMerchantArtificial merchant) {
        // Randomly select 1 or 2 items based on weights
        List<HiredMerchantArtificial.shopTypes> selectedItems = getSecondaryShopItemTypes();
        merchant.setSecondary(selectedItems.getFirst());

        for (HiredMerchantArtificial.shopTypes itemType : selectedItems) {
            applySecondaryShopItems(merchant, itemType);
        }
    }

    private static void applySecondaryShopItems(HiredMerchantArtificial merchant, HiredMerchantArtificial.shopTypes item) {
        switch (item) {
            case Potion:
                generateSecondaryShop(merchant, Potion);
                break;
            case Scroll:
                generateSecondaryShop(merchant, Scroll);
                break;
            case DarkScroll:
                generateSecondaryShop(merchant, DarkScroll);
                break;
            case Mastery:
                generateSecondaryShop(merchant, Mastery);
                break;
            case Chair:
                generateSecondaryShop(merchant, Chair);
                break;
            case ETC:
                generateSecondaryShop(merchant, ETC);
                break;
            default:
                debugprint("Unknown item: " + item);
        }
    }

    private static void generateTertiaryBackupShop(HiredMerchantArtificial merchant) {
        if (merchant.getItems().size() < 5) {
            int choice = random.nextInt(5); // Random Equip Common thru Pirate
            HiredMerchantArtificial.shopTypes tertiary = HiredMerchantArtificial.shopTypes.values()[choice];
            merchant.setTertiary(tertiary);
            generatePrimaryShop(tertiary, merchant);
        }
    }

    private static final Job[] CLASS_JOBS = {Job.WARRIOR, Job.MAGICIAN, Job.BOWMAN, Job.THIEF, Job.BEGINNER};

    private static void generateBonusEquipsForHotRoom(HiredMerchantArtificial merchant) {
        Job bonusClass = CLASS_JOBS[random.nextInt(CLASS_JOBS.length)];
        generateShop(merchant, bonusClass);
    }

    private static void fillHotRoomToMinimum(HiredMerchantArtificial merchant) {
        int attempts = 0;
        while (merchant.getItems().size() < 10 && attempts < 3) {
            Job fillClass = CLASS_JOBS[random.nextInt(CLASS_JOBS.length)];
            generateShop(merchant, fillClass);
            attempts++;
        }
    }

    private static HiredMerchantArtificial createMerchantObject(Character newchar, String ownerName, int ownerId, String description, int shopItemId) {
        // STEP 1 - CREATE MERCHANT OBJECT
        HiredMerchantArtificial merchant = new HiredMerchantArtificial(newchar, description, shopItemId, ownerId, ownerName);
        newchar.setHiredMerchant(merchant);
        newchar.getWorldServer().registerHiredMerchant(merchant);
        newchar.getWorldServer().getChannel(DefaultBotServerAccess.resolveBotChannel()).addHiredMerchant(newchar.getId(), merchant);
        return merchant;
    }

    private static HiredMerchant addMerchantToChannel(Character newchar) {
        // Step 3
        HiredMerchant merchant = newchar.getHiredMerchant();
        newchar.setHasMerchant(true);
        merchant.setOpen(true);
        merchant.getMap().addMapObject(merchant);
        newchar.setHiredMerchant(null);
        merchant.getMap().broadcastMessage(PacketCreator.spawnHiredMerchantBox(merchant));
        return merchant;
    }

    private static double getHiredMerchantChance(int mapId) {
        return switch (mapId) {
            case 910000001 -> 0.90;
            case 910000002 -> 0.80;
            case 910000007 -> 0.70;
            case 910000003 -> 0.60;
            default -> 0.40;
        };
    }

    private static boolean calculateSkippedSpot(int mapId) {
        // Map to store regions and their probabilities
        Map<String, Double> regionProbabilities = Map.of(
                "henesys", 0.002,
                "ludi", 0.04,
                "perion", 0.10,
                "elnath", 0.15
        );

        // Iterate through the map to find the matching region and apply the probability
        for (Map.Entry<String, Double> entry : regionProbabilities.entrySet()) {
            if (fmInfo.getRegionFMMapId(entry.getKey()).contains(mapId)) {
                return Math.random() < entry.getValue();
            }
        }

        return false; // Default case
    }

    // gms 增强：特价后缀从源 3 种扩到 12 种变体（577 摊位下防重复感）。
    private static final List<String> QUITTING_SALE_SUFFIXES = List.of(
            " 退游清仓大甩卖", " 老板跑路价", " 关店最后一天", " 退坑甩卖");
    private static final List<String> CHEAP_SALE_SUFFIXES = List.of(
            " 便宜甩卖", " 骨折价", " 血亏甩卖", " 今日特价", " 全场五折起", " 不赚差价", " 手慢无", " 老客户专享");

    private static void applySpecialShopType(HiredMerchantArtificial merchant) {
        Random random = new Random();
        int roll = random.nextInt(10_000);
        if (roll == 0) { // 1 in 10,000 chance
            setOneMesoShop(merchant);
            setMerchantDescription(merchant, " 一币店!!!");
            return;
        }
        if (roll < 100) { // 100 in 10,000 chance (1%)
            // Quitting Sale
            applyQuittingSaleDiscount(merchant);
            appendMerchantDescription(merchant, QUITTING_SALE_SUFFIXES.get(random.nextInt(QUITTING_SALE_SUFFIXES.size())));
            return;
        }
        if (roll < 800) { // 800 in 10,000 chance (7%)
            applyCheapSaleDiscount(merchant);
            appendMerchantDescription(merchant, CHEAP_SALE_SUFFIXES.get(random.nextInt(CHEAP_SALE_SUFFIXES.size())));
            return;
        }
    }

    // doesnt work
    public static void destroyAllShops(Client c) {
        // 引擎差异适配：源调用 c.getWorldServer().getChannel(1).closeAllMerchants()，
        // gms Channel.closeAllMerchants() 为 private 且源自认 "doesnt work"，降级为 no-op。
        debugprint("Destroy all shops");
    }

    private static void repeatAction(int times, Runnable action) {
        for (int i = 0; i < times; i++) {
            action.run();
        }
    }

    /////////////////////////////////

    // Bot Store Permits

    public static void BotPlayerStorePermit(Character fakechar) {
        String desc = "Test";
        Integer shopItemId = getRandomStorePermitId();
        PlayerShop ps = new PlayerShop(fakechar, desc, shopItemId);
        fakechar.setPlayerShop(ps);
        fakechar.getMap().addMapObject(ps);

        HiredMerchantArtificial hma = generateHiredMerchantShopData(fakechar, fakechar.getName(), fakechar.getId(), desc, 5030000, fakechar.getMapId());
        String desc2 = hma.getDescription();
        ps.setDescription(desc2);
        List<PlayerShopItem> premadeShop = hma.getItems();
        // 引擎差异适配：gms PlayerShop 无 setItems，逐条 addItem 等价替代（16 格上限内，HMA 最多 16 项）。
        for (PlayerShopItem shopItem : premadeShop) {
            ps.addItem(shopItem);
        }

        // open shop on map
        PlayerShop fakecharplayershop = fakechar.getPlayerShop();
        fakecharplayershop.setOpen(true);
    }

    public static void main(String[] args) {
    }

    public static void createBotShopAtLocation(Point position, int mapId) {
        BotExecutors.runAsync(() -> {
            debugprint("Making shop at: " + mapId + ", " + position);

            Character fakechar2 = createBotPollReadiness(position, mapId);
            if (fakechar2 == null) {
                System.err.println("Bot not ready after 3 seconds, skipping store");
                return;
            }

            // The spawn drop-down/turn-around choreography plays asynchronously,
            // so wait it out before opening the store - a shop popping open while
            // the bot is still mid-drop looks broken.
            BotExecutors.schedule(() -> BotExecutors.runAsync(() -> {
                BotPlayerStorePermit(fakechar2);

                if (Math.random() < 0.5) {
                    microTurnAround(fakechar2);
                }
                if (Math.random() < 0.4) {
                    BotExecutors.schedule(() -> botSitChair(fakechar2, getRandomChairId()), 500);
                }
            }), BotGeneration.SPAWN_CHOREOGRAPHY_MAX_MS);
        });
    }

    /**
     * 源 BotGeneration.createBotPollReadiness 的 gms 等价实现（参考 EnvironmentManager.createBotWithRetry 模式）：
     * BotGeneration.createBot(Point, MapleMap) 同步注册并落图后，轮询 DefaultBotServerAccess.getCharacterById
     * 直到角色在频道存储中可见（30 次 × 100ms）；超时返回 null（不重建，避免泄漏孤儿 bot）。
     */
    private static Character createBotPollReadiness(Point position, int mapId) {
        MapleMap map = DefaultBotServerAccess.INSTANCE.getMap(
                DefaultBotServerAccess.resolveBotWorld(), DefaultBotServerAccess.resolveBotChannel(), mapId);
        if (map == null) {
            return null;
        }
        int botId = BotGeneration.createBot(position, map);
        if (botId <= 0) {
            return null;
        }
        for (int poll = 1; poll <= CREATE_BOT_POLL_MAX; poll++) {
            Character bot = DefaultBotServerAccess.INSTANCE.getCharacterById(botId);
            if (bot != null) {
                return bot;
            }
            try {
                Thread.sleep(CREATE_BOT_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    // 源 SoloMaplingUtilities.chance(double percent) 逐行照搬（gms BotRand 无此方法）。
    private static boolean chance(double percent) {
        return ThreadLocalRandom.current().nextDouble(100) < percent;
    }
}
