package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.ModerationManager;
import org.craftcore.stellaria.utils.DurationParser;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * プレイヤーの警告・Kick・BAN・被通報履歴を確認する運営用コマンド。
 */
public final class UserHistoryCommand implements CommandExecutor, TabCompleter {

    private static final int HISTORY_LIMIT = 10;

    private final StellariaCore plugin;

    public UserHistoryCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String @NotNull [] args
    ) {
        if (!sender.hasPermission("stellaria.userhistory")) {
            sender.sendMessage(
                    plugin.getConfigManager()
                            .getMessage("userhistory.no_permission", null)
            );
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(
                    plugin.getConfigManager()
                            .getUsageMessage("userhistory.usage", null)
            );
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);

        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(
                    plugin.getConfigManager()
                            .getMessage("userhistory.player_not_found", target)
            );
            return true;
        }

        UUID targetUuid = target.getUniqueId();

        ModerationManager.Counts counts =
                plugin.getModerationManager().getCounts(targetUuid);

        List<ModerationManager.HistoryEntry> history =
                plugin.getModerationManager().getHistory(targetUuid, HISTORY_LIMIT);

        sender.sendMessage(
                plugin.getConfigManager()
                        .getMessage("userhistory.header", target)
        );

        sender.sendMessage(
                plugin.getConfigManager()
                        .getMessage("userhistory.counts", target)
                        .replace("%warns%", String.valueOf(counts.warns()))
                        .replace("%kicks%", String.valueOf(counts.kicks()))
                        .replace("%bans%", String.valueOf(counts.bans()))
                        .replace("%reports%", String.valueOf(counts.reports()))
        );

        if (history.isEmpty()) {
            sender.sendMessage(
                    plugin.getConfigManager()
                            .getMessage("userhistory.empty", target)
            );
            return true;
        }

        long now = System.currentTimeMillis();

        for (ModerationManager.HistoryEntry entry : history) {
            long elapsedSeconds =
                    Math.max(0L, (now - entry.occurredAt()) / 1000L);

            String elapsed =
                    DurationParser.formatDuration(elapsedSeconds);

            String actor = actorName(entry.actorUuid());

            sender.sendMessage(
                    plugin.getConfigManager()
                            .getMessage("userhistory.entry", target)
                            .replace("%time%", elapsed)
                            .replace("%type%", entry.type())
                            .replace("%actor%", actor)
                            .replace("%reason%", entry.reason())
            );
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String @NotNull [] args
    ) {
        if (!sender.hasPermission("stellaria.userhistory")) {
            return List.of();
        }

        if (args.length == 1) {
            return TabCompleteUtil.knownPlayerNames(args[0]);
        }

        return List.of();
    }

    private static String actorName(UUID uuid) {
        if (uuid == null) {
            return "CONSOLE";
        }

        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);

        if (player.getName() != null) {
            return player.getName();
        }

        return uuid.toString();
    }
}