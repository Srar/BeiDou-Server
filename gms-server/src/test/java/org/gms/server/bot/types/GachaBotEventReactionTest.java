package org.gms.server.bot.types;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.packet.Packet;
import org.gms.server.bot.event.GameEvent;
import org.gms.server.maps.MapleMap;
import org.gms.test.BotTestSupport;
import org.gms.util.PacketCreator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.awt.Point;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * GachaBot 事件反应：handleEvent 对 SCROLLING / GACHAPON_REWARD 的分发与反应
 * （成功/失败卷轴的表情+台词、扭蛋开奖反应），以及升级庆祝录制品缺失时的兜底。
 * <p>
 * 表情/气泡经 BotGameSupport 广播到 map：捕获 broadcastMessage 的 Packet 与
 * PacketCreator 期望输出逐字节比对，验证表情码与台词文本。
 */
class GachaBotEventReactionTest {

    private static final int BOT_ID = 965_000_100;
    private static final int MAP_ID = 100000000;
    private static final int PLAYER_ID = 777_777;
    private static final String PLAYER_NAME = "Tester";

    private Character chr;
    private MapleMap map;
    private Character targetPlayer;
    private GachaBot bot;

    @BeforeAll
    static void initSupport() {
        BotTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        chr = Mockito.mock(Character.class);
        Mockito.when(chr.getId()).thenReturn(BOT_ID);
        Mockito.when(chr.getName()).thenReturn("GachaBot");
        Mockito.when(chr.getWorld()).thenReturn(0);
        Mockito.when(chr.getPosition()).thenReturn(new Point(0, 0));
        Mockito.when(chr.getMapId()).thenReturn(MAP_ID);

        Client client = Mockito.mock(Client.class);
        Mockito.when(client.getChannel()).thenReturn(1);
        Mockito.when(chr.getClient()).thenReturn(client);

        map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getId()).thenReturn(MAP_ID);

        // resolvePlayerName 遍历同图角色找 sourceCharacterId 对应的真人名
        targetPlayer = Mockito.mock(Character.class);
        Mockito.when(targetPlayer.getId()).thenReturn(PLAYER_ID);
        Mockito.when(targetPlayer.getName()).thenReturn(PLAYER_NAME);
        Mockito.when(map.getCharacters()).thenReturn(List.of(targetPlayer));

        Mockito.when(chr.getMap()).thenReturn(map);

        bot = new GachaBot(chr);
    }

    @Test
    void scrollingSuccessEmitsCongratulationEmoteAndBubble() {
        bot.handleEvent(GameEvent.scrolling(0, 1, MAP_ID, PLAYER_ID, true));

        ArgumentCaptor<Packet> packets = ArgumentCaptor.forClass(Packet.class);
        verify(map, times(2)).broadcastMessage(packets.capture());
        assertPacketEquals(PacketCreator.facialExpression(chr, 3), packets.getAllValues().get(0), "成功卷轴应发表情 3");
        assertPacketEquals(PacketCreator.getChatText(BOT_ID, "卷轴成了 " + PLAYER_NAME + "，好手气！", false, 1),
                packets.getAllValues().get(1), "成功卷轴台词不正确");
    }

    @Test
    void scrollingFailureEmitsConsolationEmoteAndBubble() {
        bot.handleEvent(GameEvent.scrolling(0, 1, MAP_ID, PLAYER_ID, false));

        ArgumentCaptor<Packet> packets = ArgumentCaptor.forClass(Packet.class);
        verify(map, times(2)).broadcastMessage(packets.capture());
        assertPacketEquals(PacketCreator.facialExpression(chr, 4), packets.getAllValues().get(0), "失败卷轴应发表情 4");
        assertPacketEquals(PacketCreator.getChatText(BOT_ID, "哎呀 " + PLAYER_NAME + "，卷轴炸了…", false, 1),
                packets.getAllValues().get(1), "失败卷轴台词不正确");
    }

    @Test
    void gachaponRewardEmitsReactionEmoteAndBubble() {
        bot.handleEvent(GameEvent.gachaponReward(0, 1, MAP_ID, PLAYER_ID));

        ArgumentCaptor<Packet> packets = ArgumentCaptor.forClass(Packet.class);
        verify(map, times(2)).broadcastMessage(packets.capture());
        assertPacketEquals(PacketCreator.facialExpression(chr, 2), packets.getAllValues().get(0), "扭蛋开奖应发表情 2");
        assertPacketEquals(PacketCreator.getChatText(BOT_ID, "运气爆棚 " + PLAYER_NAME + "！", false, 1),
                packets.getAllValues().get(1), "扭蛋开奖台词不正确");
    }

    @Test
    void unknownEventTypeIsIgnored() {
        bot.handleEvent(GameEvent.chat(0, 1, MAP_ID, PLAYER_ID, "hello"));

        verify(map, never()).broadcastMessage(any(Packet.class));
    }

    @Test
    void missingCelebrationRecordingFallsBackWithoutThrow() throws Exception {
        // 录制品缺失时 playCelebrationRecording 只告警不抛异常，庆祝流程由表情+气泡兜底
        Method play = GachaBot.class.getDeclaredMethod("playCelebrationRecording", String.class);
        play.setAccessible(true);
        assertDoesNotThrow(() -> play.invoke(bot, "definitely-missing-recording-xyz"),
                "录制品缺失时应吞掉异常并回退兜底");
    }

    private static void assertPacketEquals(Packet expected, Packet actual, String message) {
        assertTrue(Arrays.equals(expected.getBytes(), actual.getBytes()), message
                + " (expected=" + Arrays.toString(expected.getBytes())
                + " actual=" + Arrays.toString(actual.getBytes()) + ")");
    }
}
