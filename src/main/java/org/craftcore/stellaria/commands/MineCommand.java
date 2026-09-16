package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.MineManager;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /mine コマンド。引数なしはトグル、on/off/buyがサブコマンド。
 * pass は隠しサブコマンド（タブ補完の候補には出さないが、onCommand内では通常通り処理する）。
 */
public class MineCommand implements CommandExecutor, TabCompleter {

    private static final List<String> VISIBLE_SUBCOMMANDS = List.of("on", "off", "buy");

    private final StellariaCore plugin;

    public MineCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("mine.must_be_player", null));
            return true;
        }
        if (!player.hasPermission("stellaria.mine")) {
            player.sendMessage(plugin.getConfigManager().getMessage("mine.no_permission", player));
            return true;
        }

        MineManager manager = plugin.getMineManager();
        String sub = args.length > 0 ? args[0].toLowerCase() : "";

        switch (sub) {
            case "buy" -> handleBuy(player, manager);
            case "pass" -> handlePass(player, manager);
            case "on" -> handleSetEnabled(player, manager, true);
            case "off" -> handleSetEnabled(player, manager, false);
            case "" -> handleSetEnabled(player, manager, !manager.isEnabled(player.getUniqueId()));
            default -> player.sendMessage(plugin.getConfigManager().getMessage("mine.usage", player));
        }
        return true;
    }

    private void handleBuy(Player player, MineManager manager) {
        MineManager.PurchaseResult result = manager.purchase(player);
        switch (result) {
            case SUCCESS -> player.sendMessage(plugin.getConfigManager().getMessage("mine.buy_success", player));
            case ALREADY_UNLOCKED -> player.sendMessage(plugin.getConfigManager().getMessage("mine.already_unlocked", player));
            case INSUFFICIENT_FUNDS -> player.sendMessage(plugin.getConfigManager().getMessage("mine.buy_insufficient_funds", player)
                    .replace("%price%", currentPriceText()));
        }
    }

    private void handlePass(Player player, MineManager manager) {
        if (!manager.isUnlocked(player)) {
            sendNotUnlocked(player);
            return;
        }
        manager.grantPass(player);
        player.sendMessage(plugin.getConfigManager().getMessage("mine.pass_granted", player));
    }

    private void handleSetEnabled(Player player, MineManager manager, boolean enabled) {
        if (enabled && !manager.isUnlocked(player)) {
            sendNotUnlocked(player);
            return;
        }
        manager.setEnabled(player, enabled);
        String path = enabled ? "mine.enabled" : "mine.disabled";
        player.sendMessage(plugin.getConfigManager().getMessage(path, player));
    }

    private void sendNotUnlocked(Player player) {
        player.sendMessage(plugin.getConfigManager().getMessage("mine.not_unlocked", player)
                .replace("%price%", currentPriceText()));
    }

    private String currentPriceText() {
        int price = plugin.getConfigManager().getInt("mine.price", 3000);
        return plugin.getEconomyManager().format(price);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(VISIBLE_SUBCOMMANDS, args[0]);
        }
        return List.of();
    }
}
