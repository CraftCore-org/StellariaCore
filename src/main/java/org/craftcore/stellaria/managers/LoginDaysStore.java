package org.craftcore.stellaria.managers;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** ログインした日（日本時間）の記録。連続ログインと直近 30 日の進捗に使う。 */
public final class LoginDaysStore {

    private LoginDaysStore() {
    }

    public static void createTable() {
        DatabaseManager.createTableIfNotExists("player_login_days",
            "uuid TEXT NOT NULL", "day TEXT NOT NULL", "PRIMARY KEY (uuid, day)");
    }

    public static void record(UUID uuid, LocalDate day) {
        DatabaseManager.execute("INSERT OR IGNORE INTO player_login_days (uuid, day) VALUES (?, ?)",
            uuid.toString(), day.toString());
    }

    public static Set<LocalDate> since(UUID uuid, LocalDate from) {
        return new HashSet<>(DatabaseManager.query(
            "SELECT day FROM player_login_days WHERE uuid = ? AND day >= ?",
            rs -> LocalDate.parse(rs.getString("day")), uuid.toString(), from.toString()));
    }
}
