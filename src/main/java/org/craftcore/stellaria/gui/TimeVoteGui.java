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
        this(plugin, player, null);
    }

    public TimeVoteGui(StellariaCore plugin, Player player, Gui parent) {
        super(27, ColorUtil.component(plugin.getConfigManager().getMessage("timevote.gui.title", player)), parent);
        this.plugin = plugin;

        getInventory().setItem(10, button(Material.SUNFLOWER, "timevote.gui.morning", player));
        getInventory().setItem(12, button(Material.CLOCK, "timevote.gui.noon", player));
        getInventory().setItem(14, button(Material.ORANGE_DYE, "timevote.gui.evening", player));
        getInventory().setItem(16, button(Material.BLACK_DYE, "timevote.gui.night", player));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();
        if (handleBackButton(event, player)) {
            return;
        }

        String time = switch (event.getRawSlot()) {
            case 10 -> "朝";
            case 12 -> "昼";
            case 14 -> "夕方";
            case 16 -> "夜";
            default -> null;
        };
        if (time == null) {
            return;
        }

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
