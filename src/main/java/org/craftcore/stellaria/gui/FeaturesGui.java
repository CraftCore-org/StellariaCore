package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.features.Feature;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.GuiItemUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** 購入できる便利機能を一覧表示し、購入または有効・無効の切替を行う画面。 */
public class FeaturesGui extends Gui {

    private final StellariaCore plugin;
    private final List<Feature> features;

    public FeaturesGui(StellariaCore plugin, List<Feature> features, Player player) {
        this(plugin, features, player, null);
    }

    /** メニュー画面から開く場合、戻るボタンを出すために親画面を渡す。 */
    public FeaturesGui(StellariaCore plugin, List<Feature> features, Player player, @Nullable Gui parent) {
        super(27, messageComponent(plugin, "features.gui_title", null), parent);
        this.plugin = plugin;
        this.features = List.copyOf(features);
        populate(player);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= getInventory().getSize()) {
            return;
        }
        if (handleBackButton(event, player)) {
            return;
        }

        int featureIndex = event.getRawSlot() - 10;
        if (featureIndex < 0 || featureIndex >= features.size()) {
            return;
        }

        Feature feature = features.get(featureIndex);
        if (feature.isUnlocked(player)) {
            boolean enabled = !feature.isEnabled(player.getUniqueId());
            feature.setEnabled(player, enabled);
            player.sendMessage(message("features." + (enabled ? "enabled" : "disabled"), player));
            populate(player);
            return;
        }

        Component title = messageComponent(plugin, "features.confirm_title", player);
        Component description = messageComponent(plugin, "features.confirm_description", player)
                .replaceText(builder -> builder.matchLiteral("%price%").replacement(Component.text(priceText(feature))));
        new ConfirmGui(title, description, () -> purchase(player, feature), null).open(player);
    }

    private void populate(Player player) {
        for (int index = 0; index < features.size() && index < 7; index++) {
            Feature feature = features.get(index);
            ItemStack item = GuiItemUtil.cleanIcon(feature.icon());
            ItemMeta meta = item.getItemMeta();
            meta.displayName(feature.displayName());

            List<Component> lore = new ArrayList<>();
            if (feature.isUnlocked(player)) {
                boolean enabled = feature.isEnabled(player.getUniqueId());
                lore.add(messageComponent(plugin, enabled ? "features.gui_enabled" : "features.gui_disabled", null));
                lore.add(messageComponent(plugin, "features.gui_toggle_hint", null));
            } else {
                lore.add(messageComponent(plugin, "features.gui_price", null)
                        .replaceText(builder -> builder.matchLiteral("%price%").replacement(Component.text(priceText(feature)))));
                lore.add(messageComponent(plugin, "features.gui_purchase_hint", null));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
            getInventory().setItem(10 + index, item);
        }
    }

    private void purchase(Player player, Feature feature) {
        switch (feature.purchase(player)) {
            case SUCCESS -> player.sendMessage(message("features.buy_success", player));
            case ALREADY_UNLOCKED -> player.sendMessage(message("features.already_unlocked", player));
            case INSUFFICIENT_FUNDS -> player.sendMessage(message("features.buy_insufficient_funds", player)
                    .replace("%price%", priceText(feature)));
            case DATABASE_ERROR -> player.sendMessage(message("features.buy_failed", player));
        }
    }

    private String priceText(Feature feature) {
        return plugin.getEconomyManager().format(feature.price());
    }

    private String message(String path, Player player) {
        return plugin.getConfigManager().getMessage(path, player);
    }

    private static Component messageComponent(StellariaCore plugin, String path, Player player) {
        return ColorUtil.component(plugin.getConfigManager().getMessage(path, player));
    }
}
