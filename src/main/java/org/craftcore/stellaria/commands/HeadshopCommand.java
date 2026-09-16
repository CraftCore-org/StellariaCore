package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.gui.HeadshopAdminGui;
import org.craftcore.stellaria.gui.HeadshopGui;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /headshop 本体（メインGUIを開く。基本権限 stellaria.headshop は plugin.yml の permission
 * 指定でBukkitに自動チェックさせる）と /headshop admin（管理者GUIを開く。こちらは
 * サブコマンド固有の権限 stellaria.headshop.admin をこのクラス内で手動チェックする）を扱う。
 */
public class HeadshopCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public HeadshopCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("headshop.must_be_player", null));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("admin")) {
            if (!player.hasPermission("stellaria.headshop.admin")) {
                player.sendMessage(plugin.getConfigManager().getMessage("headshop.no_permission", player));
                return true;
            }
            new HeadshopAdminGui(plugin).open(player);
            return true;
        }

        new HeadshopGui(plugin, player).open(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(List.of("admin"), args[0]);
        }
        return List.of();
    }
}
