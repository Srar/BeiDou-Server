package org.gms.server.bot.trade;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.server.Trade;

@Slf4j
public class BotTradeHandler {

    Character chr;
    Character tradePartner;

    public BotTradeHandler(Character chr) {
        this.chr = chr;
    }

    public void setTradePartner(Character player) {
        this.tradePartner = player;
    }

    public void resetTradePartner() {
        setTradePartner(null);
    }

    protected Character getTradePartnerConfirmed() {
        // gms 增强（F7）：源的 try/catch 包住一个纯字段访问（从不抛异常），属多余
        // 异常表字节码；直接返回，语义不变（null = 无已确认伙伴）。
        return this.tradePartner;
    }

    public Character getTradePartnerRaw() {
        // gms 增强（F7）：源用 try/catch 吞掉无交易路径的 NPE——每个宏 tick 构造
        // 约 2 个异常（栈填充）是 2核4G 数千 bot 的固定浪费。改为显式判空短路：
        // 无交易（getTrade()==null）或对方未确认（getPartner()==null）时直接返回
        // null，返回语义与源一致（null = 无交易伙伴），但不抛异常。
        Trade trade = chr.getTrade();
        if (trade == null) {
            return null;
        }
        Trade partner = trade.getPartner();
        if (partner == null) {
            return null;
        }
        return partner.getChr();
    }

    public boolean verifyTradePartner() {
        // gms 增强（F7）：源对 getTradePartnerRaw()/getTradePartnerConfirmed() 各调用
        // 3 次（每次构造 try/catch 异常表），改为局部变量单次求值 + 显式判空短路；
        // 返回语义与源完全一致（无交易伙伴 = false），且不再依赖 try/catch NPE。
        Character raw = getTradePartnerRaw();
        Character confirmed = getTradePartnerConfirmed();
        if (raw == null || confirmed == null || !verifyPlayerOnSameMap(confirmed)) {
            return false;
        }
        return true;
    }

    protected boolean insideAcceptedTrade() {
//        if ()
        return true;
    }

    protected boolean verifyPlayerOnSameMap(Character player) {
        return player.getMapId() == chr.getMapId();
    }

}
