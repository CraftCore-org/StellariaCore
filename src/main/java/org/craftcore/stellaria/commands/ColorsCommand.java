package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.TreeMap;

/**
 * /colors コマンド。ColorUtilのカスタムカラーコード（&%0〜&%h）を実際の色で一覧表示する
 * カンペコマンド。専用権限は用意せず、既存の stellaria.chat.color（チャットで色コードを
 * 使える権限）を流用する。
 */
public class ColorsCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public ColorsCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.chat.color")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("colors.no_permission", null));
            return true;
        }

        sender.sendMessage(plugin.getConfigManager().getMessage("colors.header", null));
        for (Map.Entry<Character, String> entry : new TreeMap<>(ColorUtil.getCustomColors()).entrySet()) {
            char code = entry.getKey();
            String hex = entry.getValue().toUpperCase();
            String line = "&%" + code + "■■■ " + code + " #" + hex + "&r";
            sender.sendMessage(ColorUtil.colorize(line));
        }
        return true;
    }
}
