package org.craftcore.stellaria.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.ConfigManager;
import org.craftcore.stellaria.managers.MentionService;
import org.craftcore.stellaria.managers.MuteManager;
import org.craftcore.stellaria.managers.RankManager;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.DurationParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * チャットメッセージのフォーマットを適用するリスナー。
 *
 * config.yml の {@code chat.format} は {@code {sender}}/{@code {placeholder}}/{@code {message}}
 * という3つの「構造スロット」を持つ文字列で、ここでは各スロットの位置をそのまま文字列検索して
 * 対応するComponentを差し込むだけ（{@code PlaceholderManager} の {@code %token%} とは別物）。
 * {@code chat.placeholder} の中身（例: {@code "%ping%ms"}）だけは {@code PlaceholderManager}
 * で解決する。
 *
 * orelia-serverutil の {@code ChatModule} を移植したもの（他プラグイン向けのProvider
 * 拡張ポイントは含まない）。送信前に {@code MuteManager} でLv1以上のミュートをチェックし、
 * ミュート中ならイベントをキャンセルする。
 */
public class ChatListener implements Listener {

    private final StellariaCore plugin;
    private final MentionService mentionService;

    public ChatListener(StellariaCore plugin, MentionService mentionService) {
        this.plugin = plugin;
        this.mentionService = mentionService;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (event.isCancelled()) {
            return;
        }
        ConfigManager config = plugin.getConfigManager();
        Player sender = event.getPlayer();

        MuteManager.MuteRecord muteRecord = plugin.getMuteManager()
                .getRestrictingRecord(sender.getUniqueId(), MuteManager.MuteScope.CHAT);
        if (muteRecord != null) {
            event.setCancelled(true);
            String blockedMessage = config.getMessage("mute.blocked_chat", sender)
                    .replace("%remaining%", DurationParser.formatRemaining(muteRecord.expiresAt()))
                    .replace("%reason%", muteRecord.reason());
            // AsyncChatEvent はメインスレッド外で発火することがあるので、送信はプレイヤーごとの
            // エンティティスケジューラ経由で行う（Folia環境でも安全に動くように）。
            sender.getScheduler().run(plugin, task -> sender.sendMessage(blockedMessage), null);
            return;
        }

        String rawPlainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        int nearTier = config.getBoolean("chat.near.enabled", true)
                ? Math.min(countLeadingNearMarkers(rawPlainMessage), 3)
                : 0;
        String plainMessage = nearTier > 0 ? stripNearMarkers(rawPlainMessage) : rawPlainMessage;

        List<Player> nearbyPlayers = null;
        if (nearTier > 0) {
            int range = config.getInt("chat.near.range-" + nearTier, defaultNearRange(nearTier));
            nearbyPlayers = nearbyPlayers(sender, range);
            Set<Player> nearbySet = new HashSet<>(nearbyPlayers);
            event.viewers().removeIf(audience -> audience instanceof Player player && !nearbySet.contains(player));
            logNearChat(sender, nearTier, range, nearbyPlayers, plainMessage);
        } else {
            plugin.getDiscordBotManager().mcChatToDiscord(event);
        }

        String format = nearTier > 0
                ? config.getString("chat.near.format-" + nearTier, defaultNearFormat(nearTier))
                : config.getString("chat.format", "{placeholder}{sender}&%7: &%f{message}");
        String placeholderTemplate = config.getString("chat.placeholder", "");
        Component message = mentionService.highlight(plainMessage, sender, colorCodesPermitted(config, sender));
        boolean clickToMessage = config.getBoolean("chat.click-to-message", true);
        RankManager.RankInfo rank = plugin.getRankManager().getRank(sender);
        Component rankPrefix = ColorUtil.component(rank.color() + config.getRawMessage("chat.sender_prefix"));
        Component nearViewersTooltip = nearTier > 0 ? buildNearViewersTooltip(config, sender, nearbyPlayers) : null;

        event.renderer((source, sourceDisplayName, ignoredMessage, audience) -> {
            Player viewer = audience instanceof Player player ? player : null;
            Component placeholder = ColorUtil.component(plugin.getPlaceholderManager()
                    .resolve(placeholderTemplate, sender, viewer));
            Component tooltip = nearViewersTooltip != null
                    ? nearViewersTooltip
                    : buildTooltip(config, sender, viewer, clickToMessage, rank);
            Component nameComponent = rankPrefix.append(
                    sourceDisplayName.decoration(TextDecoration.BOLD, false).color(NamedTextColor.WHITE));
            nameComponent = tooltip != null
                    ? nameComponent.hoverEvent(HoverEvent.showText(tooltip))
                    : nameComponent;
            if (clickToMessage) {
                nameComponent = nameComponent.clickEvent(ClickEvent.suggestCommand("/msg " + sender.getName() + " "));
            }
            return render(format, nameComponent, placeholder, message);
        });
    }

    /** メッセージ先頭に連続する {@code !} の数（近距離チャットの段階トリガー）を数える。 */
    private int countLeadingNearMarkers(String text) {
        int count = 0;
        while (count < text.length() && text.charAt(count) == '!') {
            count++;
        }
        return count;
    }

    /** 先頭の {@code !} 群（と、その直後に1つだけあるスペース）を取り除く。 */
    private String stripNearMarkers(String text) {
        String stripped = text.substring(countLeadingNearMarkers(text));
        return stripped.startsWith(" ") ? stripped.substring(1) : stripped;
    }

    private int defaultNearRange(int tier) {
        return switch (tier) {
            case 1 -> 8;
            case 2 -> 16;
            default -> 32;
        };
    }

