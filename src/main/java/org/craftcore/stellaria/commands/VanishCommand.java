package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.jetbrains.annotations.NotNull;

/** /vanish — 自分自身のvanish状態をトグルする。対象指定なし、自分専用。 */
public class VanishCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public VanishCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("vanish.must_be_player", null));
            return true;
        }

        boolean nowVanished = plugin.getVanishManager().toggle(player);
        String path = nowVanished ? "vanish.enabled" : "vanish.disabled";
        player.sendMessage(plugin.getConfigManager().getMessage(path, player));
        return true;
    }
}
