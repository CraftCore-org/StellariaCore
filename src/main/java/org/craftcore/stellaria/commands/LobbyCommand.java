package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;
import org.jetbrains.annotations.NotNull;

/** 設定したロビーワールドのスポーン地点へ移動する `/lobby` コマンド。 */
public final class LobbyCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public LobbyCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("lobby.must-be-player", null));
            return true;
        }

        String worldName = configuredWorldName(plugin.getConfigManager().getString("lobby.world", "lobby"));
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("lobby.world-not-found", player), "%world%", worldName));
            return true;
        }

        player.teleportAsync(world.getSpawnLocation());
        return true;
    }

    static String configuredWorldName(String configured) {
        return configured == null || configured.isBlank() ? "lobby" : configured.trim();
    }
}
