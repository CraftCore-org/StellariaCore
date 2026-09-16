package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.gui.MenuGui;
import org.jetbrains.annotations.NotNull;

/**
 * /menu コマンド。サーバー内の各機能への入口をまとめた総合メニューを開く。
 * 専用権限は無し（誰でも実行可）。
 */
public class MenuCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public MenuCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("menu.must_be_player", null));
            return true;
        }

        new MenuGui(plugin, player).open(player);
        return true;
    }
}
