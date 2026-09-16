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
import org.craftcore.stellaria.gui.ProfileGui;
import org.craftcore.stellaria.managers.EconomyManager;
import org.craftcore.stellaria.utils.DurationParser;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /profile [プレイヤー] コマンド。自分のプロフィールはGUIで、指定プレイヤーはテキストで表示する。
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

        if (args.length == 0) {
            new ProfileGui(plugin, viewer).open(viewer);
            return true;
        }

        if (!viewer.hasPermission("stellaria.profile.others")) {
            viewer.sendMessage(plugin.getConfigManager().getMessage("profile.no_permission_others", null));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);

        if (!target.hasPlayedBefore() && !target.isOnline()) {
            viewer.sendMessage(plugin.getConfigManager().getMessage("profile.player_not_found", target));
            return true;
        }

        viewer.sendMessage(buildProfile(viewer, target));
        return true;
    }

    /** プロフィールの複数行表示を組み立てる。 */
    private Component buildProfile(Player viewer, OfflinePlayer target) {
        return plugin.getPlaceholderManager().resolveLines(buildProfileInfoLines(plugin, viewer, target), viewer);
    }

    /**
     * {@code profile.info-lines} のプロフィール固有トークンを置換した未解決テンプレートを返す。
     * {@code %playtime%} は PlaceholderManager の組み込みトークンではないため、
     * {@link org.craftcore.stellaria.managers.PlaceholderManager#resolveLines(List, Player)} の前に
     * ここで必ず置換する。ProfileGui もこのメソッドを使い、テキスト表示と同じ内容を表示する。
     */
    public static List<String> buildProfileInfoLines(StellariaCore plugin, Player viewer, OfflinePlayer target) {
        EconomyManager economy = plugin.getEconomyManager();
        String playtime = DurationParser.formatDuration(plugin.getPlaytimeManager().getPlaytimeSeconds(target.getUniqueId()));
        boolean isSelf = viewer.getUniqueId().equals(target.getUniqueId());
        boolean hideBalance = economy.isHideBalance(target) && !isSelf;
        String balance;
        if (hideBalance) {
            balance = plugin.getConfigManager().getMessage("profile.balance_hidden", null);
        } else if (isSelf && economy.isHideBalance(target)) {
            // 自分自身には金額を見せつつ、他人には非公開設定にしていることを併記する。
            balance = economy.formatExact(economy.getBalance(target))
                    + plugin.getConfigManager().getMessage("profile.balance_self_hidden_suffix", null);
        } else {
            balance = economy.formatExact(economy.getBalance(target));
        }

        return plugin.getConfigManager().getStringList("profile.info-lines").stream()
                .map(line -> line
                        .replace("%player%", target.getName() != null ? target.getName() : target.getUniqueId().toString())
                        .replace("%playtime%", playtime)
                        .replace("%money%", balance))
                .toList();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.knownPlayerNames(args[0]);
        }
        return List.of();
    }
}
