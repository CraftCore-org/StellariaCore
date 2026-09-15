package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
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
 * /pay コマンド。プレイヤー間送金。EconomyManager.transfer() が1トランザクションで処理する。
 */
public class PayCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public PayCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("pay.must_be_player", null));
            return true;
        }
        if (!player.hasPermission("stellaria.pay")) {
            player.sendMessage(plugin.getConfigManager().getMessage("pay.no_permission", player));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().getMessage("pay.usage", player));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(plugin.getConfigManager().getMessage("pay.player_not_found", Bukkit.getOfflinePlayer(args[0])));
            return true;
        }
        if (target.equals(player)) {
            player.sendMessage(plugin.getConfigManager().getMessage("pay.self", player));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[1]);
            if (amount <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getConfigManager().getMessage("pay.invalid_amount", player));
            return true;
        }

        EconomyManager economy = plugin.getEconomyManager();
        if (!economy.transfer(player, target, amount)) {
            player.sendMessage(plugin.getConfigManager().getMessage("pay.insufficient_balance", player));
            return true;
        }

        String amountText = economy.formatExact(amount);
        player.sendMessage(plugin.getConfigManager().getMessage("pay.sender", target)
                .replace("%amount%", amountText));
        target.sendMessage(plugin.getConfigManager().getMessage("pay.receiver", player)
                .replace("%amount%", amountText));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.onlinePlayerNames(args[0]);
        }
        return List.of();
    }
}
