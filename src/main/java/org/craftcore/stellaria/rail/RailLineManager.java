package org.craftcore.stellaria.rail;

import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.DatabaseManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * rail_lines/rail_line_stationsテーブルへのCRUDを担当する。路線は駅の並び順（sequence）を持ち、
 * これが一方通行の「正方向」を定義する。RailStationManagerと同様、判定頻度が高い
 * （/rail depart のたび）ためDBに毎回問い合わせず起動時に全件メモリキャッシュする。
 * 1駅は最大1路線にしか属せない（複数路線を跨ぐ駅は許可しない — シンプルさのための制約）。
 */
public class RailLineManager {

    public record RailLine(String name, boolean oneWay, List<String> stationNamesInOrder) {
        /** 駅名の並び順インデックス。属していなければ-1。 */
        public int sequenceOf(String stationName) {
            for (int i = 0; i < stationNamesInOrder.size(); i++) {
                if (stationNamesInOrder.get(i).equalsIgnoreCase(stationName)) {
                    return i;
                }
            }
            return -1;
        }
    }

    public enum CreateResult {
        SUCCESS, NAME_TAKEN, TOO_FEW_STATIONS, STATION_NOT_FOUND, STATION_ALREADY_ON_LINE, DATABASE_ERROR
    }

    private record LineRow(String name, boolean oneWay) {
    }

    private final StellariaCore plugin;
    private final RailStationManager stationManager;
    private final Map<String, RailLine> lines = new ConcurrentHashMap<>();
    /** 駅名(小文字) -> 所属路線名(小文字)。findLineForStationの高速化用。 */
    private final Map<String, String> stationToLine = new ConcurrentHashMap<>();

    public RailLineManager(StellariaCore plugin, RailStationManager stationManager) {
        this.plugin = plugin;
        this.stationManager = stationManager;
    }

    /** onEnableで1回呼ぶ。rail_lines/rail_line_stationsの全行をメモリに読み込む。 */
    public void loadAll() {
        lines.clear();
        stationToLine.clear();
        List<LineRow> rows = DatabaseManager.query(
                "SELECT name, one_way FROM rail_lines",
                rs -> new LineRow(rs.getString("name"), rs.getInt("one_way") != 0)
        );
        for (LineRow row : rows) {
            List<String> stationNames = DatabaseManager.query(
                    "SELECT station_name FROM rail_line_stations WHERE line_name = ? ORDER BY sequence ASC",
                    rs -> rs.getString("station_name"),
                    row.name()
            );
            registerInCache(new RailLine(row.name(), row.oneWay(), stationNames));
        }
    }

    /**
     * /rail line create から呼ぶ。駅は2つ以上、全て実在し、まだどの路線にも属していないこと。
     * stationNamesの並び順がそのまま路線の正方向（一方通行時の順走方向）になる。
     */
    public CreateResult create(String name, boolean oneWay, List<String> stationNames) {
        String key = name.toLowerCase(Locale.ROOT);
        if (lines.containsKey(key)) {
            return CreateResult.NAME_TAKEN;
        }
        if (stationNames.size() < 2) {
            return CreateResult.TOO_FEW_STATIONS;
        }
        List<String> canonicalNames = new ArrayList<>();
        for (String stationName : stationNames) {
            RailStationManager.Station station = stationManager.get(stationName);
            if (station == null) {
                return CreateResult.STATION_NOT_FOUND;
            }
            if (stationToLine.containsKey(station.name().toLowerCase(Locale.ROOT))) {
                return CreateResult.STATION_ALREADY_ON_LINE;
            }
            canonicalNames.add(station.name());
        }

        boolean committed = DatabaseManager.transaction(connection -> {
            if (DatabaseManager.insert("rail_lines", Map.of(
                    "name", name, "one_way", oneWay ? 1 : 0, "created_at", System.currentTimeMillis())) != 1) {
                throw new IllegalStateException("路線の保存に失敗しました");
            }
            for (int i = 0; i < canonicalNames.size(); i++) {
                if (DatabaseManager.insert("rail_line_stations", Map.of(
                        "line_name", name, "station_name", canonicalNames.get(i), "sequence", i)) != 1) {
                    throw new IllegalStateException("路線の駅登録に失敗しました");
                }
            }
        });
        if (!committed) {
            return CreateResult.DATABASE_ERROR;
        }

        registerInCache(new RailLine(name, oneWay, canonicalNames));
        return CreateResult.SUCCESS;
    }

    /** /rail line remove から呼ぶ。存在しなければfalse。 */
    public boolean remove(String name) {
        RailLine line = lines.get(name.toLowerCase(Locale.ROOT));
        if (line == null) {
            return false;
        }
        boolean committed = DatabaseManager.transaction(connection -> {
            DatabaseManager.execute("DELETE FROM rail_line_stations WHERE line_name = ?", line.name());
            DatabaseManager.execute("DELETE FROM rail_lines WHERE name = ?", line.name());
        });
        if (!committed) {
            return false;
        }
        String key = name.toLowerCase(Locale.ROOT);
        lines.remove(key);
        for (String stationName : line.stationNamesInOrder()) {
            stationToLine.remove(stationName.toLowerCase(Locale.ROOT));
        }
        return true;
    }

    /**
     * ワールドリセット等、駅自体が消える時にRailStationManagerから呼ぶ。所属路線があれば安全に外す
     * （残り駅が1つ以下になる場合は幽霊路線を残さないよう路線ごと解散する）。
     * 所属していなければ何もせずfalseを返す。通常の削除コマンド/GUIはこちらを経由せず、
     * 事前にfindLineForStationで所属を検出して削除自体を拒否する（RailStationManager#remove参照）。
     */
    public boolean detachStation(String stationName) {
        RailLine line = findLineForStation(stationName);
        if (line == null) {
            return false;
        }
        List<String> remaining = new ArrayList<>(line.stationNamesInOrder());
        remaining.removeIf(name -> name.equalsIgnoreCase(stationName));
        if (remaining.size() < 2) {
            remove(line.name());
            return true;
        }
        boolean committed = DatabaseManager.transaction(connection -> {
            DatabaseManager.execute("DELETE FROM rail_line_stations WHERE line_name = ? AND station_name = ?",
                    line.name(), stationName);
            for (int i = 0; i < remaining.size(); i++) {
                DatabaseManager.execute(
                        "UPDATE rail_line_stations SET sequence = ? WHERE line_name = ? AND station_name = ?",
                        i, line.name(), remaining.get(i));
            }
        });
        if (!committed) {
            return false;
        }
        registerInCache(new RailLine(line.name(), line.oneWay(), remaining));
        stationToLine.remove(stationName.toLowerCase(Locale.ROOT));
        return true;
    }

    /** 無ければnull。 */
    public RailLine get(String name) {
        return lines.get(name.toLowerCase(Locale.ROOT));
    }

    /** stationNameが属する路線。どの路線にも属していなければnull。 */
    public RailLine findLineForStation(String stationName) {
        String lineKey = stationToLine.get(stationName.toLowerCase(Locale.ROOT));
        return lineKey != null ? lines.get(lineKey) : null;
    }

    public List<RailLine> listAll() {
        return lines.values().stream()
                .sorted(Comparator.comparing(RailLine::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private void registerInCache(RailLine line) {
        String key = line.name().toLowerCase(Locale.ROOT);
        lines.put(key, line);
        for (String stationName : line.stationNamesInOrder()) {
            stationToLine.put(stationName.toLowerCase(Locale.ROOT), key);
        }
    }
}
