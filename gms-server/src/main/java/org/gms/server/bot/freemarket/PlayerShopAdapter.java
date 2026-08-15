package org.gms.server.bot.freemarket;

import org.gms.client.Character;
import org.gms.server.maps.PlayerShop;
import org.gms.server.maps.PlayerShopItem;
import org.gms.util.PacketCreator;

import java.awt.Point;

/**
 * PlayerShop 适配器（逐行移植自 SoloMapling FreeMarket.PlayerShopAdapter）。
 * gms 底座差异：chat 与 buy 均以 Client 为入参。
 */
public class PlayerShopAdapter implements ShopKeeper {
    private final PlayerShop shop;

    public PlayerShopAdapter(PlayerShop shop) {
        this.shop = shop;
    }

    @Override
    public String getOwner() {
        return shop.getOwner().getName();
    }

    @Override
    public Point getPosition() {
        return shop.getPosition();
    }

    @Override
    public void visitShop(Character fakechar) {
        getShop().visitShop(fakechar);
    }

    @Override
    public void chat(Character fakechar, String msg) {
        getShop().chat(fakechar.getClient(), msg);
    }

    @Override
    public void removeVisitor(Character fakechar) {
        getShop().removeVisitor(fakechar);
    }

    @Override
    public void botBuyItem(Character fakechar, PlayerShopItem pItem, short quantity) {
        // PlayerShop only
    }

    @Override
    public void botBuyItemPlayerShop(Character fakechar, PlayerShopItem pItem, int itemPosition, short quantity) {
        getShop().buy(fakechar.getClient(), itemPosition, quantity);
        getShop().broadcast(PacketCreator.getPlayerShopItemUpdate(getShop()));
    }

    // Helper method to get the original shop if needed
    public PlayerShop getShop() {
        return shop;
    }
}
