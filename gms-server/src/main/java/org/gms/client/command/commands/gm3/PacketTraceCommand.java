package org.gms.client.command.commands.gm3;

import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.net.packet.trace.PacketTraceConfig;
import org.gms.net.packet.trace.PacketTraceRegistry;
import org.gms.util.I18nUtil;

import java.util.List;

/**
 * GM3 命令 !pktrace：客户端崩溃诊断的包记录查看与导出（gms 增强）。
 * 子命令：list（在线/归档一览）/ status（开关状态）/ &lt;ip&gt;（手动导出该 IP 的包记录）。
 */
public class PacketTraceCommand extends Command {
    {
        setDescription(I18nUtil.getMessage("PacketTraceCommand.header"));
    }

    @Override
    public void execute(Client c, String[] params) {
        if (c == null || c.getPlayer() == null) {
            return;
        }
        String action = params != null && params.length > 0 ? params[0] : "list";
        switch (action) {
            case "list" -> handleList(c);
            case "status" -> handleStatus(c);
            default -> {
                if (action.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                    handleDump(c, action);
                } else {
                    c.getPlayer().yellowMessage(I18nUtil.getMessage("PacketTraceCommand.help"));
                }
            }
        }
    }

    /** 在线/归档包记录一览。 */
    private void handleList(Client c) {
        List<String> lines = PacketTraceRegistry.listSummary();
        c.getPlayer().yellowMessage(I18nUtil.getMessage("PacketTraceCommand.header"));
        if (lines.isEmpty()) {
            c.getPlayer().yellowMessage(I18nUtil.getMessage("PacketTraceCommand.empty"));
            return;
        }
        for (String line : lines) {
            c.getPlayer().yellowMessage(I18nUtil.getMessage("PacketTraceCommand.item", line));
        }
    }

    /** 包记录开关状态。 */
    private void handleStatus(Client c) {
        // 先刷新快照，status 显示实时值（GameConfig 热更新最多延迟 1 秒）
        PacketTraceConfig.refreshNow();
        c.getPlayer().yellowMessage(I18nUtil.getMessage("PacketTraceCommand.status",
                PacketTraceConfig.enabled(), PacketTraceConfig.capacity(), PacketTraceConfig.includeMove()));
    }

    /** 按 IP 手动导出包记录。 */
    private void handleDump(Client c, String ip) {
        String file = PacketTraceRegistry.dump(ip, "manual");
        if (file != null) {
            c.getPlayer().yellowMessage(I18nUtil.getMessage("PacketTraceCommand.dumped", file));
        } else {
            c.getPlayer().yellowMessage(I18nUtil.getMessage("PacketTraceCommand.miss", ip));
        }
    }
}
