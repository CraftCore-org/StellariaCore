package org.craftcore.stellaria.managers;

import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 最終ログイン日時・累計プレイ時間を player_stats テーブルに記録する。
 * セッション開始時刻はメモリ保持し、ログアウト時に累計へ加算して永続化する
 * （サーバーが異常終了した場合、そのセッション分は失われる）。
 */
public class PlaytimeManager {

    private final StellariaCore plugin;
    private final Map<UUID, Long> sessionStart = new HashMap<>();

    public PlaytimeManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** ログイン時に呼ぶ。セッション開始時刻を記録し、last_login を更新する（累計プレイ時間は維持）。 */
    public void onJoin(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        sessionStart.put(uuid, now);

        long currentPlaytime = getStoredPlaytimeSeconds(uuid);
        DatabaseManager.executeAsync(
            "INSERT OR REPLACE INTO player_stats (uuid, last_login, playtime_seconds) VALUES (?, ?, ?)",
            uuid.toString(), now, currentPlaytime
        );
    }

    /** ログアウト時に呼ぶ。セッション経過時間を累計プレイ時間に加算して永続化する。 */
    public void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        Long start = sessionStart.remove(uuid);
        if (start == null) {
            return;
        }
        long elapsedSeconds = Math.max(0, (System.currentTimeMillis() - start) / 1000L);
        long newPlaytime = getStoredPlaytimeSeconds(uuid) + elapsedSeconds;
        DatabaseManager.executeAsync(
            "UPDATE player_stats SET playtime_seconds = ? WHERE uuid = ?",
            newPlaytime, uuid.toString()
        );
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

    /** 最終ログイン日時（epoch millis）。記録が無ければ0。 */
    public long getLastLogin(UUID uuid) {
        Long value = DatabaseManager.queryOne(
            "SELECT last_login FROM player_stats WHERE uuid = ?",
            rs -> rs.getLong("last_login"),
            uuid.toString()
        );
        return value != null ? value : 0;
    }

    private long getStoredPlaytimeSeconds(UUID uuid) {
        Long value = DatabaseManager.queryOne(
            "SELECT playtime_seconds FROM player_stats WHERE uuid = ?",
            rs -> rs.getLong("playtime_seconds"),
            uuid.toString()
        );
        return value != null ? value : 0;
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
