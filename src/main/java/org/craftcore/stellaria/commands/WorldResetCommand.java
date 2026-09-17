package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /worldreset now <world> — スケジュールを待たずに即座にワールドを退避・リセットする管理者コマンド。 */
public class WorldResetCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public WorldResetCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.worldreset")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("world-reset.no_permission", null));
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("now")) {
            sender.sendMessage(plugin.getConfigManager().getUsageMessage("world-reset.usage_now", null));
            return true;
        }

        String worldName = args[1];
        boolean started = plugin.getWorldResetManager().resetNow(worldName);
        if (!started) {
            sender.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("world-reset.not_a_target", null), "%world%", worldName));
            return true;
        }
        sender.sendMessage(FormatUtil.replace(
                plugin.getConfigManager().getMessage("world-reset.reset_now_started", null), "%world%", worldName));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(List.of("now"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("now")) {
            return TabCompleteUtil.filterStartsWith(
                    plugin.getConfigManager().getStringList("world-reset.worlds"), args[1]);
        }
        return List.of();
    }
}
