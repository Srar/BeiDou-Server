package org.gms.server.bot.freemarket;

import org.gms.client.Character;
import org.gms.server.maps.HiredMerchant;

import java.awt.*;

/**
 * 移植自 SoloMapling FreeMarket.HiredMerchantArtificial（逐行照搬）。
 *
 * <p>移植说明（gms 底座差异）：
 * <ul>
 *   <li>源中 HiredMerchant 的 {@code ownerId}/{@code ownerName} 为 {@code protected}，
 *       子类可直接赋值；gms 中两者为 {@code private}，无法写入。故本类声明同名字段遮蔽
 *       父类字段，并覆盖 {@link #getOwnerId()} / {@link #getOwner()}，使外部可见语义与源
 *       完全一致。父类内部路径（如 {@link HiredMerchant#buy} 的卖家结算）读的是父类私有
 *       字段，因此调用方必须保证构造入参 owner 的 id/name 与传入的 id/name 一致——
 *       {@code ArtificialFreeMarket.spawnHiredMerchantStore} 在构造前已
 *       {@code owner.setId(id)} + {@code owner.setName(name)}（漏设 name 会使父类 ownerName
 *       为 null，真实玩家购买时 getCharacterByName(null) NPE）。</li>
 *   <li>gms 构造器签名与源相同：{@code HiredMerchant(Character owner, String desc, int itemId)}。</li>
 * </ul>
 *
 * <p>gms 创建人工摊主实例的方式（owner 无需真实在线角色）：
 * <pre>{@code
 * Character owner = Character.getDefault(Client.createMock());
 * HiredMerchantArtificial merchant = new HiredMerchantArtificial(owner, description, shopItemId, ownerId, ownerName);
 * }</pre>
 */
public class HiredMerchantArtificial extends HiredMerchant {

    public enum shopTypes {
        Common,
        Warrior,
        Mage,
        Bowman,
        Thief,
        Pirate,
        Potion,
        Scroll,
        DarkScroll,
        Mastery,
        Chair,
        ETC,
        Stars
    }

    private String tier = "";
    private int mapId = 0;
    private shopTypes primary = null;
    private shopTypes secondary = null;
    private shopTypes tertiary = null;

    // 遮蔽父类 private ownerId/ownerName（gms 中父类字段不可写），getter 见下方覆盖
    private int ownerId;
    private String ownerName = "";

    public HiredMerchantArtificial(Character owner, String desc, int itemId, int id, String name) {
        super(owner, desc, itemId);

        this.ownerId = id;
        this.ownerName = name;
    }

    @Override
    public int getOwnerId() {
        return this.ownerId;
    }

    @Override
    public String getOwner() {
        return this.ownerName;
    }

    public Point getPos() {
        return getPosition();
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public String getTier() {
        return this.tier;
    }

    public void setMapId(int newMapId) {
        this.mapId = newMapId;
    }

    public int getMapIdArtificial() {
        return this.mapId;
    }

    public int getRoomNumber() {
        return this.mapId % 100;
    }

    public shopTypes getPrimary() {
        return this.primary;
    }

    public void setPrimary(shopTypes shopType) {
        this.primary = shopType;
    }

    public shopTypes getSecondary() {
        return this.secondary;
    }

    public void setSecondary(shopTypes shopType) {
        this.secondary = shopType;
    }

    public shopTypes getTertiary() {
        return this.tertiary;
    }

    public void setTertiary(shopTypes shopType) {
        this.tertiary = shopType;
    }


}
