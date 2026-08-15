package org.gms.server.bot.freemarket;

import org.gms.client.Character;
import org.gms.server.maps.PlayerShopItem;

import java.awt.Point;

/**
 * 商店适配接口（逐行移植自 SoloMapling FreeMarket.ShopKeeper）。
 * gms 底座差异：HiredMerchant/PlayerShop 的原生 buy/chat 走 Client，见各自 adapter。
 */
public interface ShopKeeper {
    String getOwner();

    Point getPosition();

    void visitShop(Character fakechar);

    void chat(Character fakechar, String msg);

    void botBuyItem(Character fakechar, PlayerShopItem pItem, short quantity);

    void botBuyItemPlayerShop(Character fakechar, PlayerShopItem pItem, int itemPosition, short quantity);

    void removeVisitor(Character fakechar);
}
