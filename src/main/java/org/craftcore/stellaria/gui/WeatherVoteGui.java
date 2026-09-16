package org.craftcore.stellaria.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

/** 天気投票を開始するためのメニュー。投票処理は既存コマンドに委譲する。 */
public class WeatherVoteGui extends Gui {
    private final StellariaCore plugin;

    public WeatherVoteGui(StellariaCore plugin, Player player) {
        super(9, ColorUtil.component(plugin.getConfigManager().getMessage("weathervote.gui.title", player)));
        this.plugin = plugin;

        getInventory().setItem(2, button(Material.SUNFLOWER, "weathervote.gui.sunny", player));
        getInventory().setItem(4, button(Material.WATER_BUCKET, "weathervote.gui.rain", player));
        getInventory().setItem(6, button(Material.LIGHTNING_ROD, "weathervote.gui.thunder", player));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);

        String weather = switch (event.getRawSlot()) {
            case 2 -> "晴れ";
            case 4 -> "雨";
            case 6 -> "雷雨";
            default -> null;
        };
        if (weather == null) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        player.closeInventory();
        player.performCommand("weathervote " + weather);
    }

    private ItemStack button(Material material, String messagePath, Player player) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ColorUtil.component(plugin.getConfigManager().getMessage(messagePath, player)));
        item.setItemMeta(meta);
        return item;
    }
}
