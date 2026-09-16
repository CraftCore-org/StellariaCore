package org.craftcore.stellaria.features;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/** 購入できる便利機能をGUIとコマンドから共通して扱うための最小限の契約。 */
public interface Feature {

    String id();

    Component displayName();

    Material icon();

    int price();

    boolean isUnlocked(OfflinePlayer player);

    boolean isEnabled(UUID uuid);

    void setEnabled(Player player, boolean enabled);

    PurchaseResult purchase(Player player);

    enum PurchaseResult {
        SUCCESS,
        ALREADY_UNLOCKED,
        INSUFFICIENT_FUNDS
    }
}
