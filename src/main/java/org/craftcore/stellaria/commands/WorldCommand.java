package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.gui.WorldSelectGui;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /world [ワールド名] でワールド選択またはスポーン地点への移動を行う。 */
public class WorldCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public WorldCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.world")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("world.no_permission", null));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("world.must_be_player", null));
            return true;
        }

        if (args.length == 0) {
            new WorldSelectGui(plugin).open(player);
            return true;
        }

        World world = Bukkit.getWorld(args[0]);
        if (world == null) {
            player.sendMessage(plugin.getConfigManager().getMessage("world.not_found", player));
            return true;
        }

        player.teleportAsync(world.getSpawnLocation());
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length != 1) {
            return List.of();
        }
        return TabCompleteUtil.filterStartsWith(Bukkit.getWorlds().stream().map(World::getName).toList(), args[0]);
    }
}
