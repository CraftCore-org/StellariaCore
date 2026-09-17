package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.features.Feature;
import org.craftcore.stellaria.gui.FeaturesGui;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/** /features のGUI表示と直接購入を扱うコマンド。 */
public class FeaturesCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;
    private final List<Feature> features;

    public FeaturesCommand(StellariaCore plugin, List<Feature> features) {
        this.plugin = plugin;
        this.features = List.copyOf(features);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("features.must_be_player", null));
            return true;
        }

        if (args.length == 0) {
            new FeaturesGui(plugin, features, player).open(player);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
            findFeature(args[1]).ifPresentOrElse(
                    feature -> purchase(player, feature),
                    () -> player.sendMessage(plugin.getConfigManager().getMessage("features.unknown_feature", player))
            );
            return true;
        }

        player.sendMessage(plugin.getConfigManager().getUsageMessage("features.usage", player));
        return true;
    }

    private void purchase(Player player, Feature feature) {
        switch (feature.purchase(player)) {
            case SUCCESS -> player.sendMessage(plugin.getConfigManager().getMessage("features.buy_success", player));
            case ALREADY_UNLOCKED -> player.sendMessage(plugin.getConfigManager().getMessage("features.already_unlocked", player));
            case INSUFFICIENT_FUNDS -> player.sendMessage(plugin.getConfigManager().getMessage("features.buy_insufficient_funds", player)
                    .replace("%price%", plugin.getEconomyManager().format(feature.price())));
            case DATABASE_ERROR -> player.sendMessage(plugin.getConfigManager().getMessage("features.buy_failed", player));
        }
    }

    private Optional<Feature> findFeature(String id) {
        return features.stream().filter(feature -> feature.id().equalsIgnoreCase(id)).findFirst();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(List.of("buy"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
            return TabCompleteUtil.filterStartsWith(features.stream().map(Feature::id).toList(), args[1]);
        }
        return List.of();
    }
}
