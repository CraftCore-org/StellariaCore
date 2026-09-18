package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.UrlHighlighter;
import org.jetbrains.annotations.NotNull;

/**
 * /rules コマンド。messages.yml の rules.lines を1行ずつ送信する。
 * DiscordCommandと同じ仕組みで、行中のURL（利用規約・プライバシーポリシー）だけを
 * クリック可能なリンクにする。専用権限は無し（誰でも実行可）。
 */
public class RulesCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public RulesCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        Player player = sender instanceof Player p ? p : null;
        String urlHint = plugin.getConfigManager().getMessage("chat.url_hint", null);

        for (String rawLine : plugin.getConfigManager().getMessageList("rules.lines")) {
            String colored = FormatUtil.text(player, rawLine);
            sender.sendMessage(UrlHighlighter.highlight(colored, true, urlHint));
        }
        return true;
    }
}
