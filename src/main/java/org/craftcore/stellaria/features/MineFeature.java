package org.craftcore.stellaria.features;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.managers.MineManager;

import java.util.UUID;

/** 既存の {@link MineManager} をFeatureとして公開する薄いアダプター。 */
public class MineFeature implements Feature {

    private final MineManager mineManager;

    public MineFeature(MineManager mineManager) {
        this.mineManager = mineManager;
    }

    @Override
    public String id() {
        return "mine";
    }

    @Override
    public Component displayName() {
        return Component.text("鉱石一括破壊機能", NamedTextColor.AQUA);
    }

    @Override
    public Material icon() {
        return Material.IRON_PICKAXE;
    }

    @Override
    public int price() {
        return mineManager.getPrice();
    }

    @Override
    public boolean isUnlocked(OfflinePlayer player) {
        return mineManager.isUnlocked(player);
    }

    @Override
    public boolean isEnabled(UUID uuid) {
        return mineManager.isEnabled(uuid);
    }

    @Override
    public void setEnabled(Player player, boolean enabled) {
        mineManager.setEnabled(player, enabled);
    }

    @Override
    public PurchaseResult purchase(Player player) {
        return switch (mineManager.purchase(player)) {
            case SUCCESS -> PurchaseResult.SUCCESS;
            case ALREADY_UNLOCKED -> PurchaseResult.ALREADY_UNLOCKED;
            case INSUFFICIENT_FUNDS -> PurchaseResult.INSUFFICIENT_FUNDS;
        };
    }
}
