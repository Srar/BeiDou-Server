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
 *       子类可直接赋值；gms 中 {@code ownerId} 为 {@code private final}、{@code ownerName} 为
 *       {@code private}，无法写入。故本类声明同名字段遮蔽父类字段，并覆盖
 *       {@link #getOwnerId()} / {@link #getOwner()}，使外部可见语义与源完全一致
 *       （父类内部持久化等路径仍使用构造时传入 owner 的 id，属于不可避免的差异）。</li>
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
