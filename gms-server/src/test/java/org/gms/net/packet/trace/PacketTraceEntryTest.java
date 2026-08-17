package org.gms.net.packet.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 包记录条目构造测试：opcode 小端提取、短包安全、hex 截断。
 */
class PacketTraceEntryTest {

    @Test
    void extractsLittleEndianOpcodeFromFirstTwoBytes() {
        byte[] content = {0x19, 0x00, 0x01, 0x02}; // opcode = 0x0019
        PacketTraceEntry entry = PacketTraceEntry.of(PacketTraceEntry.DIR_RECV, content, "CLIENT_START_ERROR");
        assertEquals(0x19, entry.opcode());
        assertEquals("CLIENT_START_ERROR", entry.opcodeName());
        assertEquals(4, entry.length());
        assertEquals(PacketTraceEntry.DIR_RECV, entry.direction());
        assertTrue(entry.hex().startsWith("19 00 01 02"));
    }

    @Test
    void toleratesNullAndShortContent() {
        PacketTraceEntry nullEntry = PacketTraceEntry.of(PacketTraceEntry.DIR_SEND, null, null);
        assertEquals(0, nullEntry.opcode());
        assertEquals(0, nullEntry.length());
        assertNull(nullEntry.opcodeName());
        assertEquals("", nullEntry.hex());

        PacketTraceEntry oneByte = PacketTraceEntry.of(PacketTraceEntry.DIR_SEND, new byte[]{0x42}, null);
        assertEquals(0, oneByte.opcode());
        assertEquals(1, oneByte.length());
        assertEquals("42", oneByte.hex());
    }

    @Test
    void truncatesHexBeyondLimit() {
        byte[] big = new byte[PacketTraceEntry.MAX_HEX_BYTES + 10];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) i;
        }
        PacketTraceEntry entry = PacketTraceEntry.of(PacketTraceEntry.DIR_SEND, big, null);
        assertEquals(big.length, entry.length());
        assertTrue(entry.hex().endsWith("..."), "超出上限必须截断并标注");
        assertTrue(entry.hex().length() <= PacketTraceEntry.MAX_HEX_LENGTH);
    }

    @Test
    void hexIsUppercaseSpaceSeparated() {
        byte[] content = {(byte) 0xAB, (byte) 0xCD};
        PacketTraceEntry entry = PacketTraceEntry.of(PacketTraceEntry.DIR_SEND, content, null);
        assertEquals("AB CD", entry.hex());
    }
}
