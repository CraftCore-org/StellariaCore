package org.craftcore.stellaria.utils;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

public class FormatUtil {

    private static final boolean HAS_PAPI = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");

    private FormatUtil() {
        // ユーティリティクラスのためインスタンス化を禁止
    }

    /**
     * カラーコード変換（16進数 HEX や &% カスタムカラーも自動適用）
     */
    public static String color(String text) {
        return ColorUtil.colorize(text); // Color.java のパース処理を使用！
    }

    /**
     * Adventure Component 形式で取得したい場合
     */
    public static Component component(String text) {
        return ColorUtil.component(text); // Color.java の Component 変換を使用！
    }

    /**
     * テキストのフル整形（PAPI適用 ＋ カラーコード・HEX変換）
     */
    public static String text(OfflinePlayer player, String text) {
        if (text == null) return "";

        // 1 独自プレースホルダーの自動置換（%player% をプレイヤー名に）
        if (player != null && player.getName() != null) {
            text = text.replace("%player%", player.getName());
        }

        // 2 PlaceholderAPI が導入されていれば %player_health% などのPAPI変数も一括置換
        if (HAS_PAPI && player != null) {
            text = PlaceholderAPI.setPlaceholders(player, text);
        }

        // 3 Color.java を使って &a や &#RRGGBB などの色コードを最終変換して返す
        return ColorUtil.colorize(text);
    }

    /**
     * 独自プレースホルダーの置き換え
     */
    public static String replace(String text, String target, String replacement) {
        if (text == null) return "";
        return text.replace(target, replacement);
    }
}