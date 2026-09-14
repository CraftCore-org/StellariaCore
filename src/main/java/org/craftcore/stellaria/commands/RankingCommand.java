package org.craftcore.stellaria.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.ConfigManager;
import org.craftcore.stellaria.managers.EconomyManager;
import org.craftcore.stellaria.managers.PlaytimeManager;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.DurationParser;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /ranking <money|playtime> [page] コマンド。所持金・累計プレイ時間のランキングを表示する。
 * 旧 /balance top から移行（今後ランキング種類が増えても1コマンドに集約できるように）。
 * 一覧の下に前/次ページの矢印（クリックで {@code /ranking <type> <page>} を実行）を付ける。
 */
public class RankingCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public RankingCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.ranking")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("ranking.no_permission", null));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.getConfigManager().getMessage("ranking.usage", null));
            return true;
        }

        String type = args[0].toLowerCase();
        if (!type.equals("money") && !type.equals("playtime")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("ranking.invalid_type", null));
            return true;
        }

        int pageSize = plugin.getConfigManager().getInt("ranking.page-size", 10);
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException e) {
                page = 1;
            }
        }

        int totalPlayers = type.equals("money")
                ? plugin.getEconomyManager().getPlayerCount()
                : plugin.getPlaytimeManager().getPlayerCount();
        int maxPage = Math.max(1, (int) Math.ceil(totalPlayers / (double) pageSize));
        page = Math.min(page, maxPage);
        int offset = (page - 1) * pageSize;

        String headerKey = type.equals("money") ? "ranking.money_header" : "ranking.playtime_header";
        sender.sendMessage(plugin.getConfigManager().getMessage(headerKey, null));

        boolean hasEntries = type.equals("money")
                ? showMoney(sender, pageSize, offset)
                : showPlaytime(sender, pageSize, offset);

        if (hasEntries) {
            sendPager(sender, type, page, maxPage);
        }
        return true;
    }

    private boolean showMoney(CommandSender sender, int pageSize, int offset) {
        EconomyManager economy = plugin.getEconomyManager();
        List<EconomyManager.BalanceEntry> entries = economy.getTopBalances(pageSize, offset);
        if (entries.isEmpty()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("ranking.empty", null));
            return false;
        }
        int rank = offset + 1;
        for (EconomyManager.BalanceEntry entry : entries) {
            sender.sendMessage(plugin.getConfigManager().getMessage("ranking.money_entry", null)
                    .replace("%rank%", String.valueOf(rank))
                    .replace("%player%", entry.name())
                    .replace("%value%", economy.format(entry.coins())));
            rank++;
        }
        return true;
    }

    private boolean showPlaytime(CommandSender sender, int pageSize, int offset) {
        List<PlaytimeManager.PlaytimeEntry> entries = plugin.getPlaytimeManager().getTopPlaytimes(pageSize, offset);
        if (entries.isEmpty()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("ranking.empty", null));
            return false;
        }
        int rank = offset + 1;
        for (PlaytimeManager.PlaytimeEntry entry : entries) {
            sender.sendMessage(plugin.getConfigManager().getMessage("ranking.playtime_entry", null)
                    .replace("%rank%", String.valueOf(rank))
                    .replace("%player%", entry.name())
                    .replace("%value%", DurationParser.formatDuration(entry.seconds())));
            rank++;
        }
        return true;
    }

    /** 一覧の下に「◀ 2/5 ▶」のようなページャーを表示する。前後が無い側の矢印はクリック不可の薄い表示にする。 */
    private void sendPager(CommandSender sender, String type, int page, int maxPage) {
        ConfigManager config = plugin.getConfigManager();

        Component prev = page > 1
                ? ColorUtil.component(config.getRawMessage("ranking.prev_arrow"))
                        .clickEvent(ClickEvent.runCommand("/ranking " + type + " " + (page - 1)))
                : ColorUtil.component(config.getRawMessage("ranking.prev_arrow_disabled"));

        Component next = page < maxPage
                ? ColorUtil.component(config.getRawMessage("ranking.next_arrow"))
                        .clickEvent(ClickEvent.runCommand("/ranking " + type + " " + (page + 1)))
                : ColorUtil.component(config.getRawMessage("ranking.next_arrow_disabled"));

        Component indicator = ColorUtil.component(config.getRawMessage("ranking.page_indicator")
                .replace("%page%", String.valueOf(page))
                .replace("%max_page%", String.valueOf(maxPage)));

        sender.sendMessage(Component.text("  ")
                .append(prev)
                .append(Component.text("  "))
                .append(indicator)
                .append(Component.text("  "))
                .append(next));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(List.of("money", "playtime"), args[0]);
        }
        return List.of();
    }
}
