package org.gms.server.bot.dialogue;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 加载 TownChatterDialogue.yaml：bot 与 bot 之间的有序闲聊对白。
 * 与 BotDialogueHandler 服务的单行对话节点（从一个节点随机抽一行）不同，
 * 一次 exchange 是一个有序的交替轮流列表（A,B,A,B...），这正是双人闲聊所需。
 * YAML 优先 + 防御性 fail-soft 解析（改坏 -> 空列表，BotChatter 回退到硬编码对白），
 * 模式同 TownPresenceConfig。
 */
public final class TownChatterLines {

    private TownChatterLines() {
    }

    private static final String YAML_PATH = "BotDialoguePack/TownChatterDialogue.yaml";

    private static volatile List<List<String>> cached;

    /** 解析后的 exchanges（首次加载后缓存）。任何解析/IO 失败返回空列表。 */
    public static List<List<String>> exchanges() {
        List<List<String>> local = cached;
        if (local == null) {
            local = load();
            cached = local;
        }
        return local;
    }

    /** 强制从磁盘重读（供 !env chatter 实时调参命令使用，编辑无需重启）。 */
    public static List<List<String>> reload() {
        cached = load();
        return cached;
    }

    /** 每地图记忆的最近段数（池 24 段，排除 6 段后仍有 18 段可选）。 */
    private static final int RECENT_EXCHANGES = 6;

    /** 一个随机的有序 exchange（>= 2 轮），没有则返回 null（无地图命名空间，共享去重记忆）。 */
    public static List<String> randomExchange() {
        return randomExchange(-1);
    }

    /** 每地图命名空间随机取一段，近期说过的不重复（见 {@link RecentLineGuard}）。 */
    public static List<String> randomExchange(int mapId) {
        List<List<String>> all = exchanges();
        if (all.isEmpty()) {
            return null;
        }
        String key = "tc:" + mapId;
        int idx = RecentLineGuard.pickIndex(key, all.size(), RECENT_EXCHANGES, null);
        RecentLineGuard.remember(key, idx, RECENT_EXCHANGES);
        return all.get(idx);
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> load() {
        List<List<String>> out = new ArrayList<>();
        try (InputStream in = TownChatterLines.class.getResourceAsStream(YAML_PATH)) {
            if (in == null) {
                System.out.println("[TownChatterLines] YAML resource not found: " + YAML_PATH);
                return out;
            }
            Yaml yaml = new Yaml();
            Map<String, Object> root = (Map<String, Object>) yaml.load(in);
            if (root == null) {
                return out;
            }
            Object node = root.get("exchanges");
            if (!(node instanceof List<?> list)) {
                return out;
            }
            for (Object ex : list) {
                if (!(ex instanceof List<?> turns)) {
                    continue;
                }
                List<String> lines = new ArrayList<>();
                for (Object t : turns) {
                    if (t != null) {
                        String s = t.toString().trim();
                        if (!s.isEmpty()) {
                            lines.add(s);
                        }
                    }
                }
                if (lines.size() >= 2) {
                    out.add(lines);
                }
            }
        } catch (Exception e) {
            System.out.println("[TownChatterLines] failed to load " + YAML_PATH + ": " + e.getMessage());
            return new ArrayList<>();
        }
        return out;
    }
}
