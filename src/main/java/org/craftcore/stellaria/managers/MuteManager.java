package org.craftcore.stellaria.managers;

import org.craftcore.stellaria.StellariaCore;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ユーザーごと・レベル別・期限付きのミュート状態を管理する。
 * DBの mutes テーブルに永続化しつつ、判定はメモリキャッシュで行う（チャット送信・コマンド実行の
 * たびに呼ばれる高頻度な判定のため、毎回DBに問い合わせない）。EconomyManagerと違い、
 * onEnable時に全件ロードしてキャッシュに載せてから使う点に注意。
 */
public class MuteManager {

    public enum MuteScope {
        CHAT(1), PM(2), COMMAND(3);

        private final int requiredLevel;

        MuteScope(int requiredLevel) {
            this.requiredLevel = requiredLevel;
        }

        public int requiredLevel() {
            return requiredLevel;
        }
    }

    public record MuteRecord(int level, long expiresAt, String reason, String mutedBy, long mutedAt) {
        public boolean isPermanent() {
            return expiresAt < 0;
        }
    }

    private final StellariaCore plugin;
    private final Map<UUID, MuteRecord> cache = new ConcurrentHashMap<>();

    public MuteManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** onEnable から1回呼ぶ。DBの mutes テーブルを全件読み込んでキャッシュに載せる。 */
    public void loadAll() {
        cache.clear();
        List<Map.Entry<UUID, MuteRecord>> rows = DatabaseManager.query(
            "SELECT uuid, level, expires_at, reason, muted_by, muted_at FROM mutes",
            rs -> Map.entry(
                UUID.fromString(rs.getString("uuid")),
                new MuteRecord(
                    rs.getInt("level"),
                    rs.getLong("expires_at"),
                    rs.getString("reason"),
                    rs.getString("muted_by"),
                    rs.getLong("muted_at")
                )
            )
        );
        for (Map.Entry<UUID, MuteRecord> entry : rows) {
            cache.put(entry.getKey(), entry.getValue());
        }
        plugin.getLogger().info("ミュート情報を読み込みました（" + cache.size() + "件）");
    }

    /**
     * 現在有効なミュートレコードを返す。期限切れなら自動解除（キャッシュ除去＋DB削除）してnullを返す。
     * ミュートされていなければnull。
     */
    public MuteRecord getRecord(UUID uuid) {
        MuteRecord record = cache.get(uuid);
        if (record == null) {
            return null;
        }
        if (!record.isPermanent() && System.currentTimeMillis() >= record.expiresAt()) {
            unmuteInternal(uuid);
            return null;
        }
        return record;
    }

    /** 指定スコープで制限されている場合はそのレコードを返す。制限されていなければnull。 */
    public MuteRecord getRestrictingRecord(UUID uuid, MuteScope scope) {
        MuteRecord record = getRecord(uuid);
        if (record != null && record.level() >= scope.requiredLevel()) {
            return record;
        }
        return null;
    }

    public boolean isRestricted(UUID uuid, MuteScope scope) {
        return getRestrictingRecord(uuid, scope) != null;
    }

    /** ミュートを設定する（既存のミュートは上書き）。DB書き込みは同期（順序保証のため）。 */
    public void mute(UUID uuid, int level, long expiresAt, String reason, String mutedBy) {
        long mutedAt = System.currentTimeMillis();
        cache.put(uuid, new MuteRecord(level, expiresAt, reason, mutedBy, mutedAt));
        DatabaseManager.execute(
            "INSERT OR REPLACE INTO mutes (uuid, level, expires_at, reason, muted_by, muted_at) VALUES (?, ?, ?, ?, ?, ?)",
            uuid.toString(), level, expiresAt, reason, mutedBy, mutedAt
        );
    }

    public void unmute(UUID uuid) {
        unmuteInternal(uuid);
    }

    private void unmuteInternal(UUID uuid) {
        cache.remove(uuid);
        DatabaseManager.execute("DELETE FROM mutes WHERE uuid = ?", uuid.toString());
    }
}
