package org.gms.server.bot.dialogue;

import org.gms.util.Randomizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bot 台词「近期不重复」守卫：按 key 记忆最近说过的台词索引，选句时优先排除；
 * 候选耗尽自动回退全池，保证永不因去重而静默。
 * <p>
 * 索引语义：同一 YAML 节点的台词列表按 YAML 顺序构建（{@link BotDialogueHandler#getDialogueCon}
 * 每次按序重建），索引稳定，因此按索引记忆等价于按台词内容记忆。
 * 池子 &lt;= recentN 时不做排除（小池靠扩文案解决重复，去重机制不造成静默）。
 */
public final class RecentLineGuard {

    private RecentLineGuard() {
    }

    /** 每 key 默认记忆条数。 */
    public static final int DEFAULT_RECENT = 8;

    private static final Map<String, Deque<Integer>> RECENT = new ConcurrentHashMap<>();

    /**
     * 从 [0, poolSize) 挑一个「最近没说过」的索引。
     *
     * @param key          记忆命名空间（如 per-bot、per-map、per-category）
     * @param poolSize     候选池大小
     * @param recentN      记忆条数（<=0 表示不排除）
     * @param extraExclude 调用方本轮的局部排除（如 token 解析失败的行），可为 null
     */
    public static int pickIndex(String key, int poolSize, int recentN, Set<Integer> extraExclude) {
        if (poolSize <= 0) {
            return 0;
        }
        int safeN = Math.min(recentN, poolSize - 1);
        List<Integer> exclude = new ArrayList<>();
        if (safeN > 0) {
            Deque<Integer> recent = RECENT.get(key);
            if (recent != null) {
                exclude.addAll(recent);
            }
        }
        int idx = pickExcluding(poolSize, exclude, extraExclude);
        if (idx >= 0) {
            return idx;
        }
        idx = pickExcluding(poolSize, null, extraExclude);
        if (idx >= 0) {
            return idx;
        }
        return Randomizer.nextInt(poolSize);
    }

    /** 记录 key 说过的台词索引（窗口最多 recentN 条）。 */
    public static void remember(String key, int index, int recentN) {
        if (recentN <= 0) {
            return;
        }
        RECENT.compute(key, (k, dq) -> {
            Deque<Integer> deque = dq != null ? dq : new ArrayDeque<>();
            deque.addLast(index);
            while (deque.size() > recentN) {
                deque.removeFirst();
            }
            return deque;
        });
    }

    /** 清空某 key 的记忆（bot 下线/换图等场景可选调用）。 */
    public static void forget(String key) {
        RECENT.remove(key);
    }

    /** 先排除 recent 集合，再并入 extraExclude；两者都没有时等价全池随机。 */
    private static int pickExcluding(int poolSize, List<Integer> recent, Set<Integer> extraExclude) {
        List<Integer> eligible = new ArrayList<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            boolean blocked = (recent != null && recent.contains(i))
                    || (extraExclude != null && extraExclude.contains(i));
            if (!blocked) {
                eligible.add(i);
            }
        }
        if (eligible.isEmpty()) {
            return -1;
        }
        return eligible.get(Randomizer.nextInt(eligible.size()));
    }
}
