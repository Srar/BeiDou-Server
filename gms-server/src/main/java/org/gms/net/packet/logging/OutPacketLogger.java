package org.gms.net.packet.logging;

import org.gms.config.GameConfig;
import org.gms.constants.net.OpcodeConstants;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.Packet;
import org.gms.net.packet.trace.PacketTraceBuffer;
import org.gms.net.packet.trace.PacketTraceConfig;
import org.gms.net.packet.trace.PacketTraceEntry;
import org.gms.net.packet.trace.PacketTraceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.util.HexTool;

import java.util.Set;

@Sharable
public class OutPacketLogger extends ChannelOutboundHandlerAdapter implements PacketLogger {
    private static final Logger log = LoggerFactory.getLogger(OutPacketLogger.class);
    private static final int LOG_CONTENT_THRESHOLD = 50_000;

    private static final Set<Short> IGNORED_SEND_PACKETS = Set.of(
            (short) SendOpcode.MOVE_PLAYER.getValue(),
            (short) SendOpcode.MOVE_MONSTER.getValue(),
            (short) SendOpcode.MOVE_MONSTER_RESPONSE.getValue(),
            (short) SendOpcode.MOVE_PET.getValue(),
            (short) SendOpcode.SHOW_STATUS_INFO.getValue(),
            (short) SendOpcode.DAMAGE_PLAYER.getValue(),
            (short) SendOpcode.SHOW_MONSTER_HP.getValue(),
            (short) SendOpcode.FACIAL_EXPRESSION.getValue(),
            (short) SendOpcode.SHOW_ITEM_GAIN_INCHAT.getValue());

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (GameConfig.getServerBoolean("use_debug_show_packet") && msg instanceof OutPacket packet) {
            log(packet);
        }

        // 每连接收发包环形记录：服务端→客户端
        if (PacketTraceConfig.enabled() && msg instanceof OutPacket packet) {
            byte[] content = packet.getBytes();
            if (content != null && content.length >= 2) {
                short opcode = LoggingUtil.readFirstShort(content);
                if (PacketTraceConfig.includeMove() || !isIgnoredSendPacket(opcode)) {
                    PacketTraceBuffer buffer = ctx.channel().attr(PacketTraceRegistry.CHANNEL_KEY).get();
                    if (buffer != null) {
                        buffer.add(PacketTraceEntry.of(PacketTraceEntry.DIR_SEND, content,
                                OpcodeConstants.sendOpcodeNames.get((int) opcode)));
                    }
                }
            }
        }

        ctx.write(msg);
    }

    private static boolean isIgnoredSendPacket(short opcode) {
        return IGNORED_SEND_PACKETS.contains(opcode);
    }

    @Override
    public void log(Packet packet) {
        final byte[] content = packet.getBytes();
        final int packetLength = content.length;

        if (packetLength <= LOG_CONTENT_THRESHOLD) {
            final short opcode = LoggingUtil.readFirstShort(content);
            String opcodeHex = Integer.toHexString(opcode).toUpperCase();
            String opcodeName = getSendOpcodeName(opcode);
            String prefix = opcodeName == null ? "<UnknownPacket> " : "";
            log.info("{}ServerSend:{} [{}] ({}) <HEX> {} <TEXT> {}", prefix, opcodeName, opcodeHex, packetLength,
                    HexTool.toHexString(content), HexTool.toStringFromCharset(content));
        } else {
            log.info("{} ...", HexTool.toHexString(new byte[]{content[0], content[1]}));
        }
    }

    private String getSendOpcodeName(short opcode) {
        return OpcodeConstants.sendOpcodeNames.get((int) opcode);
    }
}
