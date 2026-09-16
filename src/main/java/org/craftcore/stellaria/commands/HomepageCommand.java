package org.craftcore.stellaria.commands;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.craftcore.stellaria.utils.UrlHighlighter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /homepage コマンド。通常はサーバーのホームページ、craftcore 指定時は運営組織のホームページを案内する。
 * 専用権限は無し（誰でも実行可）。
 */
public class HomepageCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("craftcore");

    private final StellariaCore plugin;

    public HomepageCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        boolean craftcore = args.length > 0 && args[0].equalsIgnoreCase("craftcore");
        String linkPath = craftcore ? "homepage.craftcore-link" : "homepage.link";
        String messagePath = craftcore ? "homepage.craftcore_message" : "homepage.message";
        String link = plugin.getConfigManager().getString(linkPath, "");
        String message = plugin.getConfigManager().getMessage(messagePath, null)
                .replace("%link%", link);
        String urlHint = plugin.getConfigManager().getMessage("chat.url_hint", null);

        Component component = UrlHighlighter.highlight(message, true, urlHint);
        sender.sendMessage(component);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(SUBCOMMANDS, args[0]);
        }
        return List.of();
    }
}
