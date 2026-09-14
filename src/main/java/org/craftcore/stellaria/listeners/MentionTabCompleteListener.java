package org.craftcore.stellaria.listeners;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.stream.Collectors;

/**
 * チャット入力欄（コマンドではない、通常のチャットメッセージ入力）で "@" の直後にTabを押すと、
 * オンラインプレイヤー名を補完候補として出すリスナー。 {@code MentionService} の @name メンションと
 * 対になる入力補助。
 */
public class MentionTabCompleteListener implements Listener {

    @EventHandler
    public void onTabComplete(AsyncTabCompleteEvent event) {
        if (event.isCommand()) {
            return;
        }
        CommandSender sender = event.getSender();
        if (!(sender instanceof Player)) {
            return;
        }

        String buffer = event.getBuffer();
        int at = buffer.lastIndexOf('@');
        if (at == -1) {
            return;
        }
        // "@" の直前が空白（または入力の先頭）でなければ、メールアドレス等の一部とみなして無視する
        if (at > 0 && !Character.isWhitespace(buffer.charAt(at - 1))) {
            return;
        }

        String partial = buffer.substring(at + 1);
        if (partial.contains(" ")) {
            // "@" を含む単語の入力が既に完了して次の単語に進んでいる
            return;
        }

        String partialLower = partial.toLowerCase();
        List<String> completions = Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(partialLower))
                .map(name -> "@" + name)
                .collect(Collectors.toList());

        if (!completions.isEmpty()) {
            event.setCompletions(completions);
            event.setHandled(true);
        }
    }
}
