package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.MenuItemUtil;
import org.jetbrains.annotations.NotNull;

/**
 * /menuitem コマンド。/menu を開くためのコンパスを付与する。
 * 専用権限は無し（誰でも実行可、無くしたり手放したりしても再入手できる）。
 */
public class MenuItemCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public MenuItemCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("menuitem.must_be_player", null));
            return true;
        }

        MenuItemUtil.give(plugin, player);
        return true;
    }
}
