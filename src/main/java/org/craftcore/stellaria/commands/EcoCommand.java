package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.EconomyManager;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /eco give|set|take コマンド。管理者用の残高操作を1つのCommandExecutorで捌く
 * （TpaCore/MuteCommandと同じ「関連サブコマンドをまとめてdispatch」方針）。
 */
public class EcoCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public EcoCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.eco")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("eco.no_permission", null));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(plugin.getConfigManager().getUsageMessage("eco.usage", null));
            return true;
        }

        String subCommand = args[0].toLowerCase();
        if (!subCommand.equals("give") && !subCommand.equals("set") && !subCommand.equals("take")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("eco.invalid_subcommand", null));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("eco.player_not_found", target));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
            boolean valid = subCommand.equals("set") ? amount >= 0 : amount > 0;
            if (!valid) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getConfigManager().getMessage("eco.invalid_amount", null));
            return true;
        }

        EconomyManager economy = plugin.getEconomyManager();
        switch (subCommand) {
            case "give" -> {
                economy.depositPlayer(target, amount);
                notify(sender, target, "eco.give_sender", "eco.give_receiver", amount);
            }
            case "set" -> {
                economy.setBalance(target, amount);
                notify(sender, target, "eco.set_sender", "eco.set_receiver", amount);
            }
            case "take" -> {
                if (economy.getBalance(target) < amount) {
                    sender.sendMessage(plugin.getConfigManager().getMessage("eco.insufficient_balance", target));
                    return true;
                }
                economy.withdrawPlayer(target, amount);
                notify(sender, target, "eco.take_sender", "eco.take_receiver", amount);
            }
        }
        return true;
    }

    private void notify(CommandSender sender, OfflinePlayer target, String senderKey, String receiverKey, long amount) {
        String amountText = plugin.getEconomyManager().formatExact(amount);
        sender.sendMessage(plugin.getConfigManager().getMessage(senderKey, target)
                .replace("%amount%", amountText));
        if (target.isOnline() && target.getPlayer() != null) {
            target.getPlayer().sendMessage(plugin.getConfigManager().getMessage(receiverKey, target)
                    .replace("%amount%", amountText));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(List.of("give", "set", "take"), args[0]);
        }
        if (args.length == 2) {
            return TabCompleteUtil.knownPlayerNames(args[1]);
        }
        return List.of();
    }
}
