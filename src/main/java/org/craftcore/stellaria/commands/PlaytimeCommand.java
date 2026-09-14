package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.DurationParser;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /playtime [プレイヤー] コマンド。引数なしで自分の累計プレイ時間、プレイヤー指定で他人の
 * 累計プレイ時間（要 stellaria.playtime.others）を表示する。
 */
public class PlaytimeCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public PlaytimeCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.playtime")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("playtime.no_permission", null));
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(plugin.getConfigManager().getMessage("playtime.must_be_player", null));
                return true;
            }
            String time = DurationParser.formatDuration(plugin.getPlaytimeManager().getPlaytimeSeconds(self.getUniqueId()));
            sender.sendMessage(plugin.getConfigManager().getMessage("playtime.self", self).replace("%time%", time));
            return true;
        }

        if (!sender.hasPermission("stellaria.playtime.others")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("playtime.no_permission_others", null));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("playtime.player_not_found", target));
            return true;
        }

        String time = DurationParser.formatDuration(plugin.getPlaytimeManager().getPlaytimeSeconds(target.getUniqueId()));
        sender.sendMessage(plugin.getConfigManager().getMessage("playtime.other", target).replace("%time%", time));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.knownPlayerNames(args[0]);
        }
        return List.of();
    }
}
