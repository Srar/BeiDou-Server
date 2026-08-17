package org.gms.net.packet.trace;

import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.gms.server.ThreadManager;
import org.gms.util.I18nUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * 包记录注册表：在线连接索引 + 断连归档 + 崩溃报告触发导出（gms 增强，崩溃诊断用）。
 * <p>
 * 客户端崩溃报告（CLIENT_START_ERROR/CLIENT_ERROR）是「客户端下次开启时才发送」的，
 * 因此证据必须在断连瞬间固化：{@link #archive(String, PacketTraceBuffer)} 在
 * channelInactive 时把该连接的环形缓冲移入归档并立即异步写盘，报告无论多晚到达
 * 都能经 {@link #dump(String, String)} 按 IP 找回现场。查找顺序：
 * 断连归档优先（崩溃现场）→ 在线连接兜底（TCP 半开：对端进程已死但连接未断）。
 */
@Slf4j
public final class PacketTraceRegistry {

    private PacketTraceRegistry() {
    }

    /** channel attr：挂载该连接的环形缓冲。 */
    public static final AttributeKey<PacketTraceBuffer> CHANNEL_KEY =
            AttributeKey.valueOf("packetTraceBuffer");

    /** 内存归档上限（按 IP，淘汰最旧）。 */
    private static final int ARCHIVE_CAPACITY = 8;

    /** 测试钩子：禁用文件输出。 */
    private static volatile boolean fileOutputDisabled = false;

    /** packettrace 目录最多保留的导出文件数（按文件名时间戳排序）。 */
    private static final int MAX_TRACE_FILES = 20;

    private static final Path TRACE_DIR = Paths.get("logs", "packettrace");

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final DateTimeFormatter LINE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 在线连接：ip → 该 ip 的全部活跃连接缓冲（登录+频道同 ip 会各占一条）。 */
    private static final Map<String, List<PacketTraceBuffer>> ONLINE = new ConcurrentHashMap<>();

    /** 断连归档：ip → 最近一次断连的缓冲（插入序淘汰）。 */
    private static final LinkedHashMap<String, PacketTraceBuffer> ARCHIVE = new LinkedHashMap<>();

    /** 连接建立：把缓冲挂进在线索引。 */
    public static void registerOnline(String ip, PacketTraceBuffer buffer) {
        if (ip == null || buffer == null) {
            return;
        }
        ONLINE.computeIfAbsent(ip, k -> new CopyOnWriteArrayList<>()).add(buffer);
    }

    /** 测试钩子：关闭文件输出（避免单测污染工作目录 logs/packettrace）。 */
    static void disableFileOutputForTest(boolean disabled) {
        fileOutputDisabled = disabled;
    }

    /** 测试钩子：清空在线索引与断连归档（消除测试间静态状态依赖）。 */
    static void clearForTest() {
        ONLINE.clear();
        synchronized (ARCHIVE) {
            ARCHIVE.clear();
        }
    }

    /**
     * 连接断开：移出在线索引 → 归档（容量淘汰最旧）→ 异步写盘固化证据。
     * 幂等：重复归档同一缓冲无副作用（在线列表移除是 contains 判断）。
     */
    public static void archive(String ip, PacketTraceBuffer buffer) {
        if (ip == null || buffer == null) {
            return;
        }
        List<PacketTraceBuffer> list = ONLINE.get(ip);
        if (list != null) {
            list.remove(buffer);
            if (list.isEmpty()) {
                ONLINE.remove(ip, list);
            }
        }
        synchronized (ARCHIVE) {
            boolean replaced = ARCHIVE.containsKey(ip) && ARCHIVE.get(ip) != buffer;
            ARCHIVE.put(ip, buffer);
            while (ARCHIVE.size() > ARCHIVE_CAPACITY) {
                String oldestIp = ARCHIVE.keySet().iterator().next();
                ARCHIVE.remove(oldestIp);
            }
            if (replaced) {
                log.info(msg("PacketTrace.registry.archive.replaced", ip));
            }
        }
        log.info(msg("PacketTrace.registry.archive", ip, buffer.size()));
        // 功能关闭时缓冲为空，写盘无意义；内存归档仍保留（重开后可查）。
        if (PacketTraceConfig.enabled()) {
            writeFileAsync(ip, "disc", buffer);
        }
    }

    /** 按 IP 查找缓冲：归档优先（崩溃现场），在线兜底（半开连接）。 */
    public static PacketTraceBuffer find(String ip) {
        if (ip == null) {
            return null;
        }
        synchronized (ARCHIVE) {
            PacketTraceBuffer archived = ARCHIVE.get(ip);
            if (archived != null) {
                return archived;
            }
        }
        List<PacketTraceBuffer> list = ONLINE.get(ip);
        if (list == null || list.isEmpty()) {
            return null;
        }
        PacketTraceBuffer best = null;
        for (PacketTraceBuffer buffer : list) {
            if (best == null || buffer.lastActivityMillis() > best.lastActivityMillis()) {
                best = buffer;
            }
        }
        return best;
    }

    /**
     * 崩溃报告触发：导出该 IP 的包记录（文件名含 crash_ 标签）。
     *
     * @return 导出文件绝对路径；该 IP 无任何记录时返回 null（调用方应打「未找到」日志）。
     */
    public static String dump(String ip, String reason) {
        PacketTraceBuffer buffer = find(ip);
        if (buffer == null) {
            log.warn(msg("PacketTrace.registry.dump.miss", ip));
            return null;
        }
        return exportToFile(ip, "crash_" + reason, buffer);
    }

    /** GM 命令用：在线/归档一览（每行一条，供 yellowMessage 输出）。 */
    public static List<String> listSummary() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<PacketTraceBuffer>> entry : ONLINE.entrySet()) {
            for (PacketTraceBuffer buffer : entry.getValue()) {
                lines.add(String.format("[ONLINE] ip=%s entries=%d last=%s",
                        entry.getKey(), buffer.size(), formatMillis(buffer.lastActivityMillis())));
            }
        }
        synchronized (ARCHIVE) {
            for (Map.Entry<String, PacketTraceBuffer> entry : ARCHIVE.entrySet()) {
                PacketTraceBuffer buffer = entry.getValue();
                lines.add(String.format("[ARCHIVE] ip=%s entries=%d last=%s",
                        entry.getKey(), buffer.size(), formatMillis(buffer.lastActivityMillis())));
            }
        }
        return lines;
    }

    /** 异步写盘（虚拟线程）；失败仅 WARN，不影响主链路。 */
    private static void writeFileAsync(String ip, String tag, PacketTraceBuffer buffer) {
        try {
            ThreadManager.getInstance().newTask(() -> {
                try {
                    exportToFile(ip, tag, buffer);
                } catch (RuntimeException e) {
                    log.warn(msg("PacketTrace.registry.export.fail", e.toString()), e);
                }
            });
        } catch (RuntimeException e) {
            // ThreadManager 未就绪（早期单测等）：降级同步写盘
            try {
                exportToFile(ip, tag, buffer);
            } catch (RuntimeException inner) {
                log.warn(msg("PacketTrace.registry.export.fail", inner.toString()), inner);
            }
        }
    }

    /**
     * 同步导出：快照 + 写 logs/packettrace/{ts}_{tag}_{ip}.log，并清理超量旧文件。
     * 文件名以时间戳开头：字典序即时间序，cleanupOldFiles 的排序因此正确。
     * ip 中的 ':' 替换为 '_'，兼容 IPv6 文件名。
     *
     * @return 导出文件的绝对路径
     */
    private static String exportToFile(String ip, String tag, PacketTraceBuffer buffer) {
        if (fileOutputDisabled) {
            return null;
        }
        List<PacketTraceEntry> entries = buffer.snapshot();
        StringBuilder sb = new StringBuilder(entries.size() * 96 + 512);
        sb.append("Packet Trace Export\n");
        sb.append("ip: ").append(ip).append('\n');
        sb.append("tag: ").append(tag).append('\n');
        sb.append("exportedAt: ").append(formatMillis(System.currentTimeMillis())).append('\n');
        sb.append("entryCount: ").append(entries.size()).append('\n');
        sb.append("direction: S = server -> client, C = client -> server\n");
        sb.append("--------------------------------------------------------------\n");
        for (PacketTraceEntry entry : entries) {
            sb.append(formatMillis(entry.timeMillis()))
                    .append(' ').append((char) entry.direction())
                    .append(String.format(" 0x%04X", entry.opcode()))
                    .append(' ').append(entry.opcodeName() == null ? "<Unknown>" : entry.opcodeName())
                    .append(" len=").append(entry.length()).append('\n')
                    .append(entry.hex()).append('\n');
        }
        try {
            Files.createDirectories(TRACE_DIR);
            String ts = FILE_TS.format(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(System.currentTimeMillis()), ZoneId.systemDefault()));
            String fileName = ts + "_" + tag + "_" + ip.replace(':', '_') + ".log";
            Path file = TRACE_DIR.resolve(fileName);
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            cleanupOldFiles();
            log.info(msg("PacketTrace.registry.exported", file.toAbsolutePath()));
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write packet trace file", e);
        }
    }

    /** 目录文件数超上限时按文件名（含时间戳）删最旧。失败静默。 */
    private static void cleanupOldFiles() {
        try (Stream<Path> stream = Files.list(TRACE_DIR)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            int excess = files.size() - MAX_TRACE_FILES;
            for (int i = 0; i < excess; i++) {
                try {
                    Files.deleteIfExists(files.get(i));
                } catch (IOException ignored) {
                    // 尽力清理
                }
            }
        } catch (IOException ignored) {
            // 目录不存在等：忽略
        }
    }

    /**
     * i18n 日志消息兜底：I18nUtil 依赖 Spring 上下文（单测/极早期停机未就绪时
     * 类初始化会失败），包记录是诊断旁路，绝不能因日志文案未就绪而破坏归档链路。
     */
    private static String msg(String key, Object... args) {
        try {
            return I18nUtil.getLogMessage(key, args);
        } catch (Throwable t) {
            return key + ": " + Arrays.toString(args);
        }
    }

    private static String formatMillis(long millis) {
        return millis <= 0 ? "-" : LINE_TS.format(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
    }
}
