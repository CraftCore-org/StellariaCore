package org.craftcore.stellaria.listeners;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.ConfigManager;
import org.craftcore.stellaria.managers.MentionService;
import org.craftcore.stellaria.utils.ColorUtil;

/**
 * チャットメッセージのフォーマットを適用するリスナー。
 *
 * config.yml の {@code chat.format} は {@code {sender}}/{@code {placeholder}}/{@code {message}}
 * という3つの「構造スロット」を持つ文字列で、ここでは各スロットの位置をそのまま文字列検索して
 * 対応するComponentを差し込むだけ（{@code PlaceholderManager} の {@code %token%} とは別物）。
 * {@code chat.placeholder} の中身（例: {@code "%ping%ms"}）だけは {@code PlaceholderManager}
 * で解決する。
 *
 * orelia-serverutil の {@code ChatModule} を移植したもの（ホバーツールチップと、他プラグイン
 * 向けのProvider拡張ポイントは含まない）。
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
        ConfigManager config = plugin.getConfigManager();
        Player sender = event.getPlayer();

        String format = config.getString("chat.format", "{placeholder}{sender}&%7: &%f{message}");
        String placeholderTemplate = config.getString("chat.placeholder", "");
        Component placeholder = ColorUtil.component(plugin.getPlaceholderManager().resolve(placeholderTemplate, sender));

        String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        Component message = mentionService.highlight(plainMessage, sender, colorCodesPermitted(config, sender));

        event.renderer(ChatRenderer.viewerUnaware((source, sourceDisplayName, ignoredMessage) ->
                render(format, sourceDisplayName, placeholder, message)));
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
