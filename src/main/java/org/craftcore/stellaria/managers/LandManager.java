package org.craftcore.stellaria.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.WorldBlacklistUtil;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 土地保護（/land）のチャンク所有権とエリア（area、隣接するclaimの集合）を管理する。
 * BlockBreakEvent等のホットパスから毎回SQLiteへ問い合わせるのを避けるため、
 * 起動時にDBから全件ロードしたインメモリキャッシュ（claimsByChunk/areas）で判定する。
 * 更新系メソッドはDB書き込みとキャッシュ更新を同時に行う。
 *
 * DBのテーブル名・カラム名（land_territories, territory_id等）は移行時の履歴的事情で
 * "territory"のままだが、これは完全に裏側の実装詳細でありユーザーからは一切見えない。
 * コード上の概念名・コマンド名・メッセージは全て「エリア(area)」に統一している。
 */
public class LandManager {

    /** チャンク座標のキー。ワールド名 + チャンクX/Z（ブロック座標を4ビットシフトしたもの）。 */
    public record ChunkKey(String world, int chunkX, int chunkZ) {
        public static ChunkKey of(Location location) {
            return new ChunkKey(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        }
    }

    /** エリア（隣接するclaimの集合）。信頼リストと各種許可フラグを共有する単位。 */
    private static final class Area {
        boolean pvpEnabled;
        boolean explosionsAllowed;
        boolean doorsOpenToOthers;
        boolean chestsOpenToOthers;
        final Set<UUID> trusted = new HashSet<>();

        Area(boolean pvpEnabled, boolean explosionsAllowed, boolean doorsOpenToOthers, boolean chestsOpenToOthers) {
            this.pvpEnabled = pvpEnabled;
            this.explosionsAllowed = explosionsAllowed;
            this.doorsOpenToOthers = doorsOpenToOthers;
            this.chestsOpenToOthers = chestsOpenToOthers;
        }
    }

    /**
     * pvpOverride等はnullなら「個別設定なし＝エリアの設定に従う」、非nullならそのチャンクだけの
     * 個別設定（/land rule）としてエリア設定を上書きする。claim直後は全てnull。
     */
    private record Claim(UUID owner, String areaId, Double claimedCost, Boolean pvpOverride, Boolean explosionsOverride,
                          Boolean doorsOverride, Boolean chestsOverride) {

        static Claim newClaim(UUID owner, String areaId, double claimedCost) {
            return new Claim(owner, areaId, claimedCost, null, null, null, null);
        }

        /** areaIdだけ差し替えた新しいClaimを返す（マージ時、個別設定はそのまま引き継ぐ）。 */
        Claim withAreaId(String newAreaId) {
            return new Claim(owner, newAreaId, claimedCost, pvpOverride, explosionsOverride, doorsOverride, chestsOverride);
        }

        /** 指定フラグの個別設定だけ差し替えた新しいClaimを返す。 */
        Claim withOverride(AreaFlag flag, Boolean value) {
            return switch (flag) {
                case PVP -> new Claim(owner, areaId, claimedCost, value, explosionsOverride, doorsOverride, chestsOverride);
                case EXPLOSIONS -> new Claim(owner, areaId, claimedCost, pvpOverride, value, doorsOverride, chestsOverride);
                case DOORS -> new Claim(owner, areaId, claimedCost, pvpOverride, explosionsOverride, value, chestsOverride);
                case CHESTS -> new Claim(owner, areaId, claimedCost, pvpOverride, explosionsOverride, doorsOverride, value);
            };
        }

        Boolean overrideFor(AreaFlag flag) {
            return switch (flag) {
                case PVP -> pvpOverride;
                case EXPLOSIONS -> explosionsOverride;
                case DOORS -> doorsOverride;
                case CHESTS -> chestsOverride;
            };
        }
    }

    public enum ClaimResult { SUCCESS, ALREADY_CLAIMED, UNCLAIMABLE, LIMIT_REACHED, INSUFFICIENT_FUNDS, WORLD_DISABLED, DATABASE_ERROR }

    public enum ActionResult { SUCCESS, NOT_CLAIMED, NOT_OWNER, SELF_TARGET, DATABASE_ERROR }

    public enum UnclaimableChunkResult { SUCCESS, ALREADY_CLAIMED, ALREADY_UNCLAIMABLE, NOT_UNCLAIMABLE, DATABASE_ERROR }

