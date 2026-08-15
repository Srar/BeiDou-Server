package org.gms.net.server.handlers;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Client;
import org.gms.net.PacketHandler;
import org.gms.net.packet.InPacket;

/**
 * v83 客户端错误/崩溃上报包处理器（gms 增强）：
 * <ul>
 *   <li>{@code CLIENT_START_ERROR (0x19)} — 客户端启动错误</li>
 *   <li>{@code CLIENT_ERROR (0x1A)} — 客户端崩溃时（Nexon crash report 机制）主动发送的错误报告</li>
 * </ul>
 * 原 OdinMS 系实现未注册这两个 opcode，客户端崩溃报告被静默丢弃。本 handler 不做协议
 * 深度解析（不同私有服的包体格式不一），只把原始字节 hex 化并打 WARN 日志，供服务端与
 * 客户端侧（Windows 事件查看器 / crash dump）对时排查。注意：包内不包含崩溃堆栈。
 */
@Slf4j
public class ClientErrorHandler implements PacketHandler {

    private final String errorKind;

    public ClientErrorHandler(String errorKind) {
        this.errorKind = errorKind;
    }

    @Override
    public void handlePacket(InPacket p, Client c) {
        byte[] payload = p.available() > 0 ? p.readBytes(p.available()) : new byte[0];
        StringBuilder hex = new StringBuilder(payload.length * 2);
        for (byte b : payload) {
            hex.append(String.format("%02X", b));
        }
        log.warn("客户端错误报告 [{}] account={} accId={} ip={} bytes={} payload={}",
                errorKind,
                safeAccountName(c),
                c != null ? c.getAccID() : -1,
                c != null ? c.getRemoteAddress() : "null",
                payload.length,
                hex);
    }

    private static String safeAccountName(Client c) {
        try {
            return c != null ? c.getAccountName() : "null";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Override
    public boolean validateState(Client c) {
        // 客户端崩溃可能发生在任何登录阶段（登录中/选角/游戏中），一律接收。
        return true;
    }
}
