package org.craftcore.stellaria.utils;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * コマンドのタブ補完でよく使う「候補の絞り込み」をまとめたユーティリティ。
 */
public final class TabCompleteUtil {

    private TabCompleteUtil() {
    }

    /** オンラインプレイヤー名のうち partial から始まるもの（大文字小文字区別なし）。 */
    public static List<String> onlinePlayerNames(String partial) {
        String lower = partial.toLowerCase();
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(lower))
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 一度でもこのサーバーに来たことがあるプレイヤー名のうち partial から始まるもの
     * （/mute・/eco のようにオフライン対象も許可するコマンド用）。
     */
    public static List<String> knownPlayerNames(String partial) {
        String lower = partial.toLowerCase();
        return Arrays.stream(Bukkit.getOfflinePlayers())
                .map(OfflinePlayer::getName)
                .filter(name -> name != null && name.toLowerCase().startsWith(lower))
                .sorted()
                .collect(Collectors.toList());
    }

    /** 固定候補一覧から partial に前方一致するものだけ絞り込む（サブコマンド用）。 */
    public static List<String> filterStartsWith(List<String> candidates, String partial) {
        String lower = partial.toLowerCase();
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase().startsWith(lower))
                .collect(Collectors.toList());
    }
}
