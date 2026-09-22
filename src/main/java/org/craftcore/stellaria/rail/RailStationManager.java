package org.craftcore.stellaria.rail;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.DatabaseManager;
import org.craftcore.stellaria.utils.FormatUtil;

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

    /** 駅名の表示色。駅名ごとに個別のカラーコードを持たせるのはやめて、常にこの色で統一表示する。 */
    private static final String DISPLAY_COLOR = "&%e";

    public record Station(String name, String world, double x, double y, double z) {
        /** 現在ロードされているワールドに解決したLocationを返す。ワールドが存在しない（未ロード/削除済み）場合はnull。 */
        public Location resolveLocation() {
            World resolvedWorld = Bukkit.getWorld(world);
            if (resolvedWorld == null) {
                return null;
            }
            return new Location(resolvedWorld, x, y, z);
        }

        /** チャット等、既に色変換済みの文字列へ%name%展開する用途向け（DISPLAY_COLOR適用済み）。 */
        public String displayName() {
            return formatDisplayName(name);
        }

        /** GUIアイテム名・タイトル等、Componentが欲しい用途向け（DISPLAY_COLOR適用済み）。 */
        public Component displayNameComponent() {
            return formatDisplayNameComponent(name);
        }
    }

    /** endSession等、Stationインスタンスを持たずタイトルだけ持っている呼び出し元向け。 */
    public static String formatDisplayName(String stationName) {
        return FormatUtil.color(DISPLAY_COLOR + stationName);
    }

    public static Component formatDisplayNameComponent(String stationName) {
        return FormatUtil.component(DISPLAY_COLOR + stationName);
    }

    public enum CreateResult { SUCCESS, NAME_TAKEN, DATABASE_ERROR }

    /** /rail station remove・RailStationAdminGuiの削除結果。BELONGS_TO_LINEは幽霊駅防止のための拒否。 */
    public enum RemoveResult { SUCCESS, NOT_FOUND, BELONGS_TO_LINE, DATABASE_ERROR }

    private final StellariaCore plugin;
    private final Map<String, Station> stations = new ConcurrentHashMap<>();
    /** remove()で所属路線をチェックするために使う。RailLineManagerがRailStationManagerに依存するため
     *  コンストラクタでは受け取れず、onEnableでRailLineManager構築後にbindLineManagerで後から渡す。 */
    private RailLineManager lineManager;

    public RailStationManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** onEnableで、RailLineManager構築直後に1回呼ぶ。 */
    public void bindLineManager(RailLineManager lineManager) {
        this.lineManager = lineManager;
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

    /**
     * /rail station remove・RailStationAdminGuiから呼ぶ。路線に属している駅はBELONGS_TO_LINEを返して
     * 拒否する（rail_line_stations/RailLineManager.stationToLineが更新されず幽霊駅が残るのを防ぐため）。
     * 削除するには先に /rail line remove でその路線ごと解除する必要がある。
     * ワールドリセットによる強制削除は removeAllInWorld（こちらは路線からも自動で外す）を使うこと。
     */
    public RemoveResult remove(String name) {
        Station station = stations.get(name.toLowerCase());
        if (station == null) {
            return RemoveResult.NOT_FOUND;
        }
        if (lineManager != null && lineManager.findLineForStation(station.name()) != null) {
            return RemoveResult.BELONGS_TO_LINE;
        }
        return removeInternal(station) ? RemoveResult.SUCCESS : RemoveResult.DATABASE_ERROR;
    }

    private boolean removeInternal(Station station) {
        int affected = DatabaseManager.execute("DELETE FROM rail_stations WHERE name = ?", station.name());
        if (affected != 1) {
            return false;
        }
        stations.remove(station.name().toLowerCase());
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

    /**
     * ワールドリセット時に呼ぶ。指定ワールドに属する駅をDBとキャッシュの両方から削除する（homes/warpsと同様の後始末）。
     * 通常のremove()と違い路線所属を理由に拒否しない（ワールド自体が消えるため確認しようがない）。
     * 代わりに所属路線があればRailLineManager#detachStationで先に安全に外してから削除する。
     */
    public void removeAllInWorld(String worldName) {
        List<Station> toRemove = stations.values().stream()
                .filter(station -> station.world().equalsIgnoreCase(worldName))
                .toList();
        for (Station station : toRemove) {
            if (lineManager != null) {
                lineManager.detachStation(station.name());
            }
            removeInternal(station);
        }
    }
}
