package org.craftcore.stellaria.managers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 独自進捗の DB 操作。DB が達成状況の正であり、バニラ進捗は表示係として扱う。
 * player_advancements の completed_at = 0 は「revoke で未達成に戻した」行を表し、reward_paid はそのまま残す
 * （再び達成しても報酬を二重に払わないため）。
 */
public final class AdvancementStore {

    public record Loaded(Map<String, Long> counters, Map<String, Long> distinctCounts, Set<String> completed) {
    }

    private AdvancementStore() {
    }

    public static void createTables() {
        DatabaseManager.createTableIfNotExists("player_counters",
            "uuid TEXT NOT NULL", "counter_key TEXT NOT NULL", "value INTEGER NOT NULL",
            "PRIMARY KEY (uuid, counter_key)");
        DatabaseManager.createTableIfNotExists("player_counter_members",
            "uuid TEXT NOT NULL", "counter_key TEXT NOT NULL", "member TEXT NOT NULL",
            "PRIMARY KEY (uuid, counter_key, member)");
        DatabaseManager.createTableIfNotExists("player_advancements",
            "uuid TEXT NOT NULL", "advancement_id TEXT NOT NULL", "completed_at INTEGER NOT NULL",
            "reward_paid INTEGER NOT NULL DEFAULT 0", "reward_amount INTEGER NOT NULL DEFAULT 0",
            "PRIMARY KEY (uuid, advancement_id)");
    }

    public static Loaded load(UUID uuid) {
        String id = uuid.toString();
        Map<String, Long> counters = new HashMap<>();
        for (Map.Entry<String, Long> e : DatabaseManager.query(
                "SELECT counter_key, value FROM player_counters WHERE uuid = ?",
                rs -> Map.entry(rs.getString("counter_key"), rs.getLong("value")), id)) {
            counters.put(e.getKey(), e.getValue());
        }
        Map<String, Long> distinct = new HashMap<>();
        for (Map.Entry<String, Long> e : DatabaseManager.query(
                "SELECT counter_key, COUNT(*) AS cnt FROM player_counter_members WHERE uuid = ? GROUP BY counter_key",
                rs -> Map.entry(rs.getString("counter_key"), rs.getLong("cnt")), id)) {
            distinct.put(e.getKey(), e.getValue());
        }
        Set<String> completed = new HashSet<>(completedAt(uuid).keySet());
        return new Loaded(counters, distinct, completed);
    }

    public static boolean addCounter(UUID uuid, String key, long amount) {
        return DatabaseManager.execute(
            "INSERT INTO player_counters (uuid, counter_key, value) VALUES (?, ?, ?) "
                + "ON CONFLICT (uuid, counter_key) DO UPDATE SET value = value + excluded.value",
            uuid.toString(), key, amount) > 0;
    }

    /** 同期で加算して、加算後の値を返す（日ごとの売上のように、その場でしきい値を判定したい値用）。 */
    public static long addCounterAndGet(UUID uuid, String key, long amount) {
        addCounter(uuid, key, amount);
        Long value = DatabaseManager.queryOne(
            "SELECT value FROM player_counters WHERE uuid = ? AND counter_key = ?",
            rs -> rs.getLong("value"), uuid.toString(), key);
        return value != null ? value : 0L;
    }

    public static void addCounterAsync(UUID uuid, String key, long amount) {
        DatabaseManager.executeAsync(
            "INSERT INTO player_counters (uuid, counter_key, value) VALUES (?, ?, ?) "
                + "ON CONFLICT (uuid, counter_key) DO UPDATE SET value = value + excluded.value",
            uuid.toString(), key, amount);
    }

    public static boolean addMember(UUID uuid, String key, String member) {
        return DatabaseManager.execute(
            "INSERT OR IGNORE INTO player_counter_members (uuid, counter_key, member) VALUES (?, ?, ?)",
            uuid.toString(), key, member) == 1;
    }

    public static boolean recordCompletion(UUID uuid, String id, long now) {
        return DatabaseManager.execute(
            "INSERT INTO player_advancements (uuid, advancement_id, completed_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (uuid, advancement_id) DO UPDATE SET completed_at = excluded.completed_at "
                + "WHERE player_advancements.completed_at = 0",
            uuid.toString(), id, now) == 1;
    }

