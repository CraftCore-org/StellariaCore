package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/**
 * /sudo <プレイヤー> <コマンド...> — オンライン中の対象プレイヤーとして、コマンドを1回だけ実行する。
 * 対象プレイヤー自身の権限で実行される（{@link Player#performCommand(String)}）。対象には何も表示せず、
 * 誰が誰としてどのコマンドを実行したかをコンソールにだけ記録する。config.yml の sudo.blacklist に
 * 挙がっているコマンドは拒否する（デフォルトでバニラの危険なコマンドと sudo 自身を含む——入れ子ループ防止）。
 */
public class SudoCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public SudoCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        OfflinePlayer placeholderPlayer = sender instanceof Player player ? player : null;

        if (args.length < 2) {
            sender.sendMessage(plugin.getConfigManager().getUsageMessage("sudo.usage", placeholderPlayer));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("sudo.player_not_found", placeholderPlayer),
                    "%target%", args[0]));
            return true;
        }

        String targetCommand = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String rawBaseCommand = targetCommand.split("\\s+", 2)[0].toLowerCase();
        String baseCommand = canonicalizeCommand(rawBaseCommand);

        List<String> blacklist = plugin.getConfigManager().getStringList("sudo.blacklist");
        if (blacklist.stream().map(SudoCommand::canonicalizeCommand).anyMatch(entry -> entry.equals(baseCommand))) {
            sender.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("sudo.blacklisted", placeholderPlayer),
                    "%command%", baseCommand));
            return true;
        }

        plugin.getLogger().info(sender.getName() + " executed as " + target.getName() + ": /" + targetCommand);
        target.performCommand(targetCommand);

        String message = FormatUtil.replace(
                plugin.getConfigManager().getMessage("sudo.executed", placeholderPlayer),
                "%target%", target.getName());
        message = FormatUtil.replace(message, "%command%", targetCommand);
        sender.sendMessage(message);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.onlinePlayerNames(args[0]);
        }
        return List.of();
    }

    /** {@code minecraft:op} のようなnamespace接頭辞を除去してblacklist照合をすり抜けられないようにする。 */
    private static String canonicalizeCommand(String command) {
        String lower = command.toLowerCase();
        if (lower.startsWith("/")) lower = lower.substring(1);
        int colon = lower.indexOf(':');
        if (colon >= 0) lower = lower.substring(colon + 1);
        return lower;
    }
}
