package org.craftcore.stellaria.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 土地保護（/land）のチャンク所有権と縄張り（territory）を管理する。
 * BlockBreakEvent等のホットパスから毎回SQLiteへ問い合わせるのを避けるため、
 * 起動時にDBから全件ロードしたインメモリキャッシュ（claimsByChunk/territories）で判定する。
 * 更新系メソッドはDB書き込みとキャッシュ更新を同時に行う。
 */
public class LandManager {

    /** チャンク座標のキー。ワールド名 + チャンクX/Z（ブロック座標を4ビットシフトしたもの）。 */
    public record ChunkKey(String world, int chunkX, int chunkZ) {
        public static ChunkKey of(Location location) {
            return new ChunkKey(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        }
    }

    /** 縄張り（隣接するclaimの集合）。信頼リストとPvP許可を共有する単位。 */
    private static final class Territory {
        boolean pvpEnabled;
        final Set<UUID> trusted = new HashSet<>();

        Territory(boolean pvpEnabled) {
            this.pvpEnabled = pvpEnabled;
        }
    }

    private record Claim(UUID owner, String territoryId) {
    }

    public enum ClaimResult { SUCCESS, ALREADY_CLAIMED, LIMIT_REACHED, INSUFFICIENT_FUNDS, WORLD_DISABLED }

    public enum ActionResult { SUCCESS, NOT_CLAIMED, NOT_OWNER }

    private static final int[][] NEIGHBOR_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final StellariaCore plugin;
    private final Map<ChunkKey, Claim> claimsByChunk = new ConcurrentHashMap<>();
    private final Map<String, Territory> territories = new ConcurrentHashMap<>();

    public LandManager(StellariaCore plugin) {
        this.plugin = plugin;
        loadFromDatabase();
    }

    // ------------------------------------------------------------------
    // 起動時ロード
    // ------------------------------------------------------------------

    private record TerritoryRow(String territoryId, boolean pvpEnabled) {
    }

    private record ClaimRow(String world, int chunkX, int chunkZ, UUID owner, String territoryId) {
    }

    private record TrustRow(String territoryId, UUID trustedUuid) {
    }

    private void loadFromDatabase() {
        List<TerritoryRow> territoryRows = DatabaseManager.query(
                "SELECT territory_id, pvp_enabled FROM land_territories",
                rs -> new TerritoryRow(rs.getString("territory_id"), rs.getInt("pvp_enabled") != 0));
        for (TerritoryRow row : territoryRows) {
            territories.put(row.territoryId(), new Territory(row.pvpEnabled()));
        }

        List<ClaimRow> claimRows = DatabaseManager.query(
                "SELECT world, chunk_x, chunk_z, owner_uuid, territory_id FROM land_claims",
                rs -> new ClaimRow(rs.getString("world"), rs.getInt("chunk_x"), rs.getInt("chunk_z"),
                        UUID.fromString(rs.getString("owner_uuid")), rs.getString("territory_id")));
        for (ClaimRow row : claimRows) {
            claimsByChunk.put(new ChunkKey(row.world(), row.chunkX(), row.chunkZ()),
                    new Claim(row.owner(), row.territoryId()));
        }

        List<TrustRow> trustRows = DatabaseManager.query(
                "SELECT territory_id, trusted_uuid FROM land_trusts",
                rs -> new TrustRow(rs.getString("territory_id"), UUID.fromString(rs.getString("trusted_uuid"))));
        for (TrustRow row : trustRows) {
            Territory territory = territories.get(row.territoryId());
            if (territory != null) {
                territory.trusted.add(row.trustedUuid());
            }
        }
    }

    // ------------------------------------------------------------------
    // claim / unclaim
    // ------------------------------------------------------------------

