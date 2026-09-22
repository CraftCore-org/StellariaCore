package org.craftcore.stellaria.rail;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.DatabaseManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * rail_stationsテーブル（name主キー）へのCRUDと、毎tickのホットパスから叩かれる駅検索を担当する。
 * MuteManagerと同様、判定頻度が高い（VehicleMoveEventのたび）ためDBに毎回問い合わせず、
 * 起動時に全件をメモリキャッシュしてから使う。
 */
public class RailStationManager {

    /** DB列 direction は既存スキーマ互換のため残しているが、駅自体はもう「向き」を持たない
     *  （/rail depart 側でレールをたどって発車方向を自動判定するため）。常にこの値を書き込み、読み込み時は無視する。 */
    private static final String UNUSED_DIRECTION_PLACEHOLDER = "NORTH";

    public record Station(String name, String world, double x, double y, double z) {
        /** 現在ロードされているワールドに解決したLocationを返す。ワールドが存在しない（未ロード/削除済み）場合はnull。 */
        public Location resolveLocation() {
            World resolvedWorld = Bukkit.getWorld(world);
            if (resolvedWorld == null) {
                return null;
            }
            return new Location(resolvedWorld, x, y, z);
        }
    }

    public enum CreateResult { SUCCESS, NAME_TAKEN, DATABASE_ERROR }

    private final StellariaCore plugin;
    private final Map<String, Station> stations = new ConcurrentHashMap<>();

    public RailStationManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** onEnableで1回呼ぶ。rail_stationsの全行をメモリに読み込む。 */
    public void loadAll() {
        stations.clear();
        List<Station> loaded = DatabaseManager.query(
                "SELECT name, world, x, y, z, direction FROM rail_stations",
                RailStationManager::mapStation
        );
        for (Station station : loaded) {
            if (station != null) {
                stations.put(station.name().toLowerCase(), station);
            }
        }
    }

    private static Station mapStation(ResultSet rs) throws SQLException {
        return new Station(
                rs.getString("name"), rs.getString("world"),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z")
        );
    }

    /** /rail station add から呼ぶ。名前はサーバー全体でユニーク（大文字小文字区別なし）。 */
    public CreateResult create(String name, Location location) {
        String key = name.toLowerCase();
        if (stations.containsKey(key)) {
            return CreateResult.NAME_TAKEN;
        }
        World world = location.getWorld();
        if (world == null) {
            return CreateResult.DATABASE_ERROR;
        }
        int affected = DatabaseManager.insert("rail_stations", Map.of(
                "name", name, "world", world.getName(),
                "x", location.getX(), "y", location.getY(), "z", location.getZ(),
                "direction", UNUSED_DIRECTION_PLACEHOLDER, "created_at", System.currentTimeMillis()
        ));
        if (affected != 1) {
            return CreateResult.DATABASE_ERROR;
        }
        stations.put(key, new Station(name, world.getName(), location.getX(), location.getY(), location.getZ()));
        return CreateResult.SUCCESS;
    }

    /** /rail station remove から呼ぶ。存在しなければfalse。 */
    public boolean remove(String name) {
        Station station = stations.get(name.toLowerCase());
        if (station == null) {
            return false;
        }
        int affected = DatabaseManager.execute("DELETE FROM rail_stations WHERE name = ?", station.name());
        if (affected != 1) {
            return false;
        }
        stations.remove(name.toLowerCase());
        return true;
    }

    /** 無ければnull。 */
    public Station get(String name) {
        return stations.get(name.toLowerCase());
    }

    public List<Station> listAll() {
        return stations.values().stream()
                .sorted(Comparator.comparing(Station::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** ワールドリセット時に呼ぶ。指定ワールドに属する駅をDBとキャッシュの両方から削除する（homes/warpsと同様の後始末）。 */
    public void removeAllInWorld(String worldName) {
        List<String> namesToRemove = stations.values().stream()
                .filter(station -> station.world().equalsIgnoreCase(worldName))
                .map(Station::name)
                .toList();
        for (String name : namesToRemove) {
            remove(name);
        }
    }
}
