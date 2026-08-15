package org.gms.server.bot.dialogue;

/**
 * 一个 bot 可执行的氛围表达动作，及其相对选取权重。
 * 权重为起点（未来可经 BotFlavor 的调参常量 / 环境变量调整）。
 * EMOTE 最便宜且最通用，权重最高；技能/增益摆拍是较稀有的点缀。
 */
public enum FlavorAction {
    EMOTE(5),
    BUFF_FLEX(3),
    SKILL_SWING(3);

    public final int weight;

    FlavorAction(int weight) {
        this.weight = weight;
    }
}
