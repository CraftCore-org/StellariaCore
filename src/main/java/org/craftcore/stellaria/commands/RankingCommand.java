package org.craftcore.stellaria.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.ConfigManager;
import org.craftcore.stellaria.managers.EconomyManager;
import org.craftcore.stellaria.managers.PlaytimeManager;
import org.craftcore.stellaria.managers.StatSnapshotManager;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.DurationParser;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.RankingFormat;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * /ranking <種類> [page] コマンド。所持金・累計プレイ時間と、バニラ統計（config.yml の ranking.stats）の
 * ランキングを表示する。一覧の最後に、実行したプレイヤー自身の順位を 1 行出す。
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
        ConfigManager config = plugin.getConfigManager();
        if (!sender.hasPermission("stellaria.ranking")) {
            sender.sendMessage(config.getMessage("ranking.no_permission", null));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(config.getUsageMessage("ranking.usage", null));
            return true;
        }

        String type = args[0].toLowerCase();
        boolean isStat = plugin.getStatSnapshotManager().getEnabledKeys().contains(type);
        if (!type.equals("money") && !type.equals("playtime") && !isStat) {
            sender.sendMessage(config.getMessage("ranking.invalid_type", null)
                    .replace("%types%", String.join(", ", availableTypes())));
            return true;
        }

        // page-size: 0や負数の設定ミスでも total/0.0 => Infinity にならないよう、最低1にクランプする。
        int pageSize = Math.max(1, config.getInt("ranking.page-size", 10));
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException e) {
                page = 1;
            }
        }

        int totalPlayers = switch (type) {
            case "money" -> plugin.getEconomyManager().getPublicPlayerCount();
            case "playtime" -> plugin.getPlaytimeManager().getPlayerCount();
            default -> plugin.getStatSnapshotManager().getPublicCount(type);
        };
        int maxPage = Math.max(1, (int) Math.ceil(totalPlayers / (double) pageSize));
        page = Math.min(page, maxPage);
        // (page - 1) * pageSize はint同士だとpageが極端な値のときoverflowし得るのでlongで計算する。
        long offsetLong = (long) (page - 1) * pageSize;
        int offset = (int) Math.min(offsetLong, Integer.MAX_VALUE);

        if (isStat) {
            // 表示名に色コードが使えるよう、整形前のテンプレートに埋め込んでからまとめて整形する。
            sender.sendMessage(FormatUtil.text(null, config.getRawMessage("ranking.stat_header").replace("%stat%", statName(type))));
        } else {
            String headerKey = type.equals("money") ? "ranking.money_header" : "ranking.playtime_header";
            sender.sendMessage(config.getMessage(headerKey, null));
        }

        boolean hasEntries = switch (type) {
            case "money" -> showMoney(sender, pageSize, offset);
            case "playtime" -> showPlaytime(sender, pageSize, offset);
            default -> showStat(sender, type, pageSize, offset);
        };

        if (hasEntries) {
            sendPager(sender, type, page, maxPage);
        }
        if (sender instanceof Player player) {
            sendSelfRank(player, type, totalPlayers);
        }
        return true;
    }

    private boolean showMoney(CommandSender sender, int pageSize, int offset) {
        EconomyManager economy = plugin.getEconomyManager();
        List<EconomyManager.BalanceEntry> entries = economy.getPublicTopBalances(pageSize, offset);
        if (entries.isEmpty()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("ranking.empty", null));
            return false;
        }
        long[] ranks = RankingFormat.competitionRanks(
                entries.stream().mapToLong(EconomyManager.BalanceEntry::coins).toArray(), offset,
                RankingFormat.rank(economy.countPublicAbove(entries.get(0).coins())));
        for (int i = 0; i < entries.size(); i++) {
            EconomyManager.BalanceEntry entry = entries.get(i);
            String value = economy.formatExact(entry.coins());
            sender.sendMessage(plugin.getConfigManager().getMessage("ranking.money_entry", null)
                    .replace("%rank%", String.valueOf(ranks[i]))
                    .replace("%player%", entry.name())
                    .replace("%value%", value));
        }
        return true;
    }

    private boolean showPlaytime(CommandSender sender, int pageSize, int offset) {
        List<PlaytimeManager.PlaytimeEntry> entries = plugin.getPlaytimeManager().getTopPlaytimes(pageSize, offset);
        if (entries.isEmpty()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("ranking.empty", null));
            return false;
        }
        long[] ranks = RankingFormat.competitionRanks(
                entries.stream().mapToLong(PlaytimeManager.PlaytimeEntry::seconds).toArray(), offset,
                RankingFormat.rank(plugin.getPlaytimeManager().countPublicAbove(entries.get(0).seconds())));
        for (int i = 0; i < entries.size(); i++) {
            PlaytimeManager.PlaytimeEntry entry = entries.get(i);
            sender.sendMessage(plugin.getConfigManager().getMessage("ranking.playtime_entry", null)
                    .replace("%rank%", String.valueOf(ranks[i]))
                    .replace("%player%", entry.name())
                    .replace("%value%", DurationParser.formatDuration(entry.seconds())));
        }
        return true;
    }

    private boolean showStat(CommandSender sender, String type, int pageSize, int offset) {
        List<StatSnapshotManager.Entry> entries = plugin.getStatSnapshotManager().getTop(type, pageSize, offset);
        if (entries.isEmpty()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("ranking.empty", null));
            return false;
        }
        long[] ranks = RankingFormat.competitionRanks(
                entries.stream().mapToLong(StatSnapshotManager.Entry::value).toArray(), offset,
                RankingFormat.rank(plugin.getStatSnapshotManager().countPublicAbove(type, entries.get(0).value())));
        for (int i = 0; i < entries.size(); i++) {
            StatSnapshotManager.Entry entry = entries.get(i);
            sender.sendMessage(plugin.getConfigManager().getMessage("ranking.stat_entry", null)
                    .replace("%rank%", String.valueOf(ranks[i]))
                    .replace("%player%", entry.name())
                    .replace("%value%", RankingFormat.value(type, entry.value())));
        }
        return true;
    }

    /** 一覧の下に、実行したプレイヤー自身の順位を 1 行出す。値は DB ではなくその場の最新値を使う。 */
    private void sendSelfRank(Player player, String type, int publicCount) {
        boolean hidden;
        // publicCount に自分が含まれているか。統計は、まだスナップショットが無い新規プレイヤーだと含まれない。
        boolean counted;
        long above;
        String value;
        switch (type) {
            case "money" -> {
                double coins = plugin.getEconomyManager().getBalance(player);
                hidden = plugin.getEconomyManager().isHideBalance(player);
                above = plugin.getEconomyManager().countPublicAbove(coins);
                value = plugin.getEconomyManager().formatExact(coins);
                counted = !hidden;
            }
            case "playtime" -> {
                long seconds = plugin.getPlaytimeManager().getPlaytimeSeconds(player.getUniqueId());
                hidden = plugin.getStatSnapshotManager().isHidden(player);
                above = plugin.getPlaytimeManager().countPublicAbove(seconds);
                value = DurationParser.formatDuration(seconds);
                counted = !hidden;
            }
            default -> {
                long live = plugin.getStatSnapshotManager().readLive(player, type);
                hidden = plugin.getStatSnapshotManager().isHidden(player);
                above = plugin.getStatSnapshotManager().countPublicAbove(type, live);
                value = RankingFormat.value(type, live);
                counted = !hidden && StatSnapshotManager.isListed(player.getUniqueId(), type);
            }
        }
        String key = hidden ? "ranking.self_rank_hidden" : "ranking.self_rank";
        player.sendMessage(plugin.getConfigManager().getMessage(key, player)
                .replace("%rank%", String.valueOf(RankingFormat.rank(above)))
                .replace("%total%", String.valueOf(RankingFormat.totalWithSelf(publicCount, counted)))
                .replace("%value%", value));
    }

    /** messages.yml の ranking.stat_names.<種類>。未設定ならキーをそのまま表示する。 */
    private String statName(String type) {
        String name = plugin.getConfigManager().getRawMessage("ranking.stat_names." + type);
        return name == null || name.isEmpty() ? type : name;
    }

    private List<String> availableTypes() {
        List<String> types = new ArrayList<>(List.of("money", "playtime"));
        types.addAll(plugin.getStatSnapshotManager().getEnabledKeys());
        return types;
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
            return TabCompleteUtil.filterStartsWith(availableTypes(), args[0]);
        }
        return List.of();
    }
}