    public static boolean claimReward(UUID uuid, String id, long amount) {
        return DatabaseManager.execute(
            "UPDATE player_advancements SET reward_paid = 1, reward_amount = ? "
                + "WHERE uuid = ? AND advancement_id = ? AND reward_paid = 0 AND completed_at > 0",
            amount, uuid.toString(), id) == 1;
    }

    /** 入金に失敗したとき、支払い済みの印を取り消す（次の機会に払い直せるように）。 */
    public static void unclaimReward(UUID uuid, String id) {
        DatabaseManager.execute(
            "UPDATE player_advancements SET reward_paid = 0, reward_amount = 0 "
                + "WHERE uuid = ? AND advancement_id = ? AND reward_paid = 1",
            uuid.toString(), id);
    }

    public static boolean revoke(UUID uuid, String id) {
        return DatabaseManager.execute(
            "UPDATE player_advancements SET completed_at = 0 WHERE uuid = ? AND advancement_id = ? AND completed_at > 0",
            uuid.toString(), id) == 1;
    }

    /** 達成済みの進捗と達成日時（epoch millis）。 */
    public static Map<String, Long> completedAt(UUID uuid) {
        Map<String, Long> result = new HashMap<>();
        List<Map.Entry<String, Long>> rows = DatabaseManager.query(
            "SELECT advancement_id, completed_at FROM player_advancements WHERE uuid = ? AND completed_at > 0",
            rs -> Map.entry(rs.getString("advancement_id"), rs.getLong("completed_at")), uuid.toString());
        for (Map.Entry<String, Long> row : rows) {
            result.put(row.getKey(), row.getValue());
        }
        return result;
    }

    /** /ranking advancements の 1 行分。 */
    public record RankEntry(String name, long value) {
    }

    /** 達成数の多い順。統計ランキングを非公開にしている人と、1 個も達成していない人は除く。 */
    public static List<RankEntry> topCompleted(int limit, int offset) {
        return DatabaseManager.query(
            "SELECT p.name AS name, COUNT(*) AS cnt FROM player_advancements a JOIN players p ON p.uuid = a.uuid "
                + "WHERE a.completed_at > 0 AND p.hide_stats_ranking = 0 "
                + "GROUP BY a.uuid, p.name ORDER BY cnt DESC, p.name ASC LIMIT ? OFFSET ?",
            rs -> new RankEntry(rs.getString("name"), rs.getLong("cnt")), limit, offset);
    }

    public static int completedPublicCount() {
        Integer count = DatabaseManager.queryOne(
            "SELECT COUNT(DISTINCT a.uuid) AS cnt FROM player_advancements a JOIN players p ON p.uuid = a.uuid "
                + "WHERE a.completed_at > 0 AND p.hide_stats_ranking = 0",
            rs -> rs.getInt("cnt"));
        return count != null ? count : 0;
    }

    /** 達成数が value より多い公開プレイヤーの人数（自分の順位用）。 */
    public static int countCompletedPublicAbove(long value) {
        Integer count = DatabaseManager.queryOne(
            "SELECT COUNT(*) AS cnt FROM (SELECT a.uuid FROM player_advancements a JOIN players p ON p.uuid = a.uuid "
                + "WHERE a.completed_at > 0 AND p.hide_stats_ranking = 0 GROUP BY a.uuid HAVING COUNT(*) > ?)",
            rs -> rs.getInt("cnt"), value);
        return count != null ? count : 0;
    }

    public static long completedCount(UUID uuid) {
        Long count = DatabaseManager.queryOne(
            "SELECT COUNT(*) AS cnt FROM player_advancements WHERE uuid = ? AND completed_at > 0",
            rs -> rs.getLong("cnt"), uuid.toString());
        return count != null ? count : 0L;
    }

    public static long rewardTotal(UUID uuid) {
        Long total = DatabaseManager.queryOne(
            "SELECT COALESCE(SUM(reward_amount), 0) AS total FROM player_advancements WHERE uuid = ? AND reward_paid = 1",
            rs -> rs.getLong("total"), uuid.toString());
        return total != null ? total : 0L;
    }
}
