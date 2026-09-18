package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.DurationParser;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** /warn、/kick、/ban をまとめて処理する運営向けモデレーションコマンド。 */
public final class ModerationCommand implements CommandExecutor, TabCompleter {

    private static final String WARN_PERMISSION = "stellaria.warn";
    private static final String KICK_PERMISSION = "stellaria.kick";
    private static final String BAN_PERMISSION = "stellaria.ban";
    private static final long WARN_ACTION_BAR_TICKS = 100L;

    private final StellariaCore plugin;

    public ModerationCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String @NotNull [] args) {
        return switch (command.getName().toLowerCase()) {
            case "warn" -> handleWarn(sender, args);
            case "kick" -> handleKick(sender, args);
            case "ban" -> handleBan(sender, args);
            default -> true;
        };
    }

    private boolean handleWarn(CommandSender sender, String[] args) {
        if (!hasPermission(sender, WARN_PERMISSION)) {
            return true;
        }
        if (args.length < 2) {
            sendUsage(sender, "moderation.warn_usage");
            return true;
        }

        OfflinePlayer target = knownTarget(sender, args[0]);
        if (target == null) {
            return true;
        }
        String reason = joinReason(args, 1);
        if (!plugin.getModerationManager().warn(target.getUniqueId(), moderatorUuid(sender), reason)) {
            sendDatabaseError(sender);
            return true;
        }

        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null) {
            String message = plugin.getConfigManager().getMessage("moderation.warned_target_actionbar", target)
                    .replace("%reason%", reason);
            plugin.getActionBarManager().flash(onlineTarget, "moderation_warn", ColorUtil.component(message), WARN_ACTION_BAR_TICKS);
        }
        sender.sendMessage(plugin.getConfigManager().getMessage("moderation.warned_sender", target)
                .replace("%reason%", reason));
        return true;
    }

    private boolean handleKick(CommandSender sender, String[] args) {
        if (!hasPermission(sender, KICK_PERMISSION)) {
            return true;
        }
        if (args.length < 2) {
            sendUsage(sender, "moderation.kick_usage");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(plugin.getConfigManager().getMessage("moderation.player_not_online", Bukkit.getOfflinePlayer(args[0])));
            return true;
        }
        String reason = joinReason(args, 1);
        if (!plugin.getModerationManager().recordKick(target.getUniqueId(), moderatorUuid(sender), reason)) {
            sendDatabaseError(sender);
            return true;
        }

        sender.sendMessage(plugin.getConfigManager().getMessage("moderation.kicked_sender", target)
                .replace("%reason%", reason));
        target.kick(ColorUtil.component(disconnectMessage("moderation.kick_screen", target, reason, "")));
        return true;
    }

    private boolean handleBan(CommandSender sender, String[] args) {
        if (!hasPermission(sender, BAN_PERMISSION)) {
            return true;
        }
        if (args.length < 3) {
            sendUsage(sender, "moderation.ban_usage");
            return true;
        }

        OfflinePlayer target = knownTarget(sender, args[0]);
        if (target == null) {
            return true;
        }

        long seconds;
        long expiresAt;
        try {
            seconds = DurationParser.parseSeconds(args[1]);
            expiresAt = DurationParser.expiresAtMillis(System.currentTimeMillis(), seconds);
        } catch (IllegalArgumentException e) {
            sendUsage(sender, "moderation.ban_usage");
            return true;
        }

        String reason = joinReason(args, 2);
        String expiresText = DurationParser.formatRemaining(expiresAt);
        if (!plugin.getModerationManager().ban(target.getUniqueId(), moderatorUuid(sender), reason, expiresAt)) {
            sendDatabaseError(sender);
            return true;
        }

        sender.sendMessage(plugin.getConfigManager().getMessage("moderation.banned_sender", target)
                .replace("%reason%", reason)
                .replace("%expires%", expiresText));
        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null) {
            onlineTarget.kick(ColorUtil.component(disconnectMessage("moderation.ban_screen", target, reason, expiresText)));
        }
        return true;
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(plugin.getConfigManager().getMessage("moderation.no_permission", null));
        return false;
    }

    private OfflinePlayer knownTarget(CommandSender sender, String name) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(name);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("moderation.player_not_found", target));
            return null;
        }
        return target;
    }

    private void sendUsage(CommandSender sender, String path) {
        for (String line : plugin.getConfigManager().getMessageList(path)) {
            sender.sendMessage(ColorUtil.colorize(line));
        }
    }

    private void sendDatabaseError(CommandSender sender) {
        sender.sendMessage(plugin.getConfigManager().getMessage("moderation.database_error", null));
    }

    private String disconnectMessage(String path, OfflinePlayer target, String reason, String expiresText) {
        return plugin.getConfigManager().getMessage(path, target)
                .replace("%reason%", reason)
                .replace("%expires%", expiresText)
                .replace("%discord_invite%", plugin.getConfigManager().getString("discord.invite", ""));
    }

    private static UUID moderatorUuid(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    private static String joinReason(String[] args, int fromIndex) {
        return String.join(" ", Arrays.copyOfRange(args, fromIndex, args.length));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String @NotNull [] args) {
        return switch (command.getName().toLowerCase()) {
            case "warn", "ban" -> {
                if (args.length == 1) {
                    yield TabCompleteUtil.knownPlayerNames(args[0]);
                }
                if (command.getName().equalsIgnoreCase("ban") && args.length == 2) {
                    yield TabCompleteUtil.filterStartsWith(List.of("10m", "1h", "3d", "permanent"), args[1]);
                }
                yield List.of();
            }
            case "kick" -> args.length == 1 ? TabCompleteUtil.onlinePlayerNames(args[0]) : List.of();
            default -> List.of();
        };
    }
}
