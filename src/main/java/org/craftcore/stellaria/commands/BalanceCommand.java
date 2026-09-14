package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.EconomyManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /balance（alias money, bal）コマンド。引数なしで自分の残高、プレイヤー指定で他人の残高
 * （要 stellaria.balance.others）、"top [page]" でランキング表示。
 */
public class BalanceCommand implements CommandExecutor {

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

        if (args.length >= 1 && args[0].equalsIgnoreCase("top")) {
            showTop(sender, args);
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
        String amountText = economy.format(economy.getBalance(target));
        sender.sendMessage(plugin.getConfigManager().getMessage(messageKey, target)
                .replace("%amount%", amountText));
    }

    private void showTop(CommandSender sender, String[] args) {
        int pageSize = plugin.getConfigManager().getInt("economy.balance-top-page-size", 10);
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException e) {
                page = 1;
            }
        }

        EconomyManager economy = plugin.getEconomyManager();
        int totalPlayers = economy.getPlayerCount();
        int maxPage = Math.max(1, (int) Math.ceil(totalPlayers / (double) pageSize));
        int offset = (page - 1) * pageSize;

        List<EconomyManager.BalanceEntry> entries = economy.getTopBalances(pageSize, offset);

        sender.sendMessage(plugin.getConfigManager().getMessage("balance.top_header", null)
                .replace("%page%", String.valueOf(page))
                .replace("%max_page%", String.valueOf(maxPage)));

        if (entries.isEmpty()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("balance.top_empty", null));
            return;
        }

        int rank = offset + 1;
        for (EconomyManager.BalanceEntry entry : entries) {
            sender.sendMessage(plugin.getConfigManager().getMessage("balance.top_entry", null)
                    .replace("%rank%", String.valueOf(rank))
                    .replace("%player%", entry.name())
                    .replace("%amount%", economy.format(entry.coins())));
            rank++;
        }
    }
}
