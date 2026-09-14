package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.craftcore.stellaria.StellariaCore;
import org.jetbrains.annotations.NotNull;

/**
 * /stellariareload コマンド。config.yml をサーバー再起動なしで読み直す。
 */
public class ReloadCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public ReloadCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.isOp()) {
            sender.sendMessage("§c§l| §7このコマンドを実行する権限がありません。");
            return true;
        }

        plugin.getConfigManager().reload();
        plugin.reloadFeatureManagers();
        sender.sendMessage("§a§l| §7設定ファイルを再読み込みしました。");
        return true;
    }
}
