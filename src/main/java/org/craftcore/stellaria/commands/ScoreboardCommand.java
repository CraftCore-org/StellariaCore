package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /scoreboard（alias sb）コマンド。引数無しでトグル、show/hideで明示指定。
 * セッション限定（再ログインすると表示に戻る）で、自分のサイドバー表示だけを切り替える。
 */
public class ScoreboardCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public ScoreboardCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("scoreboard.must_be_player", null));
            return true;
        }
        if (!player.hasPermission("stellaria.scoreboard")) {
            player.sendMessage(plugin.getConfigManager().getMessage("scoreboard.no_permission", player));
            return true;
        }

        boolean newHidden;
        if (args.length == 0) {
            newHidden = !plugin.getScoreboardManager().isHidden(player);
        } else if (args[0].equalsIgnoreCase("hide")) {
            newHidden = true;
        } else if (args[0].equalsIgnoreCase("show")) {
            newHidden = false;
        } else {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("scoreboard.usage", player));
            return true;
        }

        plugin.getScoreboardManager().setHidden(player, newHidden);
        player.sendMessage(plugin.getConfigManager().getMessage(
                newHidden ? "scoreboard.hidden" : "scoreboard.shown", player));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(List.of("show", "hide"), args[0]);
        }
        return List.of();
    }
}
