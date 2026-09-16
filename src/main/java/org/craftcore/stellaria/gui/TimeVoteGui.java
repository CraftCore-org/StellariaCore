package org.craftcore.stellaria.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

/** 時間投票を開始するためのメニュー。投票処理は既存コマンドに委譲する。 */
public class TimeVoteGui extends Gui {
    private final StellariaCore plugin;

    public TimeVoteGui(StellariaCore plugin, Player player) {
        super(9, ColorUtil.component(plugin.getConfigManager().getMessage("timevote.gui.title", player)));
        this.plugin = plugin;

        getInventory().setItem(1, button(Material.SUNFLOWER, "timevote.gui.morning", player));
        getInventory().setItem(3, button(Material.CLOCK, "timevote.gui.noon", player));
        getInventory().setItem(5, button(Material.ORANGE_DYE, "timevote.gui.evening", player));
        getInventory().setItem(7, button(Material.BLACK_DYE, "timevote.gui.night", player));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);

        String time = switch (event.getRawSlot()) {
            case 1 -> "朝";
            case 3 -> "昼";
            case 5 -> "夕方";
            case 7 -> "夜";
            default -> null;
        };
        if (time == null) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        player.closeInventory();
        player.performCommand("timevote " + time);
    }

    private ItemStack button(Material material, String messagePath, Player player) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ColorUtil.component(plugin.getConfigManager().getMessage(messagePath, player)));
        item.setItemMeta(meta);
        return item;
    }
}
