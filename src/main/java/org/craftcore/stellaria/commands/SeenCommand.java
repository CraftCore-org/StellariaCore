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
 * /seen [プレイヤー] コマンド。引数なしで自分、プレイヤー指定で他人（要 stellaria.seen.others）の
 * 最終ログインを表示する。対象がオンライン中なら「現在オンライン」と表示する。
 */
public class SeenCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public SeenCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.seen")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("seen.no_permission", null));
            return true;
        }

        OfflinePlayer target;
        boolean self;
        if (args.length == 0) {
            if (!(sender instanceof Player selfPlayer)) {
                sender.sendMessage(plugin.getConfigManager().getMessage("seen.must_be_player", null));
                return true;
            }
            target = selfPlayer;
            self = true;
        } else {
            if (!sender.hasPermission("stellaria.seen.others")) {
                sender.sendMessage(plugin.getConfigManager().getMessage("seen.no_permission_others", null));
                return true;
            }
            target = Bukkit.getOfflinePlayer(args[0]);
            self = false;
        }

        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("seen.player_not_found", target));
            return true;
        }

        if (target.isOnline()) {
            String key = self ? "seen.online_now_self" : "seen.online_now_other";
            sender.sendMessage(plugin.getConfigManager().getMessage(key, target));
            return true;
        }

        long lastLogin = plugin.getPlaytimeManager().getLastLogin(target.getUniqueId());
        if (lastLogin <= 0) {
            sender.sendMessage(plugin.getConfigManager().getMessage("seen.unknown", target));
            return true;
        }

        String time = DurationParser.formatDuration(Math.max(0, (System.currentTimeMillis() - lastLogin) / 1000L));
        String key = self ? "seen.offline_self" : "seen.offline_other";
        sender.sendMessage(plugin.getConfigManager().getMessage(key, target).replace("%time%", time));
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
