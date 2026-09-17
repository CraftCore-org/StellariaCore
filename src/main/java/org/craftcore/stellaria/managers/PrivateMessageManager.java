package org.craftcore.stellaria.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤー間PM（/msg, /reply）の送信ロジック。フォーマット適用・クリックオートフィル・
 * 通知音・直前の相手記録（/reply用）をまとめて担当する。ミュート判定は呼び出し側
 * （MessageCommand）が事前に行う想定で、このクラスは「送っていい」と決まった後の処理のみ行う。
 */
public class PrivateMessageManager {

    private static final String MESSAGE_TOKEN = "%message%";

    private final StellariaCore plugin;
    private final Map<UUID, UUID> lastMessaged = new HashMap<>();

    public PrivateMessageManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** メッセージを送信し、双方向の「直前の相手」を更新する。ミュートチェック済み前提。 */
    public void send(Player sender, Player target, String rawMessage) {
        ConfigManager config = plugin.getConfigManager();
        boolean colorAllowed = config.getBoolean("message.color-codes.enabled", true)
                && sender.hasPermission(config.getString("message.color-codes.permission", "stellaria.chat.color"));
        Component messageBody = colorAllowed ? ColorUtil.component(rawMessage) : Component.text(rawMessage);

        Component senderLine = render(config.getRawMessage("msg.format_sender"), "%target%",
                ColorUtil.component(target.getName()), messageBody);
        Component receiverLine = render(config.getRawMessage("msg.format_receiver"), "%sender%",
                ColorUtil.component(sender.getName()), messageBody);

        if (config.getBoolean("chat.click-to-message", true)) {
            senderLine = attachClickToMessage(senderLine, target.getName());
            receiverLine = attachClickToMessage(receiverLine, sender.getName());
        }

        sender.sendMessage(senderLine);
        target.sendMessage(receiverLine);
        playSound(target, config);

        lastMessaged.put(sender.getUniqueId(), target.getUniqueId());
        lastMessaged.put(target.getUniqueId(), sender.getUniqueId());
    }

    /** /reply が参照する「直前にやり取りした相手」。やり取りが無ければnull。 */
    public UUID getLastMessaged(UUID uuid) {
        return lastMessaged.get(uuid);
    }

    /** 退出者を返信先として参照する全セッション状態を破棄する。 */
    public void removePlayer(UUID uuid) {
        lastMessaged.remove(uuid);
        lastMessaged.values().removeIf(uuid::equals);
    }

    private Component attachClickToMessage(Component component, String targetName) {
        Component hint = ColorUtil.component(plugin.getConfigManager().getMessage("msg.click_hint", null));
        return component
                .clickEvent(ClickEvent.suggestCommand("/msg " + targetName + " "))
                .hoverEvent(HoverEvent.showText(hint));
    }

    private void playSound(Player target, ConfigManager config) {
        if (!config.getBoolean("message.sound.enabled", true)) {
            return;
        }
        String soundName = config.getString("message.sound.name", "ENTITY_EXPERIENCE_ORB_PICKUP");
        Sound sound;
        try {
            sound = Sound.valueOf(soundName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("message.sound.name (\"" + soundName + "\") が有効な org.bukkit.Sound ではないため、通知音をスキップします。");
            return;
        }
        float volume = (float) config.getDouble("message.sound.volume", 1.0);
        float pitch = (float) config.getDouble("message.sound.pitch", 1.0);
        target.getScheduler().run(plugin, task -> target.playSound(target.getLocation(), sound, volume, pitch), null);
    }

    /** テンプレート文字列内の nameToken と %message% の位置にComponentを差し込む（BroadcastCommand.spliceの2トークン版）。 */
    private Component render(String template, String nameToken, Component nameComponent, Component messageBody) {
        Component result = Component.empty();
        int i = 0;
        while (i < template.length()) {
            int nextName = template.indexOf(nameToken, i);
            int nextMessage = template.indexOf(MESSAGE_TOKEN, i);
            int next = closest(nextName, nextMessage);
            if (next == -1) {
                result = result.append(ColorUtil.component(template.substring(i)));
                break;
            }
            if (next > i) {
                result = result.append(ColorUtil.component(template.substring(i, next)));
            }
            if (next == nextName) {
                result = result.append(nameComponent);
                i = next + nameToken.length();
            } else {
                result = result.append(messageBody);
                i = next + MESSAGE_TOKEN.length();
            }
        }
        return result;
    }

    private int closest(int a, int b) {
        if (a == -1) return b;
        if (b == -1) return a;
        return Math.min(a, b);
    }
}
