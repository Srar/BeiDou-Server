package org.gms.net.packet.trace;

/**
 * 包记录条目：单条收/发包的元信息 + 内容快照（gms 增强，崩溃诊断用）。
 * <p>
 * hex 为包体 hex 字符串（大写、空格分隔），单条上限 {@link #MAX_HEX_BYTES} 字节，
 * 超出部分截断并在末尾追加 "..."——防超大包把环形缓冲撑爆。
 */
public record PacketTraceEntry(
        long timeMillis,
        byte direction,
        short opcode,
        String opcodeName,
        int length,
        String hex) {

    /** 方向常量：S = server → client（出站），C = client → server（入站）。 */
    public static final byte DIR_SEND = 'S';
    public static final byte DIR_RECV = 'C';

    /** 单条 hex 快照的最大字节数（超出截断，控制单条内存占用）。 */
    public static final int MAX_HEX_BYTES = 256;

    /** 截断后的 hex 字符串长度估算上限（每字节 3 字符 + 尾部标记）。 */
    public static final int MAX_HEX_LENGTH = MAX_HEX_BYTES * 3 + 4;

    /**
     * 由原始包字节构造条目。opcode 取前 2 字节（小端），名称由调用方经
     * OpcodeConstants 映射传入（可为 null，导出时显示 hex 值）。
     */
    public static PacketTraceEntry of(byte direction, byte[] content, String opcodeName) {
        short opcode = 0;
        if (content != null && content.length >= 2) {
            opcode = (short) ((content[0] & 0xFF) | ((content[1] & 0xFF) << 8));
        }
        String hex;
        if (content == null || content.length == 0) {
            hex = "";
        } else if (content.length <= MAX_HEX_BYTES) {
            hex = toHex(content);
        } else {
            byte[] head = new byte[MAX_HEX_BYTES];
            System.arraycopy(content, 0, head, 0, MAX_HEX_BYTES);
            hex = toHex(head) + "...";
        }
        return new PacketTraceEntry(System.currentTimeMillis(), direction, opcode,
                opcodeName == null || opcodeName.isEmpty() ? null : opcodeName,
                content == null ? 0 : content.length, hex);
    }

    /** 大写 hex 查表（避免逐字节 String.format 的开销）。 */
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(HEX_DIGITS[(bytes[i] >> 4) & 0xF]).append(HEX_DIGITS[bytes[i] & 0xF]);
        }
        return sb.toString();
    }
}
