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
import java.util.concurrent.atomic.AtomicReference;

/**
 * homesテーブル（uuid, name, world, x, y, z, yaw, pitch / 主キー(uuid, name)）へのCRUDと、
 * 上限数・名前重複・経済コストのチェックをまとめる。DatabaseManagerを直接呼ぶ点はEconomyManagerと同じ設計。
 */
public class HomeManager {

    public enum SetResult { SUCCESS, LIMIT_REACHED, NAME_TAKEN, INSUFFICIENT_FUNDS, DATABASE_ERROR }

    private final StellariaCore plugin;

    public HomeManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    public boolean exists(UUID owner, String name) {
        return DatabaseManager.exists("homes", "uuid = ? AND name = ?", owner.toString(), name);
    }

    public int count(UUID owner) {
        Integer count = DatabaseManager.queryOne(
                "SELECT COUNT(*) as cnt FROM homes WHERE uuid = ?",
                rs -> rs.getInt("cnt"),
                owner.toString()
        );
        return count != null ? count : 0;
    }

    /**
     * home作成。上限・重複名・経済コストの順にチェックし、最初に失敗した理由を返す。
     * 全て通った場合のみDBへINSERTし、コストが0より大きければEconomyManager経由で消費する。
     */
    public SetResult set(Player player, String name, Location location) {
        UUID owner = player.getUniqueId();
        int max = Math.max(0, plugin.getConfigManager().getInt("home.max-per-player", 5));
        double cost = plugin.getConfigManager().getDouble("home.cost", 0);
        if (cost < 0 || !Double.isFinite(cost) || cost > Long.MAX_VALUE || cost != Math.rint(cost)) {
            plugin.getLogger().severe("home.cost は0以上の整数で指定してください: " + cost);
            return SetResult.DATABASE_ERROR;
        }
        long costLong = (long) cost;
        AtomicReference<SetResult> result = new AtomicReference<>(SetResult.DATABASE_ERROR);
        boolean committed = DatabaseManager.transaction(connection -> {
            if (count(owner) >= max) {
                result.set(SetResult.LIMIT_REACHED);
                return;
            }
            if (exists(owner, name)) {
                result.set(SetResult.NAME_TAKEN);
                return;
            }
            int debitAffected = costLong > 0 ? DatabaseManager.execute(
                    "UPDATE players SET coins = coins - ? WHERE uuid = ? AND coins >= ?",
                    costLong, owner.toString(), costLong) : 1;
            if (debitAffected < 0) {
                throw new IllegalStateException("homeの料金引き落としに失敗しました");
            }
            if (debitAffected == 0) {
                result.set(SetResult.INSUFFICIENT_FUNDS);
                return;
            }
            if (DatabaseManager.insert("homes", Map.of(
                    "uuid", owner.toString(), "name", name, "world", location.getWorld().getName(),
                    "x", location.getX(), "y", location.getY(), "z", location.getZ(),
                    "yaw", location.getYaw(), "pitch", location.getPitch())) != 1) {
                throw new IllegalStateException("homeの保存に失敗しました");
            }
            result.set(SetResult.SUCCESS);
        });
        plugin.getEconomyManager().invalidateBalance(owner);
        return committed ? result.get() : SetResult.DATABASE_ERROR;
    }

    /** 削除に成功したらtrue、存在しなかったかDB削除に失敗したらfalse。 */
    public boolean delete(UUID owner, String name) {
        if (!exists(owner, name)) {
            return false;
        }
        return DatabaseManager.execute("DELETE FROM homes WHERE uuid = ? AND name = ?", owner.toString(), name) > 0;
    }

    /** 無ければnull（ワールドが存在しない場合も含む）。 */
    public Location get(UUID owner, String name) {
        return DatabaseManager.queryOne(
                "SELECT world, x, y, z, yaw, pitch FROM homes WHERE uuid = ? AND name = ?",
                HomeManager::mapLocation,
                owner.toString(), name
        );
    }

    public List<String> listNames(UUID owner) {
        return DatabaseManager.query(
                "SELECT name FROM homes WHERE uuid = ? ORDER BY name",
                rs -> rs.getString("name"),
                owner.toString()
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
