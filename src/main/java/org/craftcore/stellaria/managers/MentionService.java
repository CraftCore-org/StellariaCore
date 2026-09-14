package org.craftcore.stellaria.managers;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * チャットメッセージ内の @name / @all メンションを検出してハイライトし、メンションされた
 * プレイヤーに通知音を鳴らすクラス。{@code listeners.ChatListener} の公開チャット処理からのみ使う。
 * orelia-serverutil の {@code MentionService} を移植したもの。
 */
public class MentionService {

    private static final Pattern MENTION_PATTERN = Pattern.compile("@(\\w+)");

    private final StellariaCore plugin;

    public MentionService(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /**
     * {@code plainMessage} 内の @name / @all を色付きハイライトに置き換えたComponentを返す
     * （認識できない @word はリテラルのまま残す）。メンションされた相手には通知音を鳴らす。
     *
     * @param colorizeLiteral メンション以外の部分もカラーコード変換するかどうか
     *                        （送信者が自分のメッセージに色を使う権限を持っているかに対応）
     */
    public Component highlight(String plainMessage, Player sender, boolean colorizeLiteral) {
        ConfigManager config = plugin.getConfigManager();
        if (!config.getBoolean("mention.enabled", true)) {
            return literal(plainMessage, colorizeLiteral);
        }

        String format = config.getString("mention.format", "&%6[&%e@{name}&%6]&r");
        boolean allEnabled = config.getBoolean("mention.all.enabled", true);
        String allKeyword = config.getString("mention.all.keyword", "all");
        String allPermission = config.getString("mention.all.permission", "stellaria.chat.mention.all");

        Set<Player> mentioned = new LinkedHashSet<>();
        Matcher matcher = MENTION_PATTERN.matcher(plainMessage);
        Component result = Component.empty();
        int lastEnd = 0;
        while (matcher.find()) {
            String token = matcher.group(1);
            boolean isAll = allEnabled && token.equalsIgnoreCase(allKeyword) && sender.hasPermission(allPermission);
            Player target = isAll ? null : findOnlinePlayer(token);
            if (!isAll && target == null) {
                continue; // 認識できないメンションではないので、"@token" をリテラルのまま残す
            }

            if (matcher.start() > lastEnd) {
                result = result.append(literal(plainMessage.substring(lastEnd, matcher.start()), colorizeLiteral));
            }
            String displayName = isAll ? allKeyword : target.getName();
            result = result.append(ColorUtil.component(format.replace("{name}", displayName)));
            lastEnd = matcher.end();

            if (isAll) {
                mentioned.addAll(Bukkit.getOnlinePlayers());
            } else {
                mentioned.add(target);
            }
        }
        if (lastEnd < plainMessage.length()) {
            result = result.append(literal(plainMessage.substring(lastEnd), colorizeLiteral));
        }

        mentioned.remove(sender);
        playSound(mentioned);
        return result;
    }

    private Component literal(String text, boolean colorize) {
        return colorize ? ColorUtil.component(text) : Component.text(text);
    }

    private Player findOnlinePlayer(String name) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    private void playSound(Set<Player> mentioned) {
        ConfigManager config = plugin.getConfigManager();
        if (mentioned.isEmpty() || !config.getBoolean("mention.sound.enabled", true)) {
            return;
        }
        String soundName = config.getString("mention.sound.name", "ENTITY_EXPERIENCE_ORB_PICKUP");
        Sound sound;
        try {
            sound = Sound.valueOf(soundName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("mention.sound.name (\"" + soundName + "\") が有効な org.bukkit.Sound ではないため、通知音をスキップします。");
            return;
        }
        float volume = (float) config.getDouble("mention.sound.volume", 1.0);
        float pitch = (float) config.getDouble("mention.sound.pitch", 1.0);

        // AsyncChatEvent はメインスレッド外で発火することがあるので、再生はプレイヤーごとの
        // エンティティスケジューラ経由で行う（Folia環境でも安全に動くように）。
        for (Player player : mentioned) {
            player.getScheduler().run(plugin, task -> player.playSound(player.getLocation(), sound, volume, pitch), null);
        }
    }
}
