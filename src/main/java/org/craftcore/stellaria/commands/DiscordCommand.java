package org.craftcore.stellaria.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;
import org.jetbrains.annotations.NotNull;

/**
 * /discord コマンド。config.yml の discord.invite をクリックで開けるリンク付きで案内する。
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

        Component component = ColorUtil.component(message);
        if (!invite.isEmpty()) {
            component = component.clickEvent(ClickEvent.openUrl(invite));
        }
        sender.sendMessage(component);
        return true;
    }
}
