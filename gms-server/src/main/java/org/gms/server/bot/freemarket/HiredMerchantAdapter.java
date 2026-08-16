package org.gms.server.bot.freemarket;

import org.gms.client.Character;
import org.gms.server.maps.HiredMerchant;
import org.gms.server.maps.PlayerShopItem;
import org.gms.util.PacketCreator;

import java.awt.Point;

/**
 * HiredMerchant 适配器（逐行移植自 SoloMapling FreeMarket.HiredMerchantAdapter）。
 * gms 底座差异：bot 共用 headless client（getPlayer() 恒为 null），购买走
 * HiredMerchant.botBuy(Character, ...) 虚拟买家结算（对齐 SoloMapling 同名方法）。
 */
public class HiredMerchantAdapter implements ShopKeeper {
    private final HiredMerchant merchant;

    public HiredMerchantAdapter(HiredMerchant merchant) {
        this.merchant = merchant;
    }

    @Override
    public String getOwner() {
        return merchant.getOwner();
    }

    @Override
    public Point getPosition() {
        return merchant.getPosition();
    }

    @Override
    public void visitShop(Character fakechar) {
        getMerchant().visitShop(fakechar);
    }

    @Override
    public void chat(Character fakechar, String msg) {
        getMerchant().sendMessage(fakechar, msg);
    }

    @Override
    public void removeVisitor(Character fakechar) {
        getMerchant().removeVisitor(fakechar);
    }

    @Override
    public void botBuyItem(Character fakechar, PlayerShopItem pItem, short quantity) {
        getMerchant().botBuy(fakechar, pItem, quantity);
        getMerchant().broadcastToVisitorsThreadsafe(PacketCreator.updateHiredMerchant(getMerchant(), fakechar));
    }

    @Override
    public void botBuyItemPlayerShop(Character fakechar, PlayerShopItem pItem, int itemPosition, short quantity) {
        // HiredMerchant only
    }

    // Helper method to get the original merchant if needed
    public HiredMerchant getMerchant() {
        return merchant;
    }
}
