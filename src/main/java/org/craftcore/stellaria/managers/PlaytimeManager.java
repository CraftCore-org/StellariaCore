package org.craftcore.stellaria.managers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 最終ログアウト日時・累計プレイ時間を player_stats テーブルに記録する。
 * セッション開始時刻はメモリ保持し、ログアウト時に累計へ加算して永続化する
 * （サーバーが異常終了した場合、そのセッション分は失われる）。
 * 「最終ログイン」ではなく「最終ログアウト」で記録する点に注意——ログイン時刻だけ覚えていても、
 * オフラインのプレイヤーが「いつ最後にプレイしていたか」を知りたい/scoreの用途には使えない
 * （ログイン中はずっと同じ時刻のままになってしまうため）。
 */
public class PlaytimeManager {

    private final StellariaCore plugin;
    private final Map<UUID, Long> sessionStart = new HashMap<>();
    private static final long PLAYTIME_CACHE_TTL_MILLIS = 5000;
    private final Map<UUID, long[]> storedPlaytimeCache = new ConcurrentHashMap<>(); // [seconds, cachedAtMillis]

    public PlaytimeManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** ログイン時に呼ぶ。セッション開始時刻を記録するだけ（last_logoutは触らない）。
     * まだレコードが無い初回ログインのプレイヤー分だけ、0件の行を用意しておく。 */
    public void onJoin(Player player) {
        UUID uuid = player.getUniqueId();
        sessionStart.put(uuid, System.currentTimeMillis());

        DatabaseManager.executeAsync(
            "INSERT OR IGNORE INTO player_stats (uuid, last_logout, playtime_seconds) VALUES (?, 0, 0)",
            uuid.toString()
        );
    }

    /** ログアウト時に呼ぶ。セッション経過時間を累計プレイ時間に加算し、last_logoutを今の時刻にして永続化する。 */
    public void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        Long start = sessionStart.remove(uuid);
        if (start == null) {
            return;
        }
        long elapsedSeconds = Math.max(0, (System.currentTimeMillis() - start) / 1000L);
        long newPlaytime = getStoredPlaytimeSeconds(uuid) + elapsedSeconds;
        DatabaseManager.executeAsync(
            "UPDATE player_stats SET playtime_seconds = ?, last_logout = ? WHERE uuid = ?",
            newPlaytime, System.currentTimeMillis(), uuid.toString()
        );
        storedPlaytimeCache.remove(uuid);
    }

    /**
     * シャットダウン時に呼ぶ。オンライン中の全プレイヤーのセッションを同期的に確定保存する
     * （DB接続が閉じられる前に完了させる必要があるため、onQuitと違い非同期にしない）。
     */
    public void flushAll() {
        for (UUID uuid : List.copyOf(sessionStart.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            Long start = sessionStart.remove(uuid);
            if (start == null) {
                continue;
            }
            long elapsedSeconds = Math.max(0, (System.currentTimeMillis() - start) / 1000L);
            long newPlaytime = getStoredPlaytimeSeconds(uuid) + elapsedSeconds;
            DatabaseManager.update(
                "player_stats",
                Map.of("playtime_seconds", newPlaytime, "last_logout", System.currentTimeMillis()),
                "uuid = ?", uuid.toString()
            );
            storedPlaytimeCache.remove(uuid);
        }
    }

    /** 累計プレイ時間（秒）。オンライン中なら現在のセッション経過分も加算して返す。 */
    public long getPlaytimeSeconds(UUID uuid) {
        long stored = getStoredPlaytimeSeconds(uuid);
        Long start = sessionStart.get(uuid);
        if (start == null) {
            return stored;
        }
        return stored + Math.max(0, (System.currentTimeMillis() - start) / 1000L);
    }

    /** 最終ログアウト日時（epoch millis）。記録が無い/まだ一度もログアウトしていなければ0。 */
    public long getLastLogout(UUID uuid) {
        Long value = DatabaseManager.queryOne(
            "SELECT last_logout FROM player_stats WHERE uuid = ?",
            rs -> rs.getLong("last_logout"),
            uuid.toString()
        );
        return value != null ? value : 0;
    }

    private long getStoredPlaytimeSeconds(UUID uuid) {
        long now = System.currentTimeMillis();
        long[] cached = storedPlaytimeCache.get(uuid);
        if (cached != null && now - cached[1] < PLAYTIME_CACHE_TTL_MILLIS) {
            return cached[0];
        }
        Long value = DatabaseManager.queryOne(
            "SELECT playtime_seconds FROM player_stats WHERE uuid = ?",
            rs -> rs.getLong("playtime_seconds"),
            uuid.toString()
        );
        long seconds = value != null ? value : 0;
        storedPlaytimeCache.put(uuid, new long[]{seconds, now});
        return seconds;
    }

    /** /ranking playtime の1行分。 */
    public record PlaytimeEntry(String name, long seconds) {
    }

    /** プレイ時間降順で limit 件、offset 件スキップして取得する（/ranking playtime のページング用）。
     * player_stats には名前を持たないので players テーブルと uuid で突き合わせる。 */
    public List<PlaytimeEntry> getTopPlaytimes(int limit, int offset) {
        return DatabaseManager.query(
            "SELECT p.name AS name, ps.playtime_seconds AS seconds FROM player_stats ps " +
                "JOIN players p ON p.uuid = ps.uuid ORDER BY ps.playtime_seconds DESC LIMIT ? OFFSET ?",
            rs -> new PlaytimeEntry(rs.getString("name"), rs.getLong("seconds")),
            limit, offset
        );
    }

    /** player_stats テーブルの総レコード数（/ranking playtime のページ数計算用）。 */
    public int getPlayerCount() {
        Integer count = DatabaseManager.queryOne("SELECT COUNT(*) as cnt FROM player_stats", rs -> rs.getInt("cnt"));
        return count != null ? count : 0;
    }
}
