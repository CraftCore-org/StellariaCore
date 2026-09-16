package org.craftcore.stellaria.commands;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.EconomyManager;
import org.craftcore.stellaria.utils.DurationParser;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /profile [プレイヤー] コマンド。自分または権限を持つ閲覧者に指定プレイヤーのプロフィールを表示する。
 * 所持金の表示内容は {@link #buildProfile(Player, OfflinePlayer)} にまとめ、将来のGUIからも再利用しやすくしている。
 */
public class ProfileCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public ProfileCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.profile")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("profile.no_permission", null));
            return true;
        }
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("profile.must_be_player", null));
            return true;
        }
        if (args.length > 1) {
            viewer.sendMessage(plugin.getConfigManager().getMessage("profile.usage", viewer));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("hide")) {
            plugin.getEconomyManager().setHideBalance(viewer, true);
            viewer.sendMessage(plugin.getConfigManager().getMessage("profile.balance_hidden_enabled", viewer));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("show")) {
            plugin.getEconomyManager().setHideBalance(viewer, false);
            viewer.sendMessage(plugin.getConfigManager().getMessage("profile.balance_hidden_disabled", viewer));
            return true;
        }

        OfflinePlayer target;
        if (args.length == 0) {
            target = viewer;
        } else {
            if (!viewer.hasPermission("stellaria.profile.others")) {
                viewer.sendMessage(plugin.getConfigManager().getMessage("profile.no_permission_others", null));
                return true;
            }
            target = Bukkit.getOfflinePlayer(args[0]);
        }

        if (!target.hasPlayedBefore() && !target.isOnline()) {
            viewer.sendMessage(plugin.getConfigManager().getMessage("profile.player_not_found", target));
            return true;
        }

        viewer.sendMessage(buildProfile(viewer, target));
        return true;
    }

    /** プロフィールの複数行表示を組み立てる。将来のGUI表示でもこの内容生成を再利用できる。 */
    private Component buildProfile(Player viewer, OfflinePlayer target) {
        EconomyManager economy = plugin.getEconomyManager();
        String playtime = DurationParser.formatDuration(plugin.getPlaytimeManager().getPlaytimeSeconds(target.getUniqueId()));
        boolean hideBalance = economy.isHideBalance(target) && !viewer.getUniqueId().equals(target.getUniqueId());
        String balance = hideBalance
                ? plugin.getConfigManager().getMessage("profile.balance_hidden", null)
                : economy.formatExact(economy.getBalance(target));

        List<String> lines = plugin.getConfigManager().getStringList("profile.info-lines").stream()
                .map(line -> line
                        .replace("%player%", target.getName() != null ? target.getName() : target.getUniqueId().toString())
                        .replace("%playtime%", playtime)
                        .replace("%money%", balance))
                .toList();
        return plugin.getPlaceholderManager().resolveLines(lines, viewer);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(List.of("hide", "show"), args[0]).isEmpty()
                    ? TabCompleteUtil.knownPlayerNames(args[0])
                    : TabCompleteUtil.filterStartsWith(List.of("hide", "show"), args[0]);
        }
        return List.of();
    }
}
