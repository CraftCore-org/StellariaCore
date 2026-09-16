package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.gui.SettingsGui;
import org.jetbrains.annotations.NotNull;

/** /settings でプレイヤー個人の設定画面を開く。 */
public final class SettingsCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public SettingsCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("settings.must_be_player", null));
            return true;
        }
        if (args.length > 0) {
            player.sendMessage(plugin.getConfigManager().getMessage("settings.usage", player));
            return true;
        }

        new SettingsGui(plugin, player).open(player);
        return true;
    }
}