    /** エリアのトグル可能な設定項目。/land area <flag> on|off の対象を1つのメソッドにまとめるための列挙。 */
    public enum AreaFlag { PVP, EXPLOSIONS, DOORS, CHESTS }

    /**
     * claim()の結果。mergedは「2つ以上の既存エリアを1つに統合した」時だけtrue（単に既存エリアに
     * 1個合流しただけならfalse）。pvpEnabledは統合後（または新規/合流後）のエリアのPvP状態。
     * resultがSUCCESS以外の場合、merged/pvpEnabledの値は意味を持たない。
     */
    public record ClaimOutcome(ClaimResult result, boolean merged, boolean pvpEnabled) {
        private static ClaimOutcome of(ClaimResult result) {
            return new ClaimOutcome(result, false, false);
        }
    }

    /**
     * unclaim()の結果。refundAmountはSUCCESSかつ返金対象だった場合の実際の返金額
     * （claim時に支払った金額、または移行前の既存行ならconfigのフォールバック価格）。
     * resultがSUCCESS以外、または返金なしの場合は0。呼び出し側は必ずこの値を表示に使い、
     * 現在のconfig価格をそのまま表示しないこと（config変更後は一致しなくなるため）。
     */
    public record UnclaimOutcome(ActionResult result, double refundAmount) {
        private static UnclaimOutcome of(ActionResult result) {
            return new UnclaimOutcome(result, 0);
        }
    }

    private static final int[][] NEIGHBOR_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final StellariaCore plugin;
    private final Map<ChunkKey, Claim> claimsByChunk = new ConcurrentHashMap<>();
    private final Map<String, Area> areas = new ConcurrentHashMap<>();
    private final UnclaimableChunkRegistry unclaimableChunks = new UnclaimableChunkRegistry();
    /** stellaria.land.adminを持つプレイヤーが/land bypassで自発的にON/OFFする、保護無視モード。 */
    private final Set<UUID> bypassEnabled = ConcurrentHashMap.newKeySet();
    /** claimの追加・削除ごとに増やす。境界パーティクルの座標キャッシュを更新するために使う。 */
    private long claimsVersion;

    public LandManager(StellariaCore plugin) {
        this.plugin = plugin;
        // NULL許容で追加する（DEFAULTを入れると、この列が無かった頃の既存claim行が
        // 一律0円扱いになり、unclaim時にconfig価格へフォールバックできなくなるため）。
        DatabaseManager.addColumnIfNotExists("land_claims", "claimed_cost REAL");
        loadFromDatabase();
    }

    // ------------------------------------------------------------------
    // 起動時ロード
    // ------------------------------------------------------------------

    private record AreaRow(String areaId, boolean pvpEnabled, boolean explosionsAllowed,
                            boolean doorsOpenToOthers, boolean chestsOpenToOthers) {
    }

    private record ClaimRow(String world, int chunkX, int chunkZ, UUID owner, String areaId, Double claimedCost,
                             Boolean pvpOverride, Boolean explosionsOverride,
                             Boolean doorsOverride, Boolean chestsOverride) {
    }

    /** SQLiteのnullable INTEGER列をBoolean（null=未設定, true/false=0/1）として読む。 */
    private static Boolean readNullableBoolean(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value != 0;
    }

    /** SQLiteのnullable REAL列をDouble（null=未記録、claimed_cost列追加前の既存行）として読む。 */
    private static Double readNullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private record TrustRow(String areaId, UUID trustedUuid) {
    }

