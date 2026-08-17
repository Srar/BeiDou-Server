package org.gms.net.server.handlers;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Client;
import org.gms.net.PacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.trace.PacketTraceRegistry;
import org.gms.util.I18nUtil;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v83 客户端错误/崩溃上报包处理器（gms 增强）：
 * <ul>
 *   <li>{@code CLIENT_START_ERROR (0x19)} — 客户端启动错误</li>
 *   <li>{@code CLIENT_ERROR (0x1A)} — 客户端崩溃时（Nexon crash report 机制）主动发送的错误报告</li>
 * </ul>
 * 原 OdinMS 系实现未注册这两个 opcode，客户端崩溃报告被静默丢弃。崩溃报告是「客户端下次
 * 开启时才发送」的，此时崩溃连接早已断开，包记录已在断连瞬间归档。本 handler 在保留原始
 * 字节 hex 日志的基础上，解析包体文本（GBK 解码，提取 ver/角色名/大区/频道/地图/错误码），
 * 并按来源 IP 触发 {@link PacketTraceRegistry#dump(String, String)} 导出崩溃前最后 N 个包。
 * 注意：包内不包含崩溃堆栈。
 */
@Slf4j
public class ClientErrorHandler implements PacketHandler {

    /** 报告正文编码（客户端中文环境用 GBK）。 */
    private static final Charset GBK = Charset.forName("GBK");

    /** 首条 hex 日志的最大字节数（超出截断，防超大 payload 刷日志）。 */
    private static final int MAX_HEX_LOG_BYTES = 1024;

    private static final Pattern PAT_VER = Pattern.compile("ver\\((\\d+)\\)");
    private static final Pattern PAT_CHAR = Pattern.compile("CharacterName\\(([^)]*)\\)");
    private static final Pattern PAT_WORLD = Pattern.compile("WorldID\\((\\d+)\\)");
    private static final Pattern PAT_CH = Pattern.compile("ChID\\((\\d+)\\)");
    private static final Pattern PAT_FIELD = Pattern.compile("FieldID\\((\\d+)\\)");
    private static final Pattern PAT_ERR = Pattern.compile("error code\\s*:\\s*(\\d+)\\s*\\(([^)]*)\\)");

    private final String errorKind;

    public ClientErrorHandler(String errorKind) {
        this.errorKind = errorKind;
    }

    @Override
    public void handlePacket(InPacket p, Client c) {
        byte[] payload = p.available() > 0 ? p.readBytes(p.available()) : new byte[0];
        // hex 洪泛截断：只保留前 1024 字节，超出以 "..." 标记（bytes 仍显示总数）
        int hexBytes = Math.min(payload.length, MAX_HEX_LOG_BYTES);
        StringBuilder hex = new StringBuilder(hexBytes * 2 + 4);
        for (int i = 0; i < hexBytes; i++) {
            hex.append(String.format("%02X", payload[i]));
        }
        if (payload.length > MAX_HEX_LOG_BYTES) {
            hex.append("...");
        }
        log.warn(I18nUtil.getLogMessage("ClientErrorHandler.report",
                errorKind,
                safeAccountName(c),
                c != null ? c.getAccID() : -1,
                c != null ? c.getRemoteAddress() : "null",
                payload.length,
                hex));
        // 文本解析与导出均为尽力而为：任意垃圾字节/环境异常都不允许向上抛（hex 日志已打）。
        try {
            String text = decodePayload(payload);
            String ver = extract(PAT_VER, text);
            String charName = extract(PAT_CHAR, text);
            String worldId = extract(PAT_WORLD, text);
            String chId = extract(PAT_CH, text);
            String fieldId = extract(PAT_FIELD, text);
            String errorDesc = extractErrorDesc(text);
            log.warn(I18nUtil.getLogMessage("ClientErrorHandler.parse",
                    ver, charName, worldId, chId, fieldId, errorDesc));

            String ip = c != null ? c.getRemoteAddress() : null;
            String file = PacketTraceRegistry.dump(ip, errorKind);
            if (file != null) {
                log.warn(I18nUtil.getLogMessage("ClientErrorHandler.trace.dumped", ip, file));
            }
        } catch (Throwable t) {
            // 解析/导出失败：静默，原始 hex 日志已足以支撑排查
        }
    }

    /** 跳过客户端报告头（0x80 0x00）后按 GBK 解码；解码失败回退 ISO-8859-1（永不抛异常）。 */
    private static String decodePayload(byte[] payload) {
        if (payload.length == 0) {
            return "";
        }
        int offset = 0;
        if (payload.length >= 2 && (payload[0] & 0xFF) == 0x80 && (payload[1] & 0xFF) == 0x00) {
            offset = 2;
        }
        byte[] body = Arrays.copyOfRange(payload, offset, payload.length);
        try {
            return GBK.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(body, StandardCharsets.ISO_8859_1);
        }
    }

    /** 正则提取字段；未匹配返回 "-"。 */
    private static String extract(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "-";
    }

    /** 提取错误码与描述（如 "5 (拒绝访问。)"）；未匹配返回 "-"。 */
    private static String extractErrorDesc(String text) {
        Matcher matcher = PAT_ERR.matcher(text);
        if (!matcher.find()) {
            return "-";
        }
        return matcher.group(1) + " (" + matcher.group(2) + ")";
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
