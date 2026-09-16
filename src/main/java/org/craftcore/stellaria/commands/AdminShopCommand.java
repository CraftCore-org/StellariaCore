package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.gui.AdminShopGui;
import org.jetbrains.annotations.NotNull;

/** /adminshop コマンド。運営物資の販売GUIを開く。 */
public class AdminShopCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public AdminShopCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (!plugin.getConfigManager().getBoolean("adminshop.enabled", true)) {
            player.sendMessage(plugin.getConfigManager().getMessage("adminshop.disabled", player));
            return true;
        }

        new AdminShopGui(plugin, player).open(player);
        return true;
    }
}
