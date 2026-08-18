package org.gms.server.bot.freemarket.shopoffer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * 议价会话（移植自 SoloMapling FreeMarket.ShopOfferSystem.HaggleSession）。
 * <p>
 * 每个「玩家 × bot 店主」一条会话：PRESENT 模式下同一玩家对同一 bot 店的连续报价
 * 共享还价上下文。上限 3 次报价，超过即踢人；60 秒无活动自动过期。
 * <p>
 * M1 修复（线程安全）：可变状态按职责选择同步手段——
 * <ul>
 *   <li>attempts 用 {@link AtomicInteger}：Netty 线程 touch 与虚拟线程
 *       incrementAttempt 竞争、连发报价并行登记时计数不丢失；</li>
 *   <li>incrementAttempt 原子返回登记后的累计次数，上限判定（{@code >= MAX_ATTEMPTS}）
 *       由调用方基于该返回值一次完成，登记与判定之间无竞态窗口；</li>
 *   <li>counterPrice/lastActivityTime 用 volatile 保证跨线程可见（单个 long 读写
 *       本身原子），写方法 synchronized 与 touch 活动刷新保持整体一致。</li>
 * </ul>
 */
public class HaggleSession {

    public static final int MAX_ATTEMPTS = 3;
    public static final long EXPIRY_MS = 60_000;

    private final int playerId;
    private final int shopOwnerId;
    private final LongSupplier clock;
    /** 报价次数（原子计数）：并发登记不丢失，上限判定可靠。 */
    private final AtomicInteger attempts = new AtomicInteger();
    /** 还价价格（-1 表示无还价）；volatile 保证跨线程可见。 */
    private volatile long counterPrice;
    /** 最后活动时间；volatile 保证 touch/isExpired 跨线程可见。 */
    private volatile long lastActivityTime;

    public HaggleSession(int playerId, int shopOwnerId) {
        this(playerId, shopOwnerId, System::currentTimeMillis);
    }

    /** 注入时钟（测试专用：可控过期判定）。 */
    HaggleSession(int playerId, int shopOwnerId, LongSupplier clock) {
        this.playerId = playerId;
        this.shopOwnerId = shopOwnerId;
        this.clock = clock;
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
        return attempts.get();
    }

    public long getCounterPrice() {
        return counterPrice;
    }

    /**
     * 登记一次报价并返回登记后的累计次数（原子操作）。
     * 并发连发报价时每次调用都拿到唯一递增序号，调用方用返回值与
     * {@link #MAX_ATTEMPTS} 比较即可原子完成「登记 + 上限判定」，
     * 不会出现旧实现「多线程各读 attempts=2 同时写 3」丢计数导致踢人上限失效的问题。
     */
    public synchronized int incrementAttempt() {
        lastActivityTime = clock.getAsLong();
        return attempts.incrementAndGet();
    }

    public synchronized void setCounterPrice(long price) {
        this.counterPrice = price;
    }

    public boolean hasExceededAttempts() {
        return attempts.get() >= MAX_ATTEMPTS;
    }

    public boolean hasCounterPending() {
        return counterPrice > 0;
    }

    public boolean isExpired() {
        return clock.getAsLong() - lastActivityTime > EXPIRY_MS;
    }

    public synchronized void touch() {
        lastActivityTime = clock.getAsLong();
    }
}
