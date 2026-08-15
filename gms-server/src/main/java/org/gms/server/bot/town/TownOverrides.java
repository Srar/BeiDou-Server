package org.gms.server.bot.town;

import java.awt.Point;
import java.util.List;

/**
 * 每图策展覆盖，与锚点加权算法（TownPresenceSampler）组合：
 * <ul>
 *   <li>pins：精确点位，总是优先放置；</li>
 *   <li>ban：矩形，绝不放置 bot；</li>
 *   <li>boost：矩形，其平台权重被放大。</li>
 * </ul>
 * 区域的 Y 界可选（Integer.MIN/MAX 表示未设置）。数据由拥有者在 TownPresence.yaml 中
 * 手写（加上 mark 命令追加的 pins）。
 */
public final class TownOverrides {

    public record Zone(int x1, int y1, int x2, int y2, double mult) {
        public boolean contains(int x, int y) {
            return x >= Math.min(x1, x2) && x <= Math.max(x1, x2)
                    && y >= Math.min(y1, y2) && y <= Math.max(y1, y2);
        }
    }

    public static final TownOverrides EMPTY = new TownOverrides(List.of(), List.of(), List.of());

    private final List<Zone> ban;
    private final List<Zone> boost;
    private final List<Point> pins;

    public TownOverrides(List<Zone> ban, List<Zone> boost, List<Point> pins) {
        this.ban = ban;
        this.boost = boost;
        this.pins = pins;
    }

    public List<Point> pins() {
        return pins;
    }

    public boolean isEmpty() {
        return ban.isEmpty() && boost.isEmpty() && pins.isEmpty();
    }

    public boolean isBanned(int x, int y) {
        for (Zone z : ban) {
            if (z.contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    public double boostMultiplier(int x, int y) {
        double m = 1.0;
        for (Zone z : boost) {
            if (z.contains(x, y)) {
                m *= z.mult();
            }
        }
        return m;
    }
}
