package org.craftcore.stellaria.managers;

import org.bukkit.OfflinePlayer;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;

import java.io.File;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YAML設定ファイルをまとめて管理するクラス。
 *
 * 各クラスが {@code plugin.getConfig()} を直接呼ぶのをやめて、ここを経由させることで
 * 「どこで config を読んでいるか」を一箇所にまとめる。{@code config.yml}（サーバー設定）と
 * {@code messages.yml}（メッセージ）の複数ファイルを {@link #register(String)} で登録して扱う。
 * orelia-serverutil の {@code ConfigManager} を移植したもの（設定ファイルの自動移行機能は含まない）。
 */
public final class ConfigManager {

    private final StellariaCore plugin;
    private final Map<String, ConfigFile> files = new LinkedHashMap<>();

    public ConfigManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /**
     * 設定ファイルを登録する。まだディスクに無ければ jar に同梱されたデフォルトをコピーする。
     * 登録済みなら既存のものをそのまま返す。
     */
    public ConfigFile register(String fileName) {
        return files.computeIfAbsent(fileName, name -> {
            if (plugin.getResource(name) != null && !new File(plugin.getDataFolder(), name).exists()) {
                plugin.saveResource(name, false);
            }
            return new ConfigFile(plugin.getLogger(), plugin.getDataFolder(), name);
        });
    }

    /**
     * 登録済みの設定ファイルを取得する。{@link #register(String)} を先に呼んでいないと例外になる。
     */
    public ConfigFile get(String fileName) {
        ConfigFile file = files.get(fileName);
        if (file == null) {
            throw new IllegalStateException("設定ファイルが登録されていません: " + fileName);
        }
        return file;
    }

    /**
     * 登録済みの設定ファイルを全部ディスクから読み直す。/stellariareload コマンドから呼ばれる想定。
     */
    public void reload() {
        files.values().forEach(ConfigFile::reload);
    }

    /**
     * config.yml の文字列設定値を取得する。存在しない場合は def を返す。
     */
    public String getString(String path, String def) {
        return get("config.yml").get().getString(path, def);
    }

    /**
     * config.yml の整数設定値を取得する。存在しない場合は def を返す。
     */
    public int getInt(String path, int def) {
        return get("config.yml").get().getInt(path, def);
    }

    /**
     * config.yml の真偽値設定を取得する。存在しない場合は def を返す。
     */
    public boolean getBoolean(String path, boolean def) {
        return get("config.yml").get().getBoolean(path, def);
    }

    /**
     * config.yml の文字列リスト設定を取得する（例: scoreboard.lines）。存在しない場合は空リスト。
     */
    public List<String> getStringList(String path) {
        return get("config.yml").get().getStringList(path);
    }

    /**
     * config.yml の小数設定を取得する（例: mention.sound.volume）。存在しない場合は def を返す。
     */
    public double getDouble(String path, double def) {
        return get("config.yml").get().getDouble(path, def);
    }

    /**
     * messages.yml のメッセージを取得する。
     * {@link FormatUtil#text(OfflinePlayer, String)} を通すので、%player% 置換・PlaceholderAPI・
     * カラーコード変換（&#RRGGBB や &% カスタムパレット含む）まで全部乗った状態の文字列が返る。
     *
     * @param path             messages.yml 上のパス
     * @param placeholderPlayer メッセージ内の %player% などのプレースホルダーに使うプレイヤー
     */
    public String getMessage(String path, OfflinePlayer placeholderPlayer) {
        return FormatUtil.text(placeholderPlayer, get("messages.yml").get().getString(path, ""));
    }

    /**
     * messages.yml の文字列を未加工のまま取得する（%player%/PAPI/色変換をしない）。
     * BroadcastCommand が %message% の位置にColorEventを持つComponentを差し込むために使う。
     * 単に送信するだけなら {@link #getMessage(String, OfflinePlayer)} を使うこと。
     */
    public String getRawMessage(String path) {
        return get("messages.yml").get().getString(path, "");
    }

    /**
     * messages.yml の文字列リストを未加工のまま取得する（例: tpa.tpa_accept_tooltip）。
     * プレースホルダー解決・色変換は呼び出し側で {@code PlaceholderManager#resolveLines} を使うこと。
     */
    public List<String> getMessageList(String path) {
        return get("messages.yml").get().getStringList(path);
    }
}
