package org.craftcore.stellaria.managers;

import org.bukkit.OfflinePlayer;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;

/**
 * config.yml へのアクセスをまとめて管理するクラス。
 *
 * 各クラスが {@code plugin.getConfig()} を直接呼ぶのをやめて、ここを経由させることで
 * 「どこで config を読んでいるか」を一箇所にまとめる。reload() で config.yml を読み直せる。
 */
public class ConfigManager {

    private final StellariaCore plugin;

    public ConfigManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /**
     * config.yml をディスクから読み直す。/stellariareload コマンドから呼ばれる想定。
     */
    public void reload() {
        plugin.reloadConfig();
    }

    /**
     * 文字列の設定値を取得する。存在しない場合は def を返す。
     */
    public String getString(String path, String def) {
        return plugin.getConfig().getString(path, def);
    }

    /**
     * 整数の設定値を取得する。存在しない場合は def を返す。
     */
    public int getInt(String path, int def) {
        return plugin.getConfig().getInt(path, def);
    }

    /**
     * メッセージ用の設定値を取得する。
     * {@link FormatUtil#text(OfflinePlayer, String)} を通すので、%player% 置換・PlaceholderAPI・
     * カラーコード変換（&#RRGGBB や &% カスタムパレット含む）まで全部乗った状態の文字列が返る。
     *
     * @param path             config.yml 上のパス
     * @param placeholderPlayer メッセージ内の %player% などのプレースホルダーに使うプレイヤー
     */
    public String getMessage(String path, OfflinePlayer placeholderPlayer) {
        return FormatUtil.text(placeholderPlayer, plugin.getConfig().getString(path, ""));
    }
}