    private void loadFromDatabase() {
        List<AreaRow> areaRows = DatabaseManager.query(
                "SELECT territory_id, pvp_enabled, explosions_allowed, doors_open, chests_open FROM land_territories",
                rs -> new AreaRow(rs.getString("territory_id"), rs.getInt("pvp_enabled") != 0,
                        rs.getInt("explosions_allowed") != 0, rs.getInt("doors_open") != 0, rs.getInt("chests_open") != 0));
        for (AreaRow row : areaRows) {
            areas.put(row.areaId(), new Area(row.pvpEnabled(), row.explosionsAllowed(),
                    row.doorsOpenToOthers(), row.chestsOpenToOthers()));
        }

        List<ClaimRow> claimRows = DatabaseManager.query(
                "SELECT world, chunk_x, chunk_z, owner_uuid, territory_id, claimed_cost, "
                        + "pvp_override, explosions_override, doors_override, chests_override FROM land_claims",
                rs -> new ClaimRow(rs.getString("world"), rs.getInt("chunk_x"), rs.getInt("chunk_z"),
                        UUID.fromString(rs.getString("owner_uuid")), rs.getString("territory_id"), readNullableDouble(rs, "claimed_cost"),
                        readNullableBoolean(rs, "pvp_override"), readNullableBoolean(rs, "explosions_override"),
                        readNullableBoolean(rs, "doors_override"), readNullableBoolean(rs, "chests_override")));
        for (ClaimRow row : claimRows) {
            // land_territories側の行が欠落していても（本来あり得ないが、mergeAreas()が
            // transaction()で保護されていないための保険として）Areaを必ず用意しておく。
            areas.computeIfAbsent(row.areaId(), id -> new Area(false, false, false, false));
            claimsByChunk.put(new ChunkKey(row.world(), row.chunkX(), row.chunkZ()),
                    new Claim(row.owner(), row.areaId(), row.claimedCost(), row.pvpOverride(), row.explosionsOverride(),
                            row.doorsOverride(), row.chestsOverride()));
        }

        List<ChunkKey> unclaimableRows = DatabaseManager.query(
                "SELECT world, chunk_x, chunk_z FROM land_unclaimable_chunks",
                rs -> new ChunkKey(rs.getString("world"), rs.getInt("chunk_x"), rs.getInt("chunk_z")));
        for (ChunkKey key : unclaimableRows) {
            unclaimableChunks.mark(key);
        }

        List<TrustRow> trustRows = DatabaseManager.query(
                "SELECT territory_id, trusted_uuid FROM land_trusts",
                rs -> new TrustRow(rs.getString("territory_id"), UUID.fromString(rs.getString("trusted_uuid"))));
        for (TrustRow row : trustRows) {
            Area area = areas.get(row.areaId());
            if (area != null) {
                area.trusted.add(row.trustedUuid());
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
     * 同じエリアに合流（複数のエリアに隣接していればマージ）する。
     */
    public ClaimOutcome claim(Player player) {
        ChunkKey key = ChunkKey.of(player.getLocation());

        if (isWorldDisabled(key.world())) {
            return ClaimOutcome.of(ClaimResult.WORLD_DISABLED);
        }
        if (claimsByChunk.containsKey(key)) {
            return ClaimOutcome.of(ClaimResult.ALREADY_CLAIMED);
        }
        if (unclaimableChunks.isMarked(key)) {
            return ClaimOutcome.of(ClaimResult.UNCLAIMABLE);
        }

        UUID owner = player.getUniqueId();
        int max = plugin.getConfigManager().getInt("land.max-chunks-per-player", 20);
        if (countClaims(owner) >= max) {
            return ClaimOutcome.of(ClaimResult.LIMIT_REACHED);
        }

        double cost = plugin.getConfigManager().getDouble("land.cost-per-chunk", 500);
        if (cost > 0) {
            net.milkbowl.vault.economy.EconomyResponse response = plugin.getEconomyManager().withdrawPlayer(player, cost);
            if (!response.transactionSuccess()) {
                return ClaimOutcome.of(ClaimResult.INSUFFICIENT_FUNDS);
            }
        }

        AreaResolution resolution = resolveAreaForNewClaim(key, owner);
        if (resolution == null) {
            // land_territories へのINSERTに失敗（新規エリア作成時のみ発生しうる）。
            // 引き落とし済みのコストを返金し、キャッシュには一切触れずに失敗を返す。
            if (cost > 0) {
                plugin.getEconomyManager().depositPlayer(player, cost);
            }
            plugin.getLogger().severe("land_territories へのINSERTに失敗したためclaimを中止しました: " + key);
            return ClaimOutcome.of(ClaimResult.DATABASE_ERROR);
        }

        int inserted = DatabaseManager.insert("land_claims", Map.of(
                "world", key.world(),
                "chunk_x", key.chunkX(),
                "chunk_z", key.chunkZ(),
                "owner_uuid", owner.toString(),
                "territory_id", resolution.areaId(),
                "claimed_at", System.currentTimeMillis(),
                "claimed_cost", cost
        ));
        if (inserted <= 0) {
            if (cost > 0) {
                plugin.getEconomyManager().depositPlayer(player, cost);
            }
            plugin.getLogger().severe("land_claims へのINSERTに失敗したためclaimを中止しました: " + key);
            return ClaimOutcome.of(ClaimResult.DATABASE_ERROR);
        }
        claimsByChunk.put(key, Claim.newClaim(owner, resolution.areaId(), cost));
        claimsVersion++;

        Area area = areas.get(resolution.areaId());
        boolean pvpEnabled = area != null && area.pvpEnabled;
        return new ClaimOutcome(ClaimResult.SUCCESS, resolution.merged(), pvpEnabled);
    }

    /** resolveAreaForNewClaimの結果。mergedは2つ以上の既存エリアを統合した場合だけtrue。 */
    private record AreaResolution(String areaId, boolean merged) {
    }

    /**
     * 隣接4方向のうち自分が持つclaimのエリアIDを集める。0件なら新規エリア、1件ならそれを再利用、
     * 2件以上ならcanonical（最初に見つかったもの）へ全部マージする。
     */
    private AreaResolution resolveAreaForNewClaim(ChunkKey key, UUID owner) {
        Set<String> adjacentAreas = new LinkedHashSet<>();
        for (int[] offset : NEIGHBOR_OFFSETS) {
            ChunkKey neighborKey = new ChunkKey(key.world(), key.chunkX() + offset[0], key.chunkZ() + offset[1]);
            Claim neighborClaim = claimsByChunk.get(neighborKey);
            if (neighborClaim != null && neighborClaim.owner().equals(owner)) {
                adjacentAreas.add(neighborClaim.areaId());
            }
        }

        if (adjacentAreas.isEmpty()) {
            String areaId = UUID.randomUUID().toString();
            int inserted = DatabaseManager.insert("land_territories", Map.of(
                    "territory_id", areaId, "pvp_enabled", 0,
                    "explosions_allowed", 0, "doors_open", 0, "chests_open", 0));
            if (inserted <= 0) {
                return null;
            }
            areas.put(areaId, new Area(false, false, false, false));
            return new AreaResolution(areaId, false);
        }

        Iterator<String> iterator = adjacentAreas.iterator();
        String canonicalId = iterator.next();
        boolean merged = adjacentAreas.size() > 1;
        while (iterator.hasNext()) {
            mergeAreas(iterator.next(), canonicalId);
        }
        return new AreaResolution(canonicalId, merged);
    }

    /** mergedIdの全claim・信頼リストをcanonicalIdへ付け替え、mergedId側のエリアは削除する。 */
    private void mergeAreas(String mergedId, String canonicalId) {
        Area canonical = areas.get(canonicalId);
        Area merged = areas.get(mergedId);

        boolean success = DatabaseManager.transaction(conn -> {
            int updated = DatabaseManager.execute("UPDATE land_claims SET territory_id = ? WHERE territory_id = ?", canonicalId, mergedId);
            if (updated < 0) {
                throw new IllegalStateException("land_claims の territory_id 更新に失敗");
            }
            if (merged != null) {
                for (UUID trustedUuid : merged.trusted) {
                    int inserted = DatabaseManager.execute(
                            "INSERT OR IGNORE INTO land_trusts (territory_id, trusted_uuid) VALUES (?, ?)",
                            canonicalId, trustedUuid.toString());
                    if (inserted < 0) {
                        throw new IllegalStateException("land_trusts への付け替えINSERTに失敗");
                    }
                }
            }
            int deletedTrusts = DatabaseManager.execute("DELETE FROM land_trusts WHERE territory_id = ?", mergedId);
            if (deletedTrusts < 0) {
                throw new IllegalStateException("land_trusts の削除に失敗");
            }
            int deletedTerritory = DatabaseManager.execute("DELETE FROM land_territories WHERE territory_id = ?", mergedId);
            if (deletedTerritory < 0) {
                throw new IllegalStateException("land_territories の削除に失敗");
            }
        });

        if (!success) {
            plugin.getLogger().severe("エリアのマージに失敗しました（DBはロールバック済み、キャッシュは変更していません）: " + mergedId + " -> " + canonicalId);
            return;
        }

        areas.remove(mergedId);
        if (merged != null) {
            canonical.trusted.addAll(merged.trusted);
        }
        for (Map.Entry<ChunkKey, Claim> entry : claimsByChunk.entrySet()) {
            Claim claim = entry.getValue();
            if (claim.areaId().equals(mergedId)) {
                entry.setValue(claim.withAreaId(canonicalId));
            }
        }
    }

    /**
     * 現在地のチャンクのclaimを解除する。adminOverrideがfalseの場合、実行者がオーナーでなければ失敗する。
     * 返金は常に元のオーナーに対して行う（adminOverrideで他人のclaimを解除した場合も同じ）。
     * 解除後、そのエリアを参照するclaimが無くなったらエリア・信頼リストも削除する。
     */
    public UnclaimOutcome unclaim(Player player, boolean adminOverride) {
        ChunkKey key = ChunkKey.of(player.getLocation());
        Claim claim = claimsByChunk.get(key);
        if (claim == null) {
            return UnclaimOutcome.of(ActionResult.NOT_CLAIMED);
        }
        if (!adminOverride && !claim.owner().equals(player.getUniqueId())) {
            return UnclaimOutcome.of(ActionResult.NOT_OWNER);
        }

        // claimed_cost列が追加される前にclaimされた行はclaimedCostがnullなので、
        // その場合だけconfigの現在価格へフォールバックする（新しい行は必ず実際の支払額を持つ）。
        double refundAmount = claim.claimedCost() != null
                ? claim.claimedCost()
                : plugin.getConfigManager().getDouble("land.cost-per-chunk", 500);
        boolean refundEnabled = plugin.getConfigManager().getBoolean("land.refund-on-unclaim", true) && refundAmount > 0;

        java.util.concurrent.atomic.AtomicBoolean rowMissing = new java.util.concurrent.atomic.AtomicBoolean(false);

        // land_claimsの削除と返金を1トランザクションにまとめる。返金が失敗した場合は
        // claim削除もロールバックされるため、「土地は消えたのに返金だけ失われる」状態を防げる。
        boolean ok = DatabaseManager.transaction(conn -> {
            int affected = DatabaseManager.execute("DELETE FROM land_claims WHERE world = ? AND chunk_x = ? AND chunk_z = ?",
                    key.world(), key.chunkX(), key.chunkZ());
            if (affected <= 0) {
                rowMissing.set(true);
                throw new IllegalStateException("land_claims に該当行がありません: " + key);
            }
            if (refundEnabled) {
                net.milkbowl.vault.economy.EconomyResponse response = plugin.getEconomyManager()
                        .depositPlayer(Bukkit.getOfflinePlayer(claim.owner()), refundAmount);
                if (!response.transactionSuccess()) {
                    throw new IllegalStateException("unclaim時の返金に失敗しました: "
                            + claim.owner() + " amount=" + refundAmount + " reason=" + response.errorMessage);
                }
            }
        });

        if (!ok) {
            if (rowMissing.get()) {
                // キャッシュ上はclaim済みだったがDB側に該当行が無かった（矛盾した状態）。
                // 証明済みで誤りのキャッシュエントリなので、放置せずここで取り除く。
                claimsByChunk.remove(key);
                claimsVersion++;
                return UnclaimOutcome.of(ActionResult.NOT_CLAIMED);
            }
            // 返金失敗（またはその他のDBエラー）。claim削除もロールバックされているので、
            // 土地は解除されておらず返金も行われていない一貫した状態のまま。
            return new UnclaimOutcome(ActionResult.DATABASE_ERROR, 0);
        }

        claimsByChunk.remove(key);
        claimsVersion++;

        boolean areaStillUsed = claimsByChunk.values().stream()
                .anyMatch(c -> c.areaId().equals(claim.areaId()));
        if (!areaStillUsed) {
            areas.remove(claim.areaId());
            DatabaseManager.execute("DELETE FROM land_trusts WHERE territory_id = ?", claim.areaId());
            DatabaseManager.execute("DELETE FROM land_territories WHERE territory_id = ?", claim.areaId());
        }

        return new UnclaimOutcome(ActionResult.SUCCESS, refundEnabled ? refundAmount : 0);
    }

    // ------------------------------------------------------------------
    // 照会
    // ------------------------------------------------------------------

    /** 現在地のオーナー。未claimならnull。 */
    public UUID ownerOf(Location location) {
        if (isWorldDisabled(location.getWorld().getName())) {
            return null;
        }
        Claim claim = claimsByChunk.get(ChunkKey.of(location));
        return claim != null ? claim.owner() : null;
    }

    /** 指定チャンクが保護済みか。境界パーティクルの外周判定で使用する。 */
    public boolean isClaimed(ChunkKey key) {
        return !isWorldDisabled(key.world()) && claimsByChunk.containsKey(key);
    }

    public boolean isWorldDisabled(String worldName) {
        return WorldBlacklistUtil.isBlacklisted(
                plugin.getConfigManager().getStringList("land.disabled-worlds", true), worldName);
    }

    /** 指定チャンクが運営によって保護不可に設定されているか。 */
    public boolean isUnclaimable(ChunkKey key) {
        return unclaimableChunks.isMarked(key);
    }

    /**
     * 指定チャンクの保護不可設定を変更する。既にclaim済みのチャンクは設定できない。
     * 呼び出し側で管理者権限を確認する。
     */
    public UnclaimableChunkResult setUnclaimable(ChunkKey key, boolean enabled) {
        if (enabled) {
            if (claimsByChunk.containsKey(key)) {
                return UnclaimableChunkResult.ALREADY_CLAIMED;
            }
            if (!unclaimableChunks.mark(key)) {
                return UnclaimableChunkResult.ALREADY_UNCLAIMABLE;
            }
            int inserted = DatabaseManager.insert("land_unclaimable_chunks", Map.of(
                    "world", key.world(), "chunk_x", key.chunkX(), "chunk_z", key.chunkZ()));
            if (inserted <= 0) {
                unclaimableChunks.unmark(key);
                plugin.getLogger().severe("land_unclaimable_chunks へのINSERTに失敗しました: " + key);
                return UnclaimableChunkResult.DATABASE_ERROR;
            }
            return UnclaimableChunkResult.SUCCESS;
        }

        if (!unclaimableChunks.unmark(key)) {
            return UnclaimableChunkResult.NOT_UNCLAIMABLE;
        }
        int deleted = DatabaseManager.execute("DELETE FROM land_unclaimable_chunks WHERE world = ? AND chunk_x = ? AND chunk_z = ?",
                key.world(), key.chunkX(), key.chunkZ());
        if (deleted <= 0) {
            unclaimableChunks.mark(key);
            plugin.getLogger().severe("land_unclaimable_chunks からのDELETEに失敗しました: " + key);
            return UnclaimableChunkResult.DATABASE_ERROR;
        }
        return UnclaimableChunkResult.SUCCESS;
    }

    /** 指定チャンクのエリアID。未claimならnull。境界パーティクルでエリアごとの色分けに使う。 */
    public String areaIdOf(ChunkKey key) {
        if (isWorldDisabled(key.world())) {
            return null;
        }
        Claim claim = claimsByChunk.get(key);
        return claim != null ? claim.areaId() : null;
    }

    /** claim一覧が変化した世代。呼び出し側はキャッシュの再計算要否だけに用いる。 */
    public long claimsVersion() {
        return claimsVersion;
    }

    /**
     * このプレイヤーがこの場所でブロック操作できるか。
     * bypassモード中の管理者・未claim地・オーナー本人・エリアの信頼リストのいずれかでtrue。
     */
    public boolean canBuild(Location location, Player player) {
        if (isWorldDisabled(location.getWorld().getName())) {
            return true;
        }
        if (player.hasPermission("stellaria.land.admin") && hasBypassEnabled(player)) {
            return true;
        }
        Claim claim = claimsByChunk.get(ChunkKey.of(location));
        if (claim == null) {
            return true;
        }
        if (claim.owner().equals(player.getUniqueId())) {
            return true;
        }
        Area area = areas.get(claim.areaId());
        return area != null && area.trusted.contains(player.getUniqueId());
    }

    /**
     * この場所でPvPが許可されているか。未claim地は常にfalse。claim済みなら、このチャンク個別の
     * /land rule設定（pvpOverride）があればそれを優先し、無ければエリアのpvp_enabledに従う。
     */
    public boolean isPvpAllowed(Location location) {
        if (isWorldDisabled(location.getWorld().getName())) {
            return true;
        }
        Claim claim = claimsByChunk.get(ChunkKey.of(location));
        if (claim == null) {
            return false;
        }
        if (claim.pvpOverride() != null) {
            return claim.pvpOverride();
        }
        Area area = areas.get(claim.areaId());
        return area != null && area.pvpEnabled;
    }

    /** この場所で爆発ダメージが許可されているか。未claim地は保護対象外なので常にtrue。個別設定があれば優先。 */
    public boolean explosionsAllowed(Location location) {
        if (isWorldDisabled(location.getWorld().getName())) {
            return true;
        }
        Claim claim = claimsByChunk.get(ChunkKey.of(location));
        if (claim == null) {
            return true;
        }
        if (claim.explosionsOverride() != null) {
            return claim.explosionsOverride();
        }
        Area area = areas.get(claim.areaId());
        return area != null && area.explosionsAllowed;
    }

    /** この場所のドア・トラップドア・フェンスゲートを非オーナーでも開閉できるか。未claim地は常にtrue。個別設定があれば優先。 */
    public boolean doorsOpenToOthers(Location location) {
        if (isWorldDisabled(location.getWorld().getName())) {
            return true;
        }
        Claim claim = claimsByChunk.get(ChunkKey.of(location));
        if (claim == null) {
            return true;
        }
        if (claim.doorsOverride() != null) {
            return claim.doorsOverride();
        }
        Area area = areas.get(claim.areaId());
        return area != null && area.doorsOpenToOthers;
    }

    /** この場所のチェスト等の収納・作業台系ブロックを非オーナーでも開閉できるか。未claim地は常にtrue。個別設定があれば優先。 */
    public boolean chestsOpenToOthers(Location location) {
        if (isWorldDisabled(location.getWorld().getName())) {
            return true;
        }
        Claim claim = claimsByChunk.get(ChunkKey.of(location));
        if (claim == null) {
            return true;
        }
        if (claim.chestsOverride() != null) {
            return claim.chestsOverride();
        }
        Area area = areas.get(claim.areaId());
        return area != null && area.chestsOpenToOthers;
    }

    /**
     * このチャンクの指定フラグに個別設定（/land rule）が入っているか。
     * nullなら個別設定なし（エリアの設定に従っている）、非nullならその値が個別設定として優先されている。
     * 未claim地は常にnull。
     */
    public Boolean chunkRuleOverride(Location location, AreaFlag flag) {
        Claim claim = claimsByChunk.get(ChunkKey.of(location));
        return claim != null ? claim.overrideFor(flag) : null;
    }

    /** 現在地のエリアの信頼リスト。未claimなら空集合。 */
    public Set<UUID> trustedPlayers(Location location) {
        Claim claim = claimsByChunk.get(ChunkKey.of(location));
        if (claim == null) {
            return Set.of();
        }
        Area area = areas.get(claim.areaId());
        return area != null ? Set.copyOf(area.trusted) : Set.of();
    }

    /** ownerが所有するclaimが属するエリアの数（重複排除済み）。 */
    public int areaCountFor(UUID owner) {
        Set<String> ids = new HashSet<>();
        for (Claim claim : claimsByChunk.values()) {
            if (claim.owner().equals(owner)) {
                ids.add(claim.areaId());
            }
        }
        return ids.size();
    }

    // ------------------------------------------------------------------
    // 信頼(trust) / エリア設定トグル
    // ------------------------------------------------------------------

    /**
     * claim・オーナー確認の共通処理。エラーがあればActionResultを返し、問題なければnullを返す
     * （trust/untrust/setAreaFlagで繰り返される「claim存在確認→オーナー確認」を1箇所にまとめる）。
     */
    private ActionResult validateOwnedClaim(Claim claim, Player owner) {
        if (claim == null) {
            return ActionResult.NOT_CLAIMED;
        }
        if (!claim.owner().equals(owner.getUniqueId())) {
            return ActionResult.NOT_OWNER;
        }
        return null;
    }

    /** 現在地のエリアに信頼プレイヤーを追加する。実行者がオーナーである必要がある。自分自身は追加できない。 */
    public ActionResult trust(Player owner, UUID target) {
        if (target.equals(owner.getUniqueId())) {
            return ActionResult.SELF_TARGET;
        }
        Claim claim = claimsByChunk.get(ChunkKey.of(owner.getLocation()));
        ActionResult error = validateOwnedClaim(claim, owner);
        if (error != null) {
            return error;
        }
        Area area = areas.get(claim.areaId());
        if (area == null) {
            return ActionResult.NOT_CLAIMED;
        }
        if (!area.trusted.contains(target)) {
            int inserted = DatabaseManager.insert("land_trusts", Map.of(
                    "territory_id", claim.areaId(),
                    "trusted_uuid", target.toString()
            ));
            if (inserted > 0) {
                area.trusted.add(target);
            }
        }
        return ActionResult.SUCCESS;
    }

    /** 現在地のエリアから信頼プレイヤーを外す。実行者がオーナーである必要がある。 */
    public ActionResult untrust(Player owner, UUID target) {
        Claim claim = claimsByChunk.get(ChunkKey.of(owner.getLocation()));
        ActionResult error = validateOwnedClaim(claim, owner);
        if (error != null) {
            return error;
        }
        Area area = areas.get(claim.areaId());
        if (area == null) {
            return ActionResult.NOT_CLAIMED;
        }
        if (area.trusted.contains(target)) {
            int deleted = DatabaseManager.execute("DELETE FROM land_trusts WHERE territory_id = ? AND trusted_uuid = ?",
                    claim.areaId(), target.toString());
            if (deleted > 0) {
                area.trusted.remove(target);
            }
        }
        return ActionResult.SUCCESS;
    }

    /** 現在地のエリアの指定フラグ（PvP/爆発/ドア/チェスト）を切り替える。実行者がオーナーである必要がある。 */
    public ActionResult setAreaFlag(Player owner, AreaFlag flag, boolean enabled) {
        Claim claim = claimsByChunk.get(ChunkKey.of(owner.getLocation()));
        ActionResult error = validateOwnedClaim(claim, owner);
        if (error != null) {
            return error;
        }
        Area area = areas.get(claim.areaId());
        if (area == null) {
            return ActionResult.NOT_CLAIMED;
        }
        int affected = DatabaseManager.update("land_territories", Map.of(dbColumnFor(flag), enabled ? 1 : 0),
                "territory_id = ?", claim.areaId());
        if (affected <= 0) {
            plugin.getLogger().severe("land_territories の更新に失敗したためキャッシュは変更していません: " + claim.areaId());
            return ActionResult.DATABASE_ERROR;
        }
        switch (flag) {
            case PVP -> area.pvpEnabled = enabled;
            case EXPLOSIONS -> area.explosionsAllowed = enabled;
            case DOORS -> area.doorsOpenToOthers = enabled;
            case CHESTS -> area.chestsOpenToOthers = enabled;
        }
        return ActionResult.SUCCESS;
    }

    private String dbColumnFor(AreaFlag flag) {
        return switch (flag) {
            case PVP -> "pvp_enabled";
            case EXPLOSIONS -> "explosions_allowed";
            case DOORS -> "doors_open";
            case CHESTS -> "chests_open";
        };
    }

    /**
     * 現在地のチャンク「だけ」の個別設定（/land rule）を変更する。実行者がオーナーである必要がある。
     * valueがnullなら個別設定を解除し、そのチャンクは以後エリアの設定にまた従うようになる。
     * エリア全体に及ぶsetAreaFlagと違い、この変更は他のチャンクに一切影響しない。
     */
    public ActionResult setChunkRule(Player owner, AreaFlag flag, Boolean value) {
        ChunkKey key = ChunkKey.of(owner.getLocation());
        Claim claim = claimsByChunk.get(key);
        ActionResult error = validateOwnedClaim(claim, owner);
        if (error != null) {
            return error;
        }
        // Map.of(...)はnull値を許容しないため、個別設定の解除（value=null）ではHashMapを使う。
        Map<String, Object> values = new HashMap<>();
        values.put(dbColumnForOverride(flag), value == null ? null : (value ? 1 : 0));
        int affected = DatabaseManager.update("land_claims", values,
                "world = ? AND chunk_x = ? AND chunk_z = ?", key.world(), key.chunkX(), key.chunkZ());
        if (affected <= 0) {
            plugin.getLogger().severe("land_claims の個別設定更新に失敗したためキャッシュは変更していません: " + key);
            return ActionResult.DATABASE_ERROR;
        }
        claimsByChunk.put(key, claim.withOverride(flag, value));
        return ActionResult.SUCCESS;
    }

    private String dbColumnForOverride(AreaFlag flag) {
        return switch (flag) {
            case PVP -> "pvp_override";
            case EXPLOSIONS -> "explosions_override";
            case DOORS -> "doors_override";
            case CHESTS -> "chests_override";
        };
    }

    // ------------------------------------------------------------------
    // 管理者bypassモード（/land bypass）
    // ------------------------------------------------------------------

    /** stellaria.land.admin持ちが現在bypassモード中か。 */
    public boolean hasBypassEnabled(Player player) {
        return bypassEnabled.contains(player.getUniqueId());
    }

    /** bypassモードを切り替える。切り替え後の状態（true=ON）を返す。 */
    public boolean toggleBypass(Player player) {
        UUID uuid = player.getUniqueId();
        if (bypassEnabled.remove(uuid)) {
            return false;
        }
        bypassEnabled.add(uuid);
        return true;
    }

    /** 退出時に呼ぶ。bypassモードの状態を破棄する。 */
    public void removeBypassState(UUID uuid) {
        bypassEnabled.remove(uuid);
    }
}
