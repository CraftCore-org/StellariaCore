package org.craftcore.stellaria.commands;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.UrlHighlighter;
import org.jetbrains.annotations.NotNull;

/**
 * /discord コマンド。config.yml の discord.invite を案内する。
 * チャットのURL自動リンク化（{@link UrlHighlighter}）と同じ仕組みで、メッセージ中の
 * リンク部分だけをクリック可能・ホバー付きにする（前後の文言まで丸ごとクリック領域にはしない）。
 * 専用権限は無し（誰でも実行可）。
 */
public class DiscordCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public DiscordCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        String invite = plugin.getConfigManager().getString("discord.invite", "");
        String message = plugin.getConfigManager().getMessage("discord.message", null)
                .replace("%link%", invite);
        String urlHint = plugin.getConfigManager().getMessage("chat.url_hint", null);

        Component component = UrlHighlighter.highlight(message, true, urlHint);
        sender.sendMessage(component);
        return true;
    }
}
