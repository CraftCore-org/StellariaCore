package org.craftcore.stellaria.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * warpsテーブル（name主キー, owner_uuid, world, x, y, z, yaw, pitch）へのCRUDと、
 * 上限数・名前重複（サーバー全体でユニーク）・経済コストのチェックをまとめる。
 */
public class WarpManager {

    public enum SetResult { SUCCESS, LIMIT_REACHED, NAME_TAKEN, INSUFFICIENT_FUNDS }

    /** /warps 表示用の1行分（所有者名はplayersテーブルとのLEFT JOINで取得、居なければnull）。 */
    public record WarpEntry(String name, String ownerName) {
    }

    private final StellariaCore plugin;

    public WarpManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    public boolean exists(String name) {
        return DatabaseManager.exists("warps", "name = ?", name);
    }

    public int count(UUID owner) {
        Integer count = DatabaseManager.queryOne(
                "SELECT COUNT(*) as cnt FROM warps WHERE owner_uuid = ?",
                rs -> rs.getInt("cnt"),
                owner.toString()
        );
        return count != null ? count : 0;
    }

    /**
     * warp作成。上限・名前重複（サーバー全体）・経済コストの順にチェックし、最初に失敗した理由を返す。
     * 全て通った場合のみDBへINSERTし、コストが0より大きければEconomyManager経由で消費する。
     */
    public SetResult set(Player player, String name, Location location) {
        UUID owner = player.getUniqueId();
        int max = plugin.getConfigManager().getInt("warp.max-per-player", 5);
        if (count(owner) >= max) {
            return SetResult.LIMIT_REACHED;
        }
        if (exists(name)) {
            return SetResult.NAME_TAKEN;
        }
        double cost = plugin.getConfigManager().getDouble("warp.cost", 0);
        if (cost > 0) {
            net.milkbowl.vault.economy.EconomyResponse response = plugin.getEconomyManager().withdrawPlayer(player, cost);
            if (!response.transactionSuccess()) {
                return SetResult.INSUFFICIENT_FUNDS;
            }
        }
        DatabaseManager.insert("warps", Map.of(
                "name", name,
                "owner_uuid", owner.toString(),
                "world", location.getWorld().getName(),
                "x", location.getX(),
                "y", location.getY(),
                "z", location.getZ(),
                "yaw", location.getYaw(),
                "pitch", location.getPitch()
        ));
        return SetResult.SUCCESS;
    }

    /** 所有者UUID。存在しなければnull（delete権限チェック用）。 */
    public UUID getOwner(String name) {
        return DatabaseManager.queryOne(
                "SELECT owner_uuid FROM warps WHERE name = ?",
                rs -> UUID.fromString(rs.getString("owner_uuid")),
                name
        );
    }

    /** 削除に成功したらtrue、そもそも存在しなかったらfalse。権限チェックは呼び出し側(WarpCommand)の責務。 */
    public boolean delete(String name) {
        if (!exists(name)) {
            return false;
        }
        DatabaseManager.execute("DELETE FROM warps WHERE name = ?", name);
        return true;
    }

    /** 無ければnull（ワールドが存在しない場合も含む）。 */
    public Location get(String name) {
        return DatabaseManager.queryOne(
                "SELECT world, x, y, z, yaw, pitch FROM warps WHERE name = ?",
                WarpManager::mapLocation,
                name
        );
    }

    public List<WarpEntry> listAll() {
        return DatabaseManager.query(
                "SELECT warps.name as wname, players.name as owner_name FROM warps " +
                        "LEFT JOIN players ON players.uuid = warps.owner_uuid ORDER BY warps.name",
                rs -> new WarpEntry(rs.getString("wname"), rs.getString("owner_name"))
        );
    }

    private static Location mapLocation(ResultSet rs) throws SQLException {
        World world = Bukkit.getWorld(rs.getString("world"));
        if (world == null) {
            return null;
        }
        return new Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getFloat("yaw"), rs.getFloat("pitch"));
    }
}