    public int countClaims(UUID owner) {
        int count = 0;
        for (Claim claim : claimsByChunk.values()) {
            if (claim.owner().equals(owner)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 現在地のチャンクをclaimする。ワールド許可・重複・上限・残高の順にチェックし、
     * 最初に失敗した理由を返す。全て通ればコストを引き落とし、隣接claimがあれば
     * 同じ縄張りに合流（複数の縄張りに隣接していればマージ）する。
     */
    public ClaimResult claim(Player player) {
        ChunkKey key = ChunkKey.of(player.getLocation());

        List<String> enabledWorlds = plugin.getConfigManager().getStringList("land.enabled-worlds");
        if (!enabledWorlds.contains(key.world())) {
            return ClaimResult.WORLD_DISABLED;
        }
        if (claimsByChunk.containsKey(key)) {
            return ClaimResult.ALREADY_CLAIMED;
        }

        UUID owner = player.getUniqueId();
        int max = plugin.getConfigManager().getInt("land.max-chunks-per-player", 20);
        if (countClaims(owner) >= max) {
            return ClaimResult.LIMIT_REACHED;
        }

        double cost = plugin.getConfigManager().getDouble("land.cost-per-chunk", 500);
        EconomyManager economy = plugin.getEconomyManager();
        if (cost > 0 && !economy.has(player, cost)) {
            return ClaimResult.INSUFFICIENT_FUNDS;
        }
        if (cost > 0) {
            economy.withdrawPlayer(player, cost);
        }

        String territoryId = resolveTerritoryForNewClaim(key, owner);

        DatabaseManager.insert("land_claims", Map.of(
                "world", key.world(),
                "chunk_x", key.chunkX(),
                "chunk_z", key.chunkZ(),
                "owner_uuid", owner.toString(),
                "territory_id", territoryId,
                "claimed_at", System.currentTimeMillis()
        ));
        claimsByChunk.put(key, new Claim(owner, territoryId));

        return ClaimResult.SUCCESS;
    }

    /**
     * 隣接4方向のうち自分が持つclaimの縄張りIDを集める。0件なら新規縄張り、1件ならそれを再利用、
     * 2件以上ならcanonical（最初に見つかったもの）へ全部マージする。
     */
    private String resolveTerritoryForNewClaim(ChunkKey key, UUID owner) {
        Set<String> adjacentTerritories = new LinkedHashSet<>();
        for (int[] offset : NEIGHBOR_OFFSETS) {
            ChunkKey neighborKey = new ChunkKey(key.world(), key.chunkX() + offset[0], key.chunkZ() + offset[1]);
            Claim neighborClaim = claimsByChunk.get(neighborKey);
            if (neighborClaim != null && neighborClaim.owner().equals(owner)) {
                adjacentTerritories.add(neighborClaim.territoryId());
            }
        }

        if (adjacentTerritories.isEmpty()) {
            String territoryId = UUID.randomUUID().toString();
            territories.put(territoryId, new Territory(false));
            DatabaseManager.insert("land_territories", Map.of("territory_id", territoryId, "pvp_enabled", 0));
            return territoryId;
        }

        Iterator<String> iterator = adjacentTerritories.iterator();
        String canonicalId = iterator.next();
        while (iterator.hasNext()) {
            mergeTerritory(iterator.next(), canonicalId);
        }
        return canonicalId;
    }

    /** mergedIdの全claim・信頼リストをcanonicalIdへ付け替え、mergedId側の縄張りは削除する。 */
    private void mergeTerritory(String mergedId, String canonicalId) {
        Territory canonical = territories.get(canonicalId);
        Territory merged = territories.remove(mergedId);
        if (merged != null) {
            canonical.trusted.addAll(merged.trusted);
        }

        for (Map.Entry<ChunkKey, Claim> entry : claimsByChunk.entrySet()) {
            Claim claim = entry.getValue();
            if (claim.territoryId().equals(mergedId)) {
                entry.setValue(new Claim(claim.owner(), canonicalId));
            }
        }

        DatabaseManager.execute("UPDATE land_claims SET territory_id = ? WHERE territory_id = ?", canonicalId, mergedId);
        if (merged != null) {
            for (UUID trustedUuid : merged.trusted) {
                DatabaseManager.execute(
                        "INSERT OR IGNORE INTO land_trusts (territory_id, trusted_uuid) VALUES (?, ?)",
                        canonicalId, trustedUuid.toString());
            }
        }
        DatabaseManager.execute("DELETE FROM land_trusts WHERE territory_id = ?", mergedId);
        DatabaseManager.execute("DELETE FROM land_territories WHERE territory_id = ?", mergedId);
    }

    /**
     * 現在地のチャンクのclaimを解除する。adminOverrideがfalseの場合、実行者がオーナーでなければ失敗する。
     * 返金は常に元のオーナーに対して行う（adminOverrideで他人のclaimを解除した場合も同じ）。
     * 解除後、その縄張りを参照するclaimが無くなったら縄張り・信頼リストも削除する。
     */
    public ActionResult unclaim(Player player, boolean adminOverride) {
        ChunkKey key = ChunkKey.of(player.getLocation());
        Claim claim = claimsByChunk.get(key);
        if (claim == null) {
            return ActionResult.NOT_CLAIMED;
        }
        if (!adminOverride && !claim.owner().equals(player.getUniqueId())) {
            return ActionResult.NOT_OWNER;
        }

        claimsByChunk.remove(key);
        DatabaseManager.execute("DELETE FROM land_claims WHERE world = ? AND chunk_x = ? AND chunk_z = ?",
                key.world(), key.chunkX(), key.chunkZ());

        if (plugin.getConfigManager().getBoolean("land.refund-on-unclaim", true)) {
            double cost = plugin.getConfigManager().getDouble("land.cost-per-chunk", 500);
            plugin.getEconomyManager().depositPlayer(Bukkit.getOfflinePlayer(claim.owner()), cost);
        }

        boolean territoryStillUsed = claimsByChunk.values().stream()
                .anyMatch(c -> c.territoryId().equals(claim.territoryId()));
        if (!territoryStillUsed) {
            territories.remove(claim.territoryId());
            DatabaseManager.execute("DELETE FROM land_trusts WHERE territory_id = ?", claim.territoryId());
            DatabaseManager.execute("DELETE FROM land_territories WHERE territory_id = ?", claim.territoryId());
        }

        return ActionResult.SUCCESS;
    }
}
