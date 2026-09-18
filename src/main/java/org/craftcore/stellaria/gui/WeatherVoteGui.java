package org.craftcore.stellaria.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import org.craftcore.stellaria.utils.GuiItemUtil;

/** 天気投票を開始するためのメニュー。投票処理は既存コマンドに委譲する。 */
public class WeatherVoteGui extends Gui {
    private final StellariaCore plugin;

    public WeatherVoteGui(StellariaCore plugin, Player player) {
        this(plugin, player, null);
    }

    public WeatherVoteGui(StellariaCore plugin, Player player, Gui parent) {
        super(27, ColorUtil.component(plugin.getConfigManager().getMessage("weathervote.gui.title", player)), parent);
        this.plugin = plugin;

        getInventory().setItem(11, button(Material.SUNFLOWER, "weathervote.gui.sunny", player));
        getInventory().setItem(13, button(Material.WATER_BUCKET, "weathervote.gui.rain", player));
        getInventory().setItem(15, button(Material.LIGHTNING_ROD, "weathervote.gui.thunder", player));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();
        if (handleBackButton(event, player)) {
            return;
        }

        String weather = switch (event.getRawSlot()) {
            case 11 -> "晴れ";
            case 13 -> "雨";
            case 15 -> "雷雨";
            default -> null;
        };
        if (weather == null) {
            return;
        }

        player.closeInventory();
        player.performCommand("weathervote " + weather);
    }

    private ItemStack button(Material material, String messagePath, Player player) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(GuiItemUtil.text(plugin.getConfigManager().getMessage(messagePath, player)));
        item.setItemMeta(meta);
        return item;
    }
}
