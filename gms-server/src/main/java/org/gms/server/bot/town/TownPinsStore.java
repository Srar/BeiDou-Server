package org.gms.server.bot.town;

import java.awt.Point;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 机器所有的 sidecar，存放「标记此处」命令追加的城镇 pin。与手写的 TownPresence.yaml
 * 分开保存，避免追加 pin 时重写（并破坏注释）那个文件。纯追加友好文本——每行一个 pin：
 * "&lt;mapId&gt;: &lt;x&gt;,&lt;y&gt;"。加载时并入各图的 pins。可安全手改或删除行。
 */
public final class TownPinsStore {

    private TownPinsStore() {
    }

    // gms 无源码树路径可写；源写 src/main/java/.../TownPins.txt，此处落在工作目录。
    private static final String PATH = "TownPins.txt";

    /** 追加一个 pin。首次创建文件时写头部。 */
    public static synchronized void addPin(int mapId, int x, int y) {
        try {
            File f = new File(PATH);
            boolean writeHeader = !f.exists();
            try (FileWriter fw = new FileWriter(f, true)) {
                if (writeHeader) {
                    fw.write("# Machine-owned pinned town spots (appended by !env townpresence mark).\n");
                    fw.write("# One per line:  <mapId>: <x>,<y>   - merged into TownPresence.yaml pins at load.\n");
                    fw.write("# Safe to hand-edit or delete lines.\n");
                }
                fw.write(mapId + ": " + x + "," + y + "\n");
            }
        } catch (IOException e) {
            System.out.println("[TownPinsStore] failed to append pin: " + e.getMessage());
        }
    }

    /** mapId -> 其 pin 点。文件尚不存在则返回空。 */
    public static synchronized Map<Integer, List<Point>> load() {
        Map<Integer, List<Point>> out = new HashMap<>();
        File f = new File(PATH);
        if (!f.exists()) {
            return out;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                int colon = s.indexOf(':');
                int comma = s.indexOf(',');
                if (colon < 0 || comma < 0 || comma < colon) {
                    continue;
                }
                try {
                    int mapId = Integer.parseInt(s.substring(0, colon).trim());
                    int x = Integer.parseInt(s.substring(colon + 1, comma).trim());
                    int y = Integer.parseInt(s.substring(comma + 1).trim());
                    out.computeIfAbsent(mapId, k -> new ArrayList<>()).add(new Point(x, y));
                } catch (NumberFormatException ignored) {
                    // 跳过畸形行，而非整次加载失败
                }
            }
        } catch (IOException e) {
            System.out.println("[TownPinsStore] failed to read pins: " + e.getMessage());
        }
        return out;
    }

    public static List<Point> forMap(int mapId) {
        return load().getOrDefault(mapId, List.of());
    }
}
