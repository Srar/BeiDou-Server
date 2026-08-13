package org.gms.server.bot.types;

import org.gms.client.Character;
import org.gms.server.bot.BotSM;

/**
 * 最小生命周期示范类型：除基类骨架外不追加任何行为——纯粹站立，
 * 用于验证「创建 → 注册 → tick → 拆卸」全链路与观察调速。
 */
public class IdleBot extends BotSM {

    public IdleBot(Character character) {
        super(character);
        this.botType = "IDLE_BOT";
    }

    @Override
    public void updateState() {
        super.updateState();
        // 无子状态机：IDLE_BOT 只承载基类义务（掉线拆卸、观察调速）
    }
}
