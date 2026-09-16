package org.craftcore.stellaria.features;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.managers.KikoriManager;

import java.util.UUID;

/** 既存の {@link KikoriManager} をFeatureとして公開する薄いアダプター。 */
public class KikoriFeature implements Feature {

    private final KikoriManager kikoriManager;

    public KikoriFeature(KikoriManager kikoriManager) {
        this.kikoriManager = kikoriManager;
    }

    @Override
    public String id() {
        return "kikori";
    }

    @Override
    public Component displayName() {
        return Component.text("木こり機能", NamedTextColor.GREEN);
    }

    @Override
    public Material icon() {
        return Material.IRON_AXE;
    }

    @Override
    public int price() {
        return kikoriManager.getPrice();
    }

    @Override
    public boolean isUnlocked(OfflinePlayer player) {
        return kikoriManager.isUnlocked(player);
    }

    @Override
    public boolean isEnabled(UUID uuid) {
        return kikoriManager.isEnabled(uuid);
    }

    @Override
    public void setEnabled(Player player, boolean enabled) {
        kikoriManager.setEnabled(player, enabled);
    }

    @Override
    public PurchaseResult purchase(Player player) {
        return switch (kikoriManager.purchase(player)) {
            case SUCCESS -> PurchaseResult.SUCCESS;
            case ALREADY_UNLOCKED -> PurchaseResult.ALREADY_UNLOCKED;
            case INSUFFICIENT_FUNDS -> PurchaseResult.INSUFFICIENT_FUNDS;
        };
    }
}
