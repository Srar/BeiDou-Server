package org.gms.server.bot.decorate;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.net.server.Server;
import org.gms.server.TimerManager;
import org.gms.server.bot.BotExecutors;
import org.gms.server.bot.BotHelpers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

@Slf4j
public class BotEquipChecker {

    private static ScheduledFuture<?> task;
    private static final long INTERVAL_MS = 2 * 60 * 1000;

    private static final short SLOT_TOP = -5;
    private static final short SLOT_PANTS = -6;

    public static void start() {
        // 修复 in-place restart 失效：旧 task 可能已被 TimerManager.shutdownNow 取消但引用未清，
        // 仅判 task != null 会导致重启后不再注册；改为「null 或已取消」才重新注册。
        if (task != null && !task.isCancelled()) return;
        // SoloMapling used ExecutorServiceManager.getScheduledExecutorService()
        // .scheduleAtFixedRate(...); gms routes periodic work through TimerManager
        // (brought up by BotExecutors.ensureStarted()).
        BotExecutors.ensureStarted();
        task = TimerManager.getInstance().register(BotEquipChecker::check, INTERVAL_MS, INTERVAL_MS);
        log.info("Started — interval={}min", (INTERVAL_MS / 60000));
    }

    private static void check() {
        try {
            long startMs = System.currentTimeMillis();
            List<Character> allChars = new ArrayList<>(
                    Server.getInstance().getChannel(0, 1).getPlayerStorage().getAllCharacters()
            );

            int totalBots = 0;
            List<Character> naked = new ArrayList<>();
            for (Character chr : allChars) {
                if (!BotHelpers.isBot(chr)) continue;
                // Mapless bots (e.g. the Console bot) can't be dressed - equipping
                // broadcasts a char-look update to the bot's map.
                if (chr.getMap() == null) continue;
                totalBots++;
                if (isNaked(chr)) naked.add(chr);
            }

            long elapsed = System.currentTimeMillis() - startMs;
            log.debug("Scan complete: {}/{} naked ({}ms)", naked.size(), totalBots, elapsed);

            if (!naked.isEmpty()) {
                log.debug("Found {} naked bots out of {}, fixing...", naked.size(), totalBots);
                final int[] fixedCount = {0};
                final int[] failedCount = {0};
                for (Character chr : naked) {
                    BotExecutors.runAsync(() -> {
                        try {
                            BotDecorateEquips.equipTopBottom(chr);
                            fixedCount[0]++;
                        } catch (Exception e) {
                            failedCount[0]++;
                            log.warn("FAILED {} (job={} lv={})", chr.getName(),
                                    chr.getJob() != null ? chr.getJob().name() : "?",
                                    chr.getLevel(), e);
                        }
                    });
                }
            } else {
                log.debug("All {} bots are dressed.", totalBots);
            }
        } catch (Exception e) {
            // Server 未就绪（getChannel/getPlayerStorage 返回 null）等：静默，避免停机窗口刷屏。
            log.debug("BotEquipChecker skipped (server not ready)", e);
        }
    }

    private static boolean isNaked(Character chr) {
        Inventory equipped = chr.getInventory(InventoryType.EQUIPPED);
        if (equipped == null) {
            return false; // no equipped inventory to inspect — skip rather than flag a false positive
        }
        boolean hasTop = equipped.getItem(SLOT_TOP) != null;
        boolean hasPants = equipped.getItem(SLOT_PANTS) != null;
        return !hasTop || !hasPants;
    }
}
