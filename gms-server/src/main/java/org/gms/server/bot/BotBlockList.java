package org.gms.server.bot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Manages a block list for bots that are trading.
 * When a bot declines a trade, the trade partner is added to this block list with an expiration time.
 * （参考 SoloMapling 的 BotBlockList 1:1 移植：单例 + 过期清理）
 */
public class BotBlockList {

    /**
     * Represents an entry in the block list.
     */
    public static class BlockEntry {
        private final Integer botId;
        private final Integer traderId;
        private final Instant blockTime;
        private final Instant expirationTime;

        public BlockEntry(Integer botId, Integer traderId, Instant blockTime, Instant expirationTime) {
            this.botId = botId;
            this.traderId = traderId;
            this.blockTime = blockTime;
            this.expirationTime = expirationTime;
        }

        // Getters
        public Integer getBotId() {
            return botId;
        }

        public Integer getTraderId() {
            return traderId;
        }

        public Instant getBlockTime() {
            return blockTime;
        }

        public Instant getExpirationTime() {
            return expirationTime;
        }

        public boolean isExpired(Instant currentTime) {
            return currentTime.isAfter(expirationTime);
        }

        @Override
        public String toString() {
            return "BlockEntry{" +
                    "botId='" + botId + '\'' +
                    ", traderId='" + traderId + '\'' +
                    ", blockTime=" + blockTime +
                    ", expirationTime=" + expirationTime +
                    '}';
        }
    }

    private static final BotBlockList botBlockList = new BotBlockList();
    private final List<BlockEntry> blockList;

    public BotBlockList() {
        this.blockList = new ArrayList<>();
    }

    public static BotBlockList getInstance() {
        return botBlockList;
    }

    public BlockEntry addToBlockList(Integer botId, Integer traderId, long blockDurationSeconds) {
        Instant now = Instant.now();
        Instant expirationTime = now.plusSeconds(blockDurationSeconds);

        BlockEntry entry = new BlockEntry(botId, traderId, now, expirationTime);
        blockList.add(entry);

        return entry;
    }

    public BlockEntry addToBlockList(Integer botId, Integer traderId) {
        return addToBlockList(botId, traderId, 300);
    }

    public boolean isBlocked(Integer botId, Integer traderId) {
        Instant now = Instant.now();
        removeExpiredEntries();
        return blockList.stream()
                .anyMatch(entry -> entry.getBotId().equals(botId)
                        && entry.getTraderId().equals(traderId));
    }

    public Optional<BlockEntry> getBlockEntry(Integer botId, Integer traderId) {
        removeExpiredEntries();
        return blockList.stream()
                .filter(entry -> entry.getBotId().equals(botId)
                        && entry.getTraderId().equals(traderId))
                .findFirst();
    }

    public void removeExpiredEntries() {
        Instant now = Instant.now();
        Iterator<BlockEntry> iterator = blockList.iterator();
        while (iterator.hasNext()) {
            BlockEntry entry = iterator.next();
            if (entry.isExpired(now)) {
                iterator.remove();
            }
        }
    }

    public boolean removeBlock(Integer botId, Integer traderId) {
        return blockList.removeIf(entry ->
                entry.getBotId().equals(botId) && entry.getTraderId().equals(traderId));
    }

    public List<BlockEntry> getBlockList() {
        removeExpiredEntries();
        return new ArrayList<>(blockList);
    }

    public List<BlockEntry> getBlocksForBot(Integer botId) {
        removeExpiredEntries();
        return blockList.stream()
                .filter(entry -> entry.getBotId().equals(botId))
                .toList();
    }

    public List<Integer> getBlockedTraders(Integer botId) {
        removeExpiredEntries();
        return blockList.stream()
                .filter(entry -> entry.getBotId().equals(botId))
                .map(BlockEntry::getTraderId)
                .toList();
    }

    public void clearBlockList() {
        blockList.clear();
    }

    public int getActiveBlockCount() {
        removeExpiredEntries();
        return blockList.size();
    }
}
