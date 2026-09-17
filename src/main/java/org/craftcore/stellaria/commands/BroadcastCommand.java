package org.craftcore.stellaria.commands;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;
import org.jetbrains.annotations.NotNull;

/**
 * /broadcast（alias bc）コマンド。引数を結合し、messages.yml の broadcast.format
 * （%message% プレースホルダ）に差し込んで全員に送信する。
 * カラーコード使用は stellaria.broadcast.color 権限を持つ場合のみ有効。
 */
public class BroadcastCommand implements CommandExecutor {

    private static final String MESSAGE_TOKEN = "%message%";

    private final StellariaCore plugin;

    public BroadcastCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.broadcast")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("broadcast.no_permission", null));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.getConfigManager().getUsageMessage("broadcast.usage", null));
            return true;
        }

        String rawMessage = String.join(" ", args);
        boolean colorAllowed = sender.hasPermission("stellaria.broadcast.color");
        Component messageBody = plugin.getMentionService().highlightBroadcast(rawMessage, colorAllowed, true);

        String template = plugin.getConfigManager().getRawMessage("broadcast.format");
        Bukkit.broadcast(splice(template, messageBody));
        return true;
    }

    /** テンプレート文字列の %message% の位置にComponentを差し込んで、前後をColorUtilで色変換する。 */
    private Component splice(String template, Component messageBody) {
        int index = template.indexOf(MESSAGE_TOKEN);
        if (index < 0) {
            return ColorUtil.component(template).append(messageBody);
        }
        Component prefix = ColorUtil.component(template.substring(0, index));
        Component suffix = ColorUtil.component(template.substring(index + MESSAGE_TOKEN.length()));
        return prefix.append(messageBody).append(suffix);
    }
}
