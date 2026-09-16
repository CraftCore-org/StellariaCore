package org.craftcore.stellaria.commands;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.UrlHighlighter;
import org.jetbrains.annotations.NotNull;

/**
 * /vote コマンド。config.yml に設定した投票サイトへのリンクを案内する。
 */
public class VoteCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public VoteCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        String mineportal = plugin.getConfigManager().getString("vote.sites.mineportal", "");
        String minecraftjp = plugin.getConfigManager().getString("vote.sites.minecraftjp", "");
        String message = plugin.getConfigManager().getMessage("vote.message", null)
            .replace("%mineportal%", mineportal)
            .replace("%minecraftjp%", minecraftjp);
        String urlHint = plugin.getConfigManager().getMessage("chat.url_hint", null);

        Component component = UrlHighlighter.highlight(message, true, urlHint);
        sender.sendMessage(component);
        return true;
    }
}
