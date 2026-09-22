package org.craftcore.stellaria.rail;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;

import java.time.Duration;

/**
 * 駅到着・通過の演出（タイトル＋通知音）をまとめたstaticヘルパー。RailManagerから
 * 到着(ARRIVED)・通過（急行運転で目的駅以外を素通りする瞬間）の両方から呼ばれる。
 */
final class RailStationAnnouncement {

    private RailStationAnnouncement() {
    }

    /** stationNameはプレイヤー入力の駅名で、&%c等のカラーコードを含んでいてもよい（タイトルにそのまま使う）。 */
    static void play(StellariaCore plugin, Player player, String stationName, boolean arrived) {
        if (!plugin.getConfigManager().getBoolean("rail.arrival-announcement.enabled", true)) {
            return;
        }
        Component title = FormatUtil.component(stationName);
        String subtitleKey = arrived ? "rail.arrival_subtitle_arrived" : "rail.arrival_subtitle_passed";
        Component subtitle = FormatUtil.component(plugin.getConfigManager().getMessage(subtitleKey, player));
        Title.Times times = Title.Times.times(
                Duration.ofMillis(50L * plugin.getConfigManager().getInt("rail.arrival-announcement.title-fade-in-ticks", 5)),
                Duration.ofMillis(50L * plugin.getConfigManager().getInt("rail.arrival-announcement.title-stay-ticks", 40)),
                Duration.ofMillis(50L * plugin.getConfigManager().getInt("rail.arrival-announcement.title-fade-out-ticks", 10))
        );
        player.showTitle(Title.title(title, subtitle, times));
        playSound(plugin, player);
    }

    private static void playSound(StellariaCore plugin, Player player) {
        String soundName = plugin.getConfigManager().getString("rail.arrival-announcement.sound", "BLOCK_NOTE_BLOCK_BELL");
        Sound sound;
        try {
            sound = Sound.valueOf(soundName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("rail.arrival-announcement.sound (\"" + soundName
                    + "\") が有効な org.bukkit.Sound ではないため、通知音をスキップします。");
            return;
        }
        float volume = (float) plugin.getConfigManager().getDouble("rail.arrival-announcement.volume", 1.0);
        float pitch = (float) plugin.getConfigManager().getDouble("rail.arrival-announcement.pitch", 1.0);
        player.playSound(player.getLocation(), sound, volume, pitch);
    }
}