    private String defaultNearFormat(int tier) {
        String tagColor = switch (tier) {
            case 1 -> "&%a";
            case 2 -> "&%e";
            default -> "&%6";
        };
        return tagColor + "[近距離] {placeholder}{sender}" + tagColor + ": &%f{message}";
    }

    /** 近距離チャットの内容と実際の受信者を、監査用にプラグインロガー（サーバーログ）へ残す。 */
    private void logNearChat(Player sender, int tier, int range, List<Player> nearbyPlayers, String message) {
        String recipients = nearbyPlayers.stream()
                .filter(player -> !player.equals(sender))
                .map(Player::getName)
                .sorted()
                .reduce((a, b) -> a + ", " + b)
                .orElse("なし");
        plugin.getLogger().info(String.format("[近距離チャット] %s (%d段階/%dm) -> %s: %s",
                sender.getName(), tier, range, recipients, message));
    }

    /** 送信者と同じワールドで、半径 {@code range} ブロック以内にいるプレイヤー（送信者自身を含む）。 */
    private List<Player> nearbyPlayers(Player sender, double range) {
        List<Player> result = new ArrayList<>();
        double rangeSquared = range * range;
        for (Player player : sender.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(sender.getLocation()) <= rangeSquared) {
                result.add(player);
            }
        }
        return result;
    }

    /**
     * 近距離チャットの送信者名にホバーした時に出す「見た人一覧」ツールチップ。
     * Vanish中のプレイヤーは一覧から除外する（メッセージ自体は届くが、見た人には表示しない）。
     */
    private Component buildNearViewersTooltip(ConfigManager config, Player sender, List<Player> nearbyPlayers) {
        List<Player> visible = nearbyPlayers.stream()
                .filter(player -> !plugin.getVanishManager().isVanished(player.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName))
                .toList();

        if (visible.size() <= 1) {
            return ColorUtil.component(config.getMessage("chat.near.viewers_none", sender));
        }

        int maxShown = config.getInt("chat.near.max-viewers-shown", 7);
        String joined = visible.stream().map(Player::getName).limit(maxShown).reduce((a, b) -> a + ", " + b).orElse("");
        String line = config.getMessage("chat.near.viewers_line", sender).replace("%viewers%", joined);
        if (visible.size() > maxShown) {
            line += config.getMessage("chat.near.viewers_overflow", sender)
                    .replace("%count%", String.valueOf(visible.size() - maxShown));
        }
        return ColorUtil.component(line);
    }

    /**
     * 送信者名にホバーした時に出すツールチップ（{@code chat.tooltip.*} + クリック案内）。
     * ツールチップもクリック案内も無ければ null。
     */
    private Component buildTooltip(ConfigManager config, Player sender, Player viewer, boolean clickToMessage, RankManager.RankInfo rank) {
        Component rankLine = rank.displayName().isEmpty()
                ? null
                : ColorUtil.component(config.getRawMessage("chat.tooltip.rank_line")
                        .replace("%rank%", rank.color() + rank.displayName()));

        Component linesTooltip = config.getBoolean("chat.tooltip.enabled", true)
                ? plugin.getPlaceholderManager().resolveLines(config.getStringList("chat.tooltip.lines"), sender, viewer)
                : null;
        Component combined = rankLine;
        if (linesTooltip != null) {
            combined = combined != null ? combined.append(Component.newline()).append(linesTooltip) : linesTooltip;
        }

        if (clickToMessage) {
            Component clickHint = ColorUtil.component(config.getMessage("chat.click_hint", sender));
            combined = combined != null ? combined.append(Component.newline()).append(clickHint) : clickHint;
        }

        if (config.getBoolean("chat.near.enabled", true)) {
            Component nearHint = ColorUtil.component(config.getMessage("chat.near.hint", sender));
            combined = combined != null ? combined.append(Component.newline()).append(nearHint) : nearHint;
        }
        return combined;
    }

    /**
     * 送信者自身が自分のメッセージ内で &カラーコードを使う権限を持っているか
     * （{@code chat.color-codes.permission}、デフォルトはOPのみに解決される）。
     */
    private boolean colorCodesPermitted(ConfigManager config, Player sender) {
        if (!config.getBoolean("chat.color-codes.enabled", true)) {
            return false;
        }
        String permission = config.getString("chat.color-codes.permission", "stellaria.chat.color");
        return sender.hasPermission(permission);
    }

    private Component render(String template, Component sender, Component placeholder, Component message) {
        Component result = Component.empty();
        int i = 0;
        while (i < template.length()) {
            int nextSender = template.indexOf("{sender}", i);
            int nextPlaceholder = template.indexOf("{placeholder}", i);
            int nextMessage = template.indexOf("{message}", i);
            int next = closest(nextSender, nextPlaceholder, nextMessage);
            if (next == -1) {
                result = result.append(ColorUtil.component(template.substring(i)));
                break;
            }
            if (next > i) {
                result = result.append(ColorUtil.component(template.substring(i, next)));
            }
            if (next == nextSender) {
                result = result.append(sender);
                i = next + "{sender}".length();
            } else if (next == nextPlaceholder) {
                result = result.append(placeholder);
                i = next + "{placeholder}".length();
            } else {
                result = result.append(message);
                i = next + "{message}".length();
            }
        }
        return result;
    }

    private int closest(int... positions) {
        int closest = -1;
        for (int position : positions) {
            if (position != -1 && (closest == -1 || position < closest)) {
                closest = position;
            }
        }
        return closest;
    }
}
