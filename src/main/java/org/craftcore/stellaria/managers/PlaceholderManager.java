package org.craftcore.stellaria.managers;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.commands.WeatherVoteCommand;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.FormatUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Scoreboard/Tablist/Belowname 用の組み込み %token% を解決するクラス。
 *
 * 独自トークンを置換した後は {@link FormatUtil#text} に丸投げする（%player% 置換・
 * PlaceholderAPI 連携・カラーコード変換はそっちの役目）。PlaceholderAPI は %player% の
 * 後・独自トークンの後に実行されるので、config内に %papi_xxx% のような他プラグインの
 * プレースホルダーを混ぜて書いても問題なく解決される。
 */
public class PlaceholderManager {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final StellariaCore plugin;

    public PlaceholderManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    public String resolve(String template, Player player) {
        return resolve(template, player, player);
    }

    /**
     * テンプレートを対象プレイヤーの情報で解決する。閲覧者が対象とは別人で、対象者が所持金を
     * 非公開にしている場合、{@code %money%} は非公開メッセージに置き換える。
     *
     * @param template 解決するテンプレート
     * @param target プレースホルダー情報の対象プレイヤー
     * @param viewer 解決結果を閲覧するプレイヤー。プレイヤーでない閲覧者の場合は {@code null}
     */
    public String resolve(String template, Player target, Player viewer) {
        return FormatUtil.text(target, resolveBuiltIn(template, target, viewer));
    }

    /**
     * 複数行のテンプレートをそれぞれ解決して改行区切りのComponentにまとめる。
     * ホバーテキスト（ツールチップ）用。TPAのボタンやChatの送信者名ホバーなど、
     * 複数行ツールチップが要る場所ならどこからでも使い回せる。
     * テンプレートが空/nullなら null を返す。
     */
    public Component resolveLines(List<String> templates, Player player) {
        return resolveLines(templates, player, player);
    }

    /**
     * 複数行テンプレートを対象プレイヤーの情報で解決し、閲覧者に応じて非公開情報を伏せる。
     */
    public Component resolveLines(List<String> templates, Player target, Player viewer) {
        if (templates == null || templates.isEmpty()) {
            return null;
        }
        Component result = Component.empty();
        for (int i = 0; i < templates.size(); i++) {
            if (i > 0) {
                result = result.append(Component.newline());
            }
            result = result.append(ColorUtil.component(resolve(templates.get(i), target, viewer)));
        }
        return result;
    }

    private String resolveBuiltIn(String template, Player player, Player viewer) {
        int x = player.getLocation().getBlockX();
        int y = player.getLocation().getBlockY();
        int z = player.getLocation().getBlockZ();
        double balance = plugin.getEconomyManager().getBalance(player);
        String money = plugin.getEconomyManager().format(balance);
        if (template.contains("%money%") && shouldHideBalance(player, viewer)) {
            money = plugin.getConfigManager().getMessage("profile.balance_hidden", player);
        }

        return template
                .replace("%afk%", resolveAfkTag(player))
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%max_online%", String.valueOf(Bukkit.getMaxPlayers()))
                .replace("%tps%", String.format(Locale.ROOT, "%.1f", Math.min(20.0, Bukkit.getTPS()[0])))
                .replace("%ping%", String.valueOf(player.getPing()))
                .replace("%world%", player.getWorld().getName())
                .replace("%server%", plugin.getConfigManager().getString("server.name", ""))
                .replace("%x%", String.valueOf(x))
                .replace("%y%", String.valueOf(y))
                .replace("%z%", String.valueOf(z))
                .replace("%location%", player.getWorld().getName() + " (" + x + ", " + y + ", " + z + ")")
                .replace("%date%", LocalDateTime.now().format(DATE_FORMAT))
                .replace("%time%", LocalDateTime.now().format(TIME_FORMAT))
                .replace("%money%", money)
                // マイクラのハート表示（10ハート=満タン）に合わせて、生のHP(0〜20)を2で割った値にする
                .replace("%health%", trimTrailingZero(player.getHealth() / 2.0))
                .replace("%max_health%", trimTrailingZero(player.getMaxHealth() / 2.0));
    }

    private boolean shouldHideBalance(Player target, Player viewer) {
        return (viewer == null || !target.getUniqueId().equals(viewer.getUniqueId()))
                && plugin.getEconomyManager().isHideBalance(target);
    }

    private String resolveAfkTag(Player player) {
        if (!plugin.getAfkManager().isAfk(player.getUniqueId())) {
            return "";
        }
        return plugin.getConfigManager().getString("afk.tag", "&%7[AFK] &r");
    }

    private String trimTrailingZero(double value) {
        String formatted = String.format(Locale.ROOT, "%.1f", value);
        return formatted.endsWith(".0") ? formatted.substring(0, formatted.length() - 2) : formatted;
    }
}
