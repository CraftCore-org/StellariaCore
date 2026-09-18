package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.gui.ReportCategoryGui;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** プレイヤー報告の対象指定とカテゴリ選択画面を開始するコマンド。 */
public final class ReportCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public ReportCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("report.must_be_player", null));
            return true;
        }
        if (args.length != 1) {
            sendLines(player, "report.usage");
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            player.sendMessage(plugin.getConfigManager().getMessage("report.player_not_found", player));
            return true;
        }

        new ReportCategoryGui(plugin, player, target.getUniqueId()).open(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String @NotNull [] args) {
        return args.length == 1 ? TabCompleteUtil.knownPlayerNames(args[0]) : List.of();
    }

    private void sendLines(Player player, String path) {
        for (String line : plugin.getConfigManager().getMessageList(path)) {
            player.sendMessage(FormatUtil.text(player, line));
        }
    }
}
