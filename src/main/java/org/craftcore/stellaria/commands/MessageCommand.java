package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.MuteManager;
import org.craftcore.stellaria.utils.DurationParser;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * /msg（alias tell, w, message）と /reply（alias r）を1つのCommandExecutorで捌く
 * （TpaCoreと同じ「1クラスで関連コマンドをまとめてdispatchする」方針）。
 */
public class MessageCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public MessageCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("msg.must_be_player", null));
            return true;
        }
        if (!player.hasPermission("stellaria.msg")) {
            player.sendMessage(plugin.getConfigManager().getMessage("msg.no_permission", player));
            return true;
        }

        if (command.getName().equalsIgnoreCase("reply")) {
            handleReply(player, args);
        } else {
            handleMsg(player, args);
        }
        return true;
    }

    private void handleMsg(Player sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.getConfigManager().getUsageMessage("msg.usage", sender));
            return;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(plugin.getConfigManager().getMessage("msg.player_not_found", Bukkit.getOfflinePlayer(args[0])));
            return;
        }
        if (target.equals(sender)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("msg.self", sender));
            return;
        }
        trySend(sender, target, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
    }

    private void handleReply(Player sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(plugin.getConfigManager().getUsageMessage("msg.usage", sender));
            return;
        }
        UUID lastUuid = plugin.getPrivateMessageManager().getLastMessaged(sender.getUniqueId());
        if (lastUuid == null) {
            sender.sendMessage(plugin.getConfigManager().getMessage("msg.reply_no_target", sender));
            return;
        }
        Player target = Bukkit.getPlayer(lastUuid);
        if (target == null) {
            sender.sendMessage(plugin.getConfigManager().getMessage("msg.player_not_found", Bukkit.getOfflinePlayer(lastUuid)));
            return;
        }
        trySend(sender, target, String.join(" ", args));
    }

    private void trySend(Player sender, Player target, String message) {
        MuteManager.MuteRecord record = plugin.getMuteManager()
                .getRestrictingRecord(sender.getUniqueId(), MuteManager.MuteScope.PM);
        if (record != null) {
            sender.sendMessage(plugin.getConfigManager().getMessage("mute.blocked_pm", sender)
                    .replace("%remaining%", DurationParser.formatRemaining(record.expiresAt()))
                    .replace("%reason%", record.reason()));
            return;
        }
        plugin.getPrivateMessageManager().send(sender, target, message);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (command.getName().equalsIgnoreCase("reply")) {
            return List.of();
        }
        if (args.length == 1) {
            return TabCompleteUtil.onlinePlayerNames(args[0]);
        }
        return List.of();
    }
}
