package org.gms.server.bot.commands;

import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.util.PacketCreator;

/**
 * 视觉特效命令（SoloMapling BotCommandsPack.VFXCommands 逐行移植）。
 * <p>
 * 底座差异：无录制引擎的 {@code createIdleStandlingPacket} 静态导入（源内未实际使用）。
 */
public class VFXCommands {

    public static void botScroll(Character fakechar, Equip.ScrollResult result) {
        fakechar.getMap().broadcastMessage(PacketCreator.getScrollEffect(fakechar.getId(), result, false, false));
    }

    public static void botScrollSuccess(Character fakechar) {
        botScroll(fakechar, Equip.ScrollResult.SUCCESS);
    }

    public static void botScrollFail(Character fakechar) {
        botScroll(fakechar, Equip.ScrollResult.CURSE);
    }

}
