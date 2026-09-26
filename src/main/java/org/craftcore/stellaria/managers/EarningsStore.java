package org.craftcore.stellaria.managers;

import java.util.List;
import java.util.UUID;

/**
 * 累計で稼いだお金（/ranking earned）。値は独自進捗と同じ player_counters の economy.earned に貯める。
 * 所持金を非公開にしているプレイヤー（players.hide_balance）はランキングから除く。
 * 記録は EconomyManager#recordEarning から行い、返金や資金の出し入れのような「自分のお金が戻っただけ」の入金は数えない。
 */
public final class EarningsStore {

    public static final String KEY = "economy.earned";

    public record Entry(String name, long value) {
    }

    private EarningsStore() {
    }

    public static List<Entry> top(int limit, int offset) {
        return DatabaseManager.query(
            "SELECT p.name AS name, c.value AS value FROM player_counters c JOIN players p ON p.uuid = c.uuid "
                + "WHERE c.counter_key = ? AND p.hide_balance = 0 AND c.value > 0 "
                + "ORDER BY c.value DESC, p.name ASC LIMIT ? OFFSET ?",
            rs -> new Entry(rs.getString("name"), rs.getLong("value")), KEY, limit, offset);
    }

    public static int publicCount() {
        Integer count = DatabaseManager.queryOne(
            "SELECT COUNT(*) AS cnt FROM player_counters c JOIN players p ON p.uuid = c.uuid "
                + "WHERE c.counter_key = ? AND p.hide_balance = 0 AND c.value > 0",
            rs -> rs.getInt("cnt"), KEY);
        return count != null ? count : 0;
    }

    public static int countPublicAbove(long value) {
        Integer count = DatabaseManager.queryOne(
            "SELECT COUNT(*) AS cnt FROM player_counters c JOIN players p ON p.uuid = c.uuid "
                + "WHERE c.counter_key = ? AND p.hide_balance = 0 AND c.value > ?",
            rs -> rs.getInt("cnt"), KEY, value);
        return count != null ? count : 0;
    }

    public static long total(UUID uuid) {
        Long value = DatabaseManager.queryOne(
            "SELECT value FROM player_counters WHERE uuid = ? AND counter_key = ?",
            rs -> rs.getLong("value"), uuid.toString(), KEY);
        return value != null ? value : 0L;
    }
}
