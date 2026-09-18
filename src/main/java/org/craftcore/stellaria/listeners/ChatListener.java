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

        plugin.getDiscordBotManager().mcChatToDiscord(event);

        String format = config.getString("chat.format", "{placeholder}{sender}&%7: &%f{message}");
        String placeholderTemplate = config.getString("chat.placeholder", "");

        String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        Component message = mentionService.highlight(plainMessage, sender, colorCodesPermitted(config, sender));
        boolean clickToMessage = config.getBoolean("chat.click-to-message", true);
        RankManager.RankInfo rank = plugin.getRankManager().getRank(sender);
        Component rankPrefix = ColorUtil.component(rank.color() + config.getRawMessage("chat.sender_prefix"));

        event.renderer((source, sourceDisplayName, ignoredMessage, audience) -> {
            Player viewer = audience instanceof Player player ? player : null;
            Component placeholder = ColorUtil.component(plugin.getPlaceholderManager()
                    .resolve(placeholderTemplate, sender, viewer));
            Component tooltip = buildTooltip(config, sender, viewer, clickToMessage, rank);
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

        if (!clickToMessage) {
            return combined;
        }
        Component hint = ColorUtil.component(config.getMessage("chat.click_hint", sender));
        return combined != null ? combined.append(Component.newline()).append(hint) : hint;
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
