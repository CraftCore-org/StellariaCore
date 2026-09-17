package org.craftcore.stellaria.managers;

import net.dv8tion.jda.api.EmbedBuilder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.craftcore.stellaria.StellariaCore;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * 警告・キック・BANの履歴を永続化し、現在有効なBANだけをメモリに保持する。
 * BANの履歴は期限切れ後も削除せず、キャッシュからのみ取り除く。
 */
public class ModerationManager {

    public record HistoryEntry(String type, UUID actorUuid, String reason, long occurredAt) {
    }

    public record Counts(int warns, int kicks, int bans, int reports) {
    }

    private final StellariaCore plugin;
    private final ActiveBanRegistry activeBans = new ActiveBanRegistry();

    public ModerationManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** 起動時にBAN履歴を読み込み、各対象者の最新BANだけを有効キャッシュの候補にする。 */
    public void loadAllBans() {
        activeBans.clear();
        Set<UUID> newestBanTargets = new HashSet<>();
        long now = System.currentTimeMillis();
        for (ActiveBanRegistry.BanEntry entry : DatabaseManager.query(
            "SELECT id, target_uuid, moderator_uuid, reason, banned_at, expires_at FROM bans "
                + "ORDER BY target_uuid ASC, banned_at DESC, id DESC",
            rs -> new ActiveBanRegistry.BanEntry(
                rs.getInt("id"),
                UUID.fromString(rs.getString("target_uuid")),
                nullableUuid(rs.getString("moderator_uuid")),
                rs.getString("reason"),
                rs.getLong("banned_at"),
                rs.getObject("expires_at") == null ? null : rs.getLong("expires_at")
            )
        )) {
            if (newestBanTargets.add(entry.targetUuid()) && !entry.isExpired(now)) {
                activeBans.put(entry);
            }
        }
        plugin.getLogger().info("BAN情報を読み込みました（" + newestBanTargets.size() + "件）");
    }

    public void warn(UUID targetUuid, UUID moderatorUuid, String reason) {
        int changed = DatabaseManager.execute(
            "INSERT INTO warns (target_uuid, moderator_uuid, reason, created_at) VALUES (?, ?, ?, ?)",
            targetUuid.toString(), nullableUuidString(moderatorUuid), reason, System.currentTimeMillis()
        );
        if (changed > 0) {
            sendModerationLog("WARN", targetUuid, moderatorUuid, reason);
        }
    }

    public void recordKick(UUID targetUuid, UUID moderatorUuid, String reason) {
        int changed = DatabaseManager.execute(
            "INSERT INTO kicks (target_uuid, moderator_uuid, reason, created_at) VALUES (?, ?, ?, ?)",
            targetUuid.toString(), nullableUuidString(moderatorUuid), reason, System.currentTimeMillis()
        );
        if (changed > 0) {
            sendModerationLog("KICK", targetUuid, moderatorUuid, reason);
        }
    }

    /** BANを履歴に追加し、期限内のBANだけを有効キャッシュへ反映する。 */
    public void ban(UUID targetUuid, UUID moderatorUuid, String reason, Long expiresAt) {
        long bannedAt = System.currentTimeMillis();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("target_uuid", targetUuid.toString());
        values.put("moderator_uuid", nullableUuidString(moderatorUuid));
        values.put("reason", reason);
        values.put("banned_at", bannedAt);
        values.put("expires_at", expiresAt);

        OptionalInt id = DatabaseManager.insertAndGetId("bans", values);
        if (id.isPresent()) {
            ActiveBanRegistry.BanEntry entry = new ActiveBanRegistry.BanEntry(
                id.getAsInt(), targetUuid, moderatorUuid, reason, bannedAt, expiresAt
            );
            // 最新のBAN行は、期限切れであっても過去の有効BANを上書きする。
            activeBans.remove(targetUuid);
            if (!entry.isExpired(bannedAt)) {
                activeBans.put(entry);
            }
            sendModerationLog("BAN", targetUuid, moderatorUuid, reason);
        }
    }

    /** 現在有効なBANを返す。期限切れ時はキャッシュから外すだけで、履歴行は保持する。 */
    public ActiveBanRegistry.BanEntry getActiveBan(UUID targetUuid) {
        return activeBans.getActive(targetUuid, System.currentTimeMillis());
    }

    /** 対象プレイヤーの直近のモデレーション・通報履歴を新しい順に取得する。 */
    public List<HistoryEntry> getHistory(UUID targetUuid, int limit) {
        return DatabaseManager.query(
            "SELECT type, actor_uuid, reason, occurred_at FROM ("
                + "SELECT 'WARN' AS type, moderator_uuid AS actor_uuid, reason, created_at AS occurred_at "
                + "FROM warns WHERE target_uuid = ? "
                + "UNION ALL "
                + "SELECT 'KICK' AS type, moderator_uuid AS actor_uuid, reason, created_at AS occurred_at "
                + "FROM kicks WHERE target_uuid = ? "
                + "UNION ALL "
                + "SELECT 'BAN' AS type, moderator_uuid AS actor_uuid, reason, banned_at AS occurred_at "
                + "FROM bans WHERE target_uuid = ? "
                + "UNION ALL "
                + "SELECT 'REPORT' AS type, reporter_uuid AS actor_uuid, category || ': ' || reason AS reason, "
                + "created_at AS occurred_at FROM reports WHERE target_uuid = ?"
                + ") ORDER BY occurred_at DESC LIMIT ?",
            rs -> new HistoryEntry(
                rs.getString("type"),
                nullableUuid(rs.getString("actor_uuid")),
                rs.getString("reason"),
                rs.getLong("occurred_at")
            ),
            targetUuid.toString(), targetUuid.toString(), targetUuid.toString(), targetUuid.toString(), limit
        );
    }

    /** 対象プレイヤーの警告・キック・BAN・被通報件数を取得する。 */
    public Counts getCounts(UUID targetUuid) {
        Counts counts = DatabaseManager.queryOne(
            "SELECT "
                + "(SELECT COUNT(*) FROM warns WHERE target_uuid = ?) AS warns, "
                + "(SELECT COUNT(*) FROM kicks WHERE target_uuid = ?) AS kicks, "
                + "(SELECT COUNT(*) FROM bans WHERE target_uuid = ?) AS bans, "
                + "(SELECT COUNT(*) FROM reports WHERE target_uuid = ?) AS reports",
            rs -> new Counts(rs.getInt("warns"), rs.getInt("kicks"), rs.getInt("bans"), rs.getInt("reports")),
            targetUuid.toString(), targetUuid.toString(), targetUuid.toString(), targetUuid.toString()
        );
        return counts != null ? counts : new Counts(0, 0, 0, 0);
    }

    private static UUID nullableUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static String nullableUuidString(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }

    private void sendModerationLog(String type, UUID targetUuid, UUID moderatorUuid, String reason) {
        plugin.getDiscordBotManager().sendModerationLog(
            new EmbedBuilder().setTitle(type)
                .addField("実行者", playerName(moderatorUuid), true)
                .addField("対象", playerName(targetUuid), true)
                .addField("理由", reason, false)
        );
    }

    private static String playerName(UUID uuid) {
        if (uuid == null) return "CONSOLE";
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        return player.getName() != null ? player.getName() : uuid.toString();
    }
}
