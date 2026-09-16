package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.jetbrains.annotations.NotNull;

/**
 * /enderchest（alias /ec）コマンド。本物のエンダーチェストを開く。
 * 専用権限は無し（誰でも実行可）。
 */
public class EnderChestCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public EnderChestCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("enderchest.must_be_player", null));
            return true;
        }

        player.openInventory(player.getEnderChest());
        return true;
    }
}
