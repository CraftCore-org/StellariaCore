package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.GuiItemUtil;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 所持金の公開設定を切り替える個人設定画面。 */
public final class SettingsGui extends Gui {

    private static final int BALANCE_VISIBILITY_SLOT = 13;

    private final StellariaCore plugin;
    private boolean hideBalance;

    public SettingsGui(StellariaCore plugin, Player player) {
        this(plugin, player, null);
    }

    /** メニュー画面などから開く場合に親画面を渡す。 */
    public SettingsGui(StellariaCore plugin, Player player, @Nullable Gui parent) {
        super(27, messageComponent(plugin, "settings.gui_title", player), parent);
        this.plugin = plugin;
        this.hideBalance = plugin.getEconomyManager().isHideBalance(player);
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
        if (event.getRawSlot() != BALANCE_VISIBILITY_SLOT) {
            return;
        }

        hideBalance = !hideBalance;
        plugin.getEconomyManager().setHideBalance(player, hideBalance);
        player.sendMessage(plugin.getConfigManager().getMessage(
                hideBalance ? "settings.balance_hidden_enabled" : "settings.balance_visible_enabled", player));
        populate(player);
    }

    private void populate(Player player) {
        ItemStack item = new ItemStack(hideBalance ? Material.GRAY_DYE : Material.LIME_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messageComponent(plugin, "settings.balance_visibility", player));
        meta.lore(List.of(
                messageComponent(plugin, hideBalance ? "settings.balance_hidden" : "settings.balance_visible", player),
                messageComponent(plugin, "settings.toggle_hint", player)
        ));
        item.setItemMeta(meta);
        getInventory().setItem(BALANCE_VISIBILITY_SLOT, item);
    }

    private static Component messageComponent(StellariaCore plugin, String path, Player player) {
        return GuiItemUtil.text(plugin.getConfigManager().getMessage(path, player));
    }
}
