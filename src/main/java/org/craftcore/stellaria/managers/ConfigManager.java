package org.craftcore.stellaria.managers;

import org.bukkit.OfflinePlayer;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.UsageFormatUtil;

import java.io.File;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    // 「このキーは無かった」の警告を1キーにつき1回だけ出すための記録（reloadで作り直す）。
    private final Set<String> warnedMissingKeys = ConcurrentHashMap.newKeySet();

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
     * 「キーが無い」警告も出し直せるように記録をリセットする。
     */
    public void reload() {
        files.values().forEach(ConfigFile::reload);
        warnedMissingKeys.clear();
    }

    /**
     * {@code fileName} に {@code path} が無ければ、コンソールに1回だけ警告を出す
     * （tickごとに呼ばれる箇所での連続警告を防ぐため、reload()するまで同じキーは出し直さない）。
     * jar同梱のデフォルトはファイルが新規作成された時にしかコピーされない（移行機能が無いため）ので、
     * 既存の config.yml/messages.yml をアップデートで上書きし忘れた時にこの警告で気付ける。
     */
    private void warnIfMissing(String fileName, String path) {
        if (get(fileName).get().contains(path)) {
            return;
        }
        if (warnedMissingKeys.add(fileName + ":" + path)) {
            plugin.getLogger().warning("エラー: " + fileName + " に \"" + path + "\" が見つかりません。デフォルト値を使用します。");
        }
    }

    /**
     * config.yml の文字列設定値を取得する。存在しない場合は def を返す。
     */
    public String getString(String path, String def) {
        warnIfMissing("config.yml", path);
        return get("config.yml").get().getString(path, def);
    }
    public String getString(String path, String def, Boolean ignoreWarn) {
        if (!ignoreWarn) warnIfMissing("config.yml", path);
        return get("config.yml").get().getString(path, def);
    }

    /**
     * config.yml の整数設定値を取得する。存在しない場合は def を返す。
     */
    public int getInt(String path, int def) {
        warnIfMissing("config.yml", path);
        return get("config.yml").get().getInt(path, def);
    }
    public int getInt(String path, int def, Boolean ignoreWarn) {
        if (!ignoreWarn) warnIfMissing("config.yml", path);
        return get("config.yml").get().getInt(path, def);
    }

    /**
     * config.yml の真偽値設定を取得する。存在しない場合は def を返す。
     */
    public boolean getBoolean(String path, boolean def) {
        warnIfMissing("config.yml", path);
        return get("config.yml").get().getBoolean(path, def);
    }
    public boolean getBoolean(String path, boolean def,Boolean ignoreWarn) {
        if (!ignoreWarn) warnIfMissing("config.yml", path);
        return get("config.yml").get().getBoolean(path, def);
    }

    /**
     * config.yml の文字列リスト設定を取得する（例: scoreboard.lines）。存在しない場合は空リスト。
     */
    public List<String> getStringList(String path) {
        warnIfMissing("config.yml", path);
        return get("config.yml").get().getStringList(path);
    }
    public List<String> getStringList(String path, Boolean ignoreWarn) {
        if (!ignoreWarn) warnIfMissing("config.yml", path);
        return get("config.yml").get().getStringList(path);
    }

    /**
     * config.yml のマップリスト設定を取得する（例: rank.groups のような、複数キーを持つ
     * オブジェクトのリスト）。存在しない場合は空リスト。
     */
    public List<Map<?, ?>> getMapList(String path) {
        warnIfMissing("config.yml", path);
        return get("config.yml").get().getMapList(path);
    }
    public List<Map<?, ?>> getMapList(String path, Boolean ignoreWarn) {
        if (!ignoreWarn) warnIfMissing("config.yml", path);
        return get("config.yml").get().getMapList(path);
    }

    /**
     * config.yml の小数設定を取得する（例: mention.sound.volume）。存在しない場合は def を返す。
     */
    public double getDouble(String path, double def) {
        warnIfMissing("config.yml", path);
        return get("config.yml").get().getDouble(path, def);
    }
    public double getDouble(String path, double def, Boolean ignoreWarn) {
        if (!ignoreWarn) warnIfMissing("config.yml", path);
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
        warnIfMissing("messages.yml", path);
        return FormatUtil.text(placeholderPlayer, get("messages.yml").get().getString(path, ""));
    }
    public String getMessage(String path, OfflinePlayer placeholderPlayer, Boolean ignoreWarn) {
        if (!ignoreWarn) warnIfMissing("config.yml", path);
        return FormatUtil.text(placeholderPlayer, get("messages.yml").get().getString(path, ""));
    }

    /**
     * usageメッセージを取得する。生文字列のまま {@link UsageFormatUtil} で &lt;&gt;/| を整形してから、
     * プレースホルダー置換・カラーコード変換を行う。
     */
    public String getUsageMessage(String path, OfflinePlayer placeholderPlayer) {
        return FormatUtil.text(placeholderPlayer, UsageFormatUtil.format(getRawMessage(path)));
    }

    /**
     * messages.yml の文字列を未加工のまま取得する（%player%/PAPI/色変換をしない）。
     * BroadcastCommand が %message% の位置にColorEventを持つComponentを差し込むために使う。
     * 単に送信するだけなら {@link #getMessage(String, OfflinePlayer)} を使うこと。
     */
    public String getRawMessage(String path) {
        warnIfMissing("messages.yml", path);
        return get("messages.yml").get().getString(path, "");
    }
    public String getRawMessage(String path,Boolean ignoreWarn) {
        if (!ignoreWarn) warnIfMissing("config.yml", path);
        return get("messages.yml").get().getString(path, "");
    }

    /**
     * messages.yml の文字列リストを未加工のまま取得する（例: tpa.tpa_accept_tooltip）。
     * プレースホルダー解決・色変換は呼び出し側で {@code PlaceholderManager#resolveLines} を使うこと。
     */
    public List<String> getMessageList(String path) {
        warnIfMissing("messages.yml", path);
        return get("messages.yml").get().getStringList(path);
    }
    public List<String> getMessageList(String path, Boolean ignoreWarn) {
        if (!ignoreWarn) warnIfMissing("config.yml", path);
        return get("messages.yml").get().getStringList(path);
    }
}
