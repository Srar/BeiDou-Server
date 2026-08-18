package org.gms.server.bot.freemarket.shopoffer;

import java.util.function.LongSupplier;

/**
 * 议价会话（移植自 SoloMapling FreeMarket.ShopOfferSystem.HaggleSession）。
 * <p>
 * 每个「玩家 × bot 店主」一条会话：PRESENT 模式下同一玩家对同一 bot 店的连续报价
 * 共享还价上下文。上限 3 次报价，超过即踢人；60 秒无活动自动过期。
 */
public class HaggleSession {

    public static final int MAX_ATTEMPTS = 3;
    public static final long EXPIRY_MS = 60_000;

    private final int playerId;
    private final int shopOwnerId;
    private final LongSupplier clock;
    private int attempts;
    private long counterPrice;
    private long lastActivityTime;

    public HaggleSession(int playerId, int shopOwnerId) {
        this(playerId, shopOwnerId, System::currentTimeMillis);
    }

    /** 注入时钟（测试专用：可控过期判定）。 */
    HaggleSession(int playerId, int shopOwnerId, LongSupplier clock) {
        this.playerId = playerId;
        this.shopOwnerId = shopOwnerId;
        this.clock = clock;
        this.attempts = 0;
        this.counterPrice = -1;
        this.lastActivityTime = clock.getAsLong();
    }

    public int getPlayerId() {
        return playerId;
    }

    public int getShopOwnerId() {
        return shopOwnerId;
    }

    public int getAttempts() {
        return attempts;
    }

    public long getCounterPrice() {
        return counterPrice;
    }

    public void incrementAttempt() {
        attempts++;
        lastActivityTime = clock.getAsLong();
    }

    public void setCounterPrice(long price) {
        this.counterPrice = price;
    }

    public boolean hasExceededAttempts() {
        return attempts >= MAX_ATTEMPTS;
    }

    public boolean hasCounterPending() {
        return counterPrice > 0;
    }

    public boolean isExpired() {
        return clock.getAsLong() - lastActivityTime > EXPIRY_MS;
    }

    public void touch() {
        lastActivityTime = clock.getAsLong();
    }
}
