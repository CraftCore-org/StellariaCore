package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.jetbrains.annotations.NotNull;

/**
 * /afk コマンド。実行するたびにAFK状態をトグルする。
 */
public class AfkCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public AfkCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("afk.must_be_player", null));
            return true;
        }
        if (!player.hasPermission("stellaria.afk")) {
            player.sendMessage(plugin.getConfigManager().getMessage("afk.no_permission", player));
            return true;
        }

        boolean currentlyAfk = plugin.getAfkManager().isAfk(player.getUniqueId());
        plugin.getAfkManager().setAfk(player, !currentlyAfk);
        return true;
    }
}
