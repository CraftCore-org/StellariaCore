package org.craftcore.stellaria.commands;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.VoteMessageUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /vote コマンド。
 * config.yml に設定された投票サイトへのリンクを表示する。
 */
public class VoteCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public VoteCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String @NotNull [] args
    ) {

        List<String> lines =
                plugin.getConfigManager()
                        .getStringList("vote.message");

        Player player =
                sender instanceof Player p
                        ? p
                        : null;

        List<Component> components =
                VoteMessageUtil.build(
                        plugin,
                        lines,
                        player
                );

        for (Component component : components) {
            sender.sendMessage(component);
        }

        return true;
    }
}