package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;
import org.jetbrains.annotations.NotNull;

/**
 * /tphelp コマンド。テレポートリクエスト（/tpa 系）の使い方を表示する。
 * 内容は menu.tpa_help_lines を共有（メニューGUIのテレポリクエストヘルプ項目と同じ）。
 * 専用権限は無し（誰でも実行可）。
 */
public class TpHelpCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public TpHelpCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        Player player = sender instanceof Player p ? p : null;
        for (String line : plugin.getConfigManager().getMessageList("menu.tpa_help_lines")) {
            sender.sendMessage(FormatUtil.text(player, line));
        }
        return true;
    }
}
