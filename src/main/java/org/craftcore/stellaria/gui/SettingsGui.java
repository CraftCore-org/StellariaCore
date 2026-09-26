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

/** 所持金の公開設定と統計ランキングへの掲載を切り替える個人設定画面。 */
public final class SettingsGui extends Gui {

    private static final int BALANCE_VISIBILITY_SLOT = 11;
    private static final int STATS_RANKING_VISIBILITY_SLOT = 15;

    private final StellariaCore plugin;
    private boolean hideBalance;
    private boolean hideStatsRanking;

    public SettingsGui(StellariaCore plugin, Player player) {
        this(plugin, player, null);
    }

    /** メニュー画面などから開く場合に親画面を渡す。 */
    public SettingsGui(StellariaCore plugin, Player player, @Nullable Gui parent) {
        super(27, messageComponent(plugin, "settings.gui_title", player), parent);
        this.plugin = plugin;
        this.hideBalance = plugin.getEconomyManager().isHideBalance(player);
        this.hideStatsRanking = plugin.getStatSnapshotManager().isHidden(player);
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
        if (event.getRawSlot() == BALANCE_VISIBILITY_SLOT) {
            boolean newValue = !hideBalance;
            if (!plugin.getEconomyManager().setHideBalance(player, newValue)) {
                player.sendMessage(plugin.getConfigManager().getMessage("settings.database_error", player));
                return;
            }
            hideBalance = newValue;
            player.sendMessage(plugin.getConfigManager().getMessage(
                    hideBalance ? "settings.balance_hidden_enabled" : "settings.balance_visible_enabled", player));
        } else if (event.getRawSlot() == STATS_RANKING_VISIBILITY_SLOT) {
            boolean newValue = !hideStatsRanking;
            if (!plugin.getStatSnapshotManager().setHidden(player, newValue)) {
                player.sendMessage(plugin.getConfigManager().getMessage("settings.database_error", player));
                return;
            }
            hideStatsRanking = newValue;
            player.sendMessage(plugin.getConfigManager().getMessage(
                    hideStatsRanking ? "settings.stats_ranking_hidden_enabled" : "settings.stats_ranking_visible_enabled", player));
        } else {
            return;
        }
        populate(player);
    }

    private void populate(Player player) {
        getInventory().setItem(BALANCE_VISIBILITY_SLOT, toggleItem(player, hideBalance,
                "settings.balance_visibility", "settings.balance_visible", "settings.balance_hidden"));
        getInventory().setItem(STATS_RANKING_VISIBILITY_SLOT, toggleItem(player, hideStatsRanking,
                "settings.stats_ranking_visibility", "settings.stats_ranking_visible", "settings.stats_ranking_hidden"));
    }

    private ItemStack toggleItem(Player player, boolean hidden, String nameKey, String visibleKey, String hiddenKey) {
        ItemStack item = new ItemStack(hidden ? Material.GRAY_DYE : Material.LIME_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messageComponent(plugin, nameKey, player));
        meta.lore(List.of(
                messageComponent(plugin, hidden ? hiddenKey : visibleKey, player),
                messageComponent(plugin, "settings.toggle_hint", player)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static Component messageComponent(StellariaCore plugin, String path, Player player) {
        return GuiItemUtil.text(plugin.getConfigManager().getMessage(path, player));
    }
}
