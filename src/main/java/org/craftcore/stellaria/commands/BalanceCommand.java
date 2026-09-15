package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.EconomyManager;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /balance（alias money, bal）コマンド。引数なしで自分の残高、プレイヤー指定で他人の残高
 * （要 stellaria.balance.others）を表示する。ランキング表示は /ranking money に移行済み。
 */
public class BalanceCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public BalanceCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.balance")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("balance.no_permission", null));
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(plugin.getConfigManager().getMessage("balance.must_be_player", null));
                return true;
            }
            showBalance(sender, self, "balance.self");
            return true;
        }

        if (!sender.hasPermission("stellaria.balance.others")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("balance.no_permission_others", null));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("balance.player_not_found", target));
            return true;
        }
        showBalance(sender, target, "balance.other");
        return true;
    }

    private void showBalance(CommandSender sender, OfflinePlayer target, String messageKey) {
        EconomyManager economy = plugin.getEconomyManager();
        String amountText = economy.formatExact(economy.getBalance(target));
        sender.sendMessage(plugin.getConfigManager().getMessage(messageKey, target)
                .replace("%amount%", amountText));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.onlinePlayerNames(args[0]);
        }
        return List.of();
    }
}
