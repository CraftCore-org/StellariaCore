package org.craftcore.stellaria.managers;

import net.milkbowl.vault.economy.EconomyResponse;
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
 * warpsテーブル（name主キー, owner_uuid, world, x, y, z, yaw, pitch）へのCRUDと、
 * 上限数・名前重複（サーバー全体でユニーク）・経済コストのチェックをまとめる。
 */
public class WarpManager {

    public enum SetResult { SUCCESS, LIMIT_REACHED, NAME_TAKEN, INSUFFICIENT_FUNDS, DATABASE_ERROR }

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
        int max = Math.max(0, plugin.getConfigManager().getInt("warp.max-per-player", 5));
        double cost = plugin.getConfigManager().getDouble("warp.cost", 0);
        if (cost < 0 || !Double.isFinite(cost) || cost > Long.MAX_VALUE || cost != Math.rint(cost)) {
            plugin.getLogger().severe("warp.cost は0以上の整数で指定してください: " + cost);
            return SetResult.DATABASE_ERROR;
        }
        long costLong = (long) cost;
        AtomicReference<SetResult> result = new AtomicReference<>(SetResult.DATABASE_ERROR);
        boolean committed = DatabaseManager.transaction(connection -> {
            if (count(owner) >= max) {
                result.set(SetResult.LIMIT_REACHED);
                return;
            }
            if (exists(name)) {
                result.set(SetResult.NAME_TAKEN);
                return;
            }
            int debitAffected = costLong > 0 ? DatabaseManager.execute(
                    "UPDATE players SET coins = coins - ? WHERE uuid = ? AND coins >= ?",
                    costLong, owner.toString(), costLong) : 1;
            if (debitAffected < 0) {
                throw new IllegalStateException("warpの料金引き落としに失敗しました");
            }
            if (debitAffected == 0) {
                result.set(SetResult.INSUFFICIENT_FUNDS);
                return;
            }
            if (DatabaseManager.insert("warps", Map.of(
                    "name", name, "owner_uuid", owner.toString(), "world", location.getWorld().getName(),
                    "x", location.getX(), "y", location.getY(), "z", location.getZ(),
                    "yaw", location.getYaw(), "pitch", location.getPitch())) != 1) {
                throw new IllegalStateException("warpの保存に失敗しました");
            }
            result.set(SetResult.SUCCESS);
        });
        plugin.getEconomyManager().invalidateBalance(owner);
        return committed ? result.get() : SetResult.DATABASE_ERROR;
    }

    /** 所有者UUID。存在しなければnull（delete権限チェック用）。 */
    public UUID getOwner(String name) {
        return DatabaseManager.queryOne(
                "SELECT owner_uuid FROM warps WHERE name = ?",
                rs -> UUID.fromString(rs.getString("owner_uuid")),
                name
        );
    }

    /** delete()の結果。refundAmountは返金が有効だった場合の実際の返金額（無効/0なら0）。 */
    public record DeleteResult(boolean deleted, double refundAmount) {
        public static final DeleteResult NOT_FOUND = new DeleteResult(false, 0);
    }

    /**
     * warp削除。権限チェックは呼び出し側(WarpCommand)の責務。存在すれば削除し、
     * warp.delete-refund-rateが0より大きければwarp.costにその割合を掛けた額を「所有者」に返金する
     * （代理削除でも返金先は所有者。削除と返金は1トランザクションにまとめ、返金失敗時は削除ごとロールバックする）。
     */
    public DeleteResult delete(String name) {
        UUID owner = getOwner(name);
        if (owner == null) {
            return DeleteResult.NOT_FOUND;
        }
        double cost = plugin.getConfigManager().getDouble("warp.cost", 0);
        double rate = plugin.getConfigManager().getDouble("warp.delete-refund-rate", 0);
        double refundAmount = cost > 0 && rate > 0 ? Math.round(cost * rate) : 0;
        boolean refundEnabled = refundAmount > 0;

        AtomicReference<Boolean> deleted = new AtomicReference<>(false);
        boolean committed = DatabaseManager.transaction(connection -> {
            int affected = DatabaseManager.execute("DELETE FROM warps WHERE name = ?", name);
            if (affected <= 0) {
                return;
            }
            deleted.set(true);
            if (refundEnabled) {
                EconomyResponse response = plugin.getEconomyManager().depositPlayer(Bukkit.getOfflinePlayer(owner), refundAmount);
                if (!response.transactionSuccess()) {
                    throw new IllegalStateException("warp削除時の返金に失敗しました: " + owner + " amount=" + refundAmount);
                }
            }
        });

        if (!committed || !deleted.get()) {
            return DeleteResult.NOT_FOUND;
        }
        if (refundEnabled) {
            plugin.getEconomyManager().recordEarning(owner, refundAmount);
        }
        return new DeleteResult(true, refundEnabled ? refundAmount : 0);
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
