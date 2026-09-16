package org.craftcore.stellaria.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.player.PlayerInteractEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.gui.MenuGui;
import org.craftcore.stellaria.utils.MenuItemUtil;

/** メニューアイテム（コンパス）を使用（右クリック/左クリック）したら /menu を開く。 */
public class MenuItemListener implements Listener {

    private final StellariaCore plugin;

    public MenuItemListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() == Action.PHYSICAL) {
            return;
        }
        if (!MenuItemUtil.isMenuItem(plugin, event.getItem())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        new MenuGui(plugin, player).open(player);
    }
}
