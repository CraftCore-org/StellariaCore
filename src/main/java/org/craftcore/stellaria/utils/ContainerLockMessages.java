package org.craftcore.stellaria.utils;

import org.bukkit.OfflinePlayer;
import org.craftcore.stellaria.managers.ConfigManager;

import java.util.Map;

/** 既存のmessages.ymlに追加キーがない場合も、コンテナロックの必須文言を提供する。 */
public final class ContainerLockMessages {
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("lock.protected_chest", "&%cこのチェストは保護されています。"),
            Map.entry("lock.protected_shulker_box", "&%cこのシュルカーボックスは保護されています。"),
            Map.entry("lock.protected_barrel", "&%cこの樽は保護されています。"),
            Map.entry("lock.already_locked_self", "&%cこのコンテナはすでに保護済みです。"),
            Map.entry("lock.auto_enabled", "&%a設置したコンテナを自動で保護します。"),
            Map.entry("lock.auto_disabled", "&%cコンテナの自動保護を解除しました。"),
            Map.entry("lock.unlock_usage", "&%c使用方法: /unlock"),
            Map.entry("lock.database_error", "&%c保護設定を保存できませんでした。しばらくしてからもう一度お試しください。"),
            Map.entry("lock.auto_failed", "&%c保護設定を保存できなかったため、設置を取り消しました。")
    );

    private ContainerLockMessages() {
    }

    public static String defaultRawMessage(String key) {
        return DEFAULTS.getOrDefault(key, "");
    }

    public static String rawMessage(ConfigManager configManager, String key) {
        String configured = configManager.getRawMessage(key);
        return configured.isEmpty() ? defaultRawMessage(key) : configured;
    }

    public static String message(ConfigManager configManager, String key, OfflinePlayer player) {
        return FormatUtil.text(player, rawMessage(configManager, key));
    }
}
