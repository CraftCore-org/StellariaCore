package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.DurationParser;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * /mute と /unmute を1つのCommandExecutorで捌く（TpaCoreと同じ「関連コマンドをまとめてdispatch」方針）。
 */
public class MuteCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public MuteCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.mute")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("mute.no_permission", null));
            return true;
        }

        if (command.getName().equalsIgnoreCase("unmute")) {
            handleUnmute(sender, args);
        } else {
            handleMute(sender, args);
        }
        return true;
    }

    private void handleMute(CommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("help")) {
            for (String line : plugin.getConfigManager().getMessageList("mute.help")) {
                sender.sendMessage(ColorUtil.colorize(line));
            }
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(plugin.getConfigManager().getMessage("mute.usage", null));
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("mute.player_not_found", target));
            return;
        }

        int level;
        try {
            level = Integer.parseInt(args[1]);
            if (level < 1 || level > 3) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getConfigManager().getMessage("mute.invalid_level", null));
            return;
        }

        long seconds;
        try {
            seconds = DurationParser.parseSeconds(args[2]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(plugin.getConfigManager().getMessage("mute.invalid_duration", null));
            return;
        }
        long expiresAt = seconds < 0 ? -1 : System.currentTimeMillis() + seconds * 1000L;

        String reason = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        String moderatorName = sender instanceof Player p ? p.getName() : "CONSOLE";

        plugin.getMuteManager().mute(target.getUniqueId(), level, expiresAt, reason, moderatorName);
        notifyMute(sender, target, level, expiresAt, reason, moderatorName);
    }

    private void handleUnmute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(plugin.getConfigManager().getMessage("mute.usage", null));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (plugin.getMuteManager().getRecord(target.getUniqueId()) == null) {
            sender.sendMessage(plugin.getConfigManager().getMessage("mute.not_muted", target));
            return;
        }
        String moderatorName = sender instanceof Player p ? p.getName() : "CONSOLE";
        plugin.getMuteManager().unmute(target.getUniqueId());
        notifyUnmute(sender, target, moderatorName);
    }

    private void notifyMute(CommandSender sender, OfflinePlayer target, int level, long expiresAt, String reason, String moderatorName) {
        String expiresText = DurationParser.formatRemaining(expiresAt);

        if (target.isOnline() && target.getPlayer() != null) {
            target.getPlayer().sendMessage(plugin.getConfigManager().getMessage("mute.muted_target", target)
                    .replace("%level%", String.valueOf(level))
                    .replace("%reason%", reason)
                    .replace("%expires%", expiresText));
        }
        sender.sendMessage(plugin.getConfigManager().getMessage("mute.muted_sender", target)
                .replace("%level%", String.valueOf(level))
                .replace("%expires%", expiresText));

        String staffMessage = plugin.getConfigManager().getMessage("mute.muted_staff", target)
                .replace("%moderator%", moderatorName)
                .replace("%reason%", reason);
        broadcastToStaff(staffMessage, sender, target);
    }

    private void notifyUnmute(CommandSender sender, OfflinePlayer target, String moderatorName) {
        if (target.isOnline() && target.getPlayer() != null) {
            target.getPlayer().sendMessage(plugin.getConfigManager().getMessage("mute.unmuted_target", target));
        }
        sender.sendMessage(plugin.getConfigManager().getMessage("mute.unmuted_sender", target));

        String staffMessage = plugin.getConfigManager().getMessage("mute.unmuted_staff", target)
                .replace("%moderator%", moderatorName);
        broadcastToStaff(staffMessage, sender, target);
    }

    /** 実行者・対象者を除いた stellaria.mute.notify 権限保持者全員に通知する。 */
    private void broadcastToStaff(String message, CommandSender sender, OfflinePlayer target) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(sender) || online.getUniqueId().equals(target.getUniqueId())) {
                continue;
            }
            if (online.hasPermission("stellaria.mute.notify")) {
                online.sendMessage(message);
            }
        }
    }
}
