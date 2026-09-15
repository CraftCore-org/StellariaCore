package org.craftcore.stellaria.managers;

import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.TreeUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 木こり機能（/kikori）の状態管理・人工物タグ管理・購入処理。
 * トグル状態とpass予約はAfkManagerと同様インメモリのみ（永続化なし）。
 * 購入済みフラグ（players.kikori_unlocked）だけはDB永続化する。
 */
public class KikoriManager {

    private final StellariaCore plugin;
    private final NamespacedKey artificialLogsKey;

    private final Set<UUID> enabledPlayers = new HashSet<>();
    private final Map<UUID, Long> lastFellMillis = new HashMap<>();
    private final Set<UUID> pendingPass = new HashSet<>();

    public KikoriManager(StellariaCore plugin) {
        this.plugin = plugin;
        this.artificialLogsKey = new NamespacedKey(plugin, "kikori_artificial_logs");
    }

    // ------------------------------------------------------------------
    // トグル状態
    // ------------------------------------------------------------------

    public boolean isEnabled(UUID uuid) {
        return enabledPlayers.contains(uuid);
    }

    /** /kikori・/kikori on・/kikori off・タイムアウトから呼ぶ。OFFにする時は必ずpassもリセットする。 */
    public void setEnabled(Player player, boolean enabled) {
        UUID uuid = player.getUniqueId();
        if (enabled) {
            enabledPlayers.add(uuid);
            lastFellMillis.put(uuid, System.currentTimeMillis());
        } else {
            enabledPlayers.remove(uuid);
            pendingPass.remove(uuid);
        }
    }

    /** タイムアウト監視。kikori.enabled が true の間、10秒毎に呼ばれる想定（AfkManagerと同方式）。 */
    public void tick() {
        long timeoutMillis = plugin.getConfigManager().getInt("kikori.timeout-seconds", 300) * 1000L;
        long now = System.currentTimeMillis();

        for (UUID uuid : new HashSet<>(enabledPlayers)) {
            Long lastFell = lastFellMillis.get(uuid);
            if (lastFell == null || now - lastFell <= timeoutMillis) {
                continue;
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            setEnabled(player, false);
            player.sendMessage(plugin.getConfigManager().getMessage("kikori.timeout_disabled", player));
        }
    }

    // ------------------------------------------------------------------
    // pass（人工物の一時無視）
    // ------------------------------------------------------------------

    public boolean hasPendingPass(UUID uuid) {
        return pendingPass.contains(uuid);
    }

    /** /kikori pass から呼ぶ。passを予約し、まだOFFなら自動でONにする。 */
    public void grantPass(Player player) {
        pendingPass.add(player.getUniqueId());
        if (!isEnabled(player.getUniqueId())) {
            setEnabled(player, true);
        }
    }

    // ------------------------------------------------------------------
    // 購入・アンロック
    // ------------------------------------------------------------------

    public boolean isUnlocked(OfflinePlayer player) {
        Integer unlocked = DatabaseManager.queryOne(
                "SELECT kikori_unlocked FROM players WHERE uuid = ?",
                rs -> rs.getInt("kikori_unlocked"),
                player.getUniqueId().toString()
        );
        return unlocked != null && unlocked != 0;
    }

    public enum PurchaseResult { SUCCESS, ALREADY_UNLOCKED, INSUFFICIENT_FUNDS }

    /** /kikori buy から呼ぶ。kikori.price をEconomyManagerから引き落とし、成功したらDBのフラグを立てる。 */
    public PurchaseResult purchase(Player player) {
        if (isUnlocked(player)) {
            return PurchaseResult.ALREADY_UNLOCKED;
        }
        int price = plugin.getConfigManager().getInt("kikori.price", 50000);
        EconomyResponse response = plugin.getEconomyManager().withdrawPlayer(player, price);
        if (!response.transactionSuccess()) {
            return PurchaseResult.INSUFFICIENT_FUNDS;
        }
        DatabaseManager.updateAsync("players", Map.of("kikori_unlocked", 1), "uuid = ?", player.getUniqueId().toString());
        return PurchaseResult.SUCCESS;
    }

    // ------------------------------------------------------------------
    // 人工物タグ（丸太）: チャンクのPersistentDataContainerに座標を記録
    // ------------------------------------------------------------------

    /** 皮むき丸太は無条件で、それ以外はチャンクPDCのタグで人工物かどうか判定する。 */
    public boolean isArtificialLog(Block block) {
        if (TreeUtil.isStrippedLog(block.getType())) {
            return true;
        }
        return readTaggedCoords(block.getChunk()).contains(packLocalCoord(block));
    }

    /** BlockPlaceEventから呼ぶ。丸太が置かれた座標をチャンクPDCに追加する。 */
    public void markArtificialLog(Block block) {
        Chunk chunk = block.getChunk();
        Set<Long> coords = readTaggedCoords(chunk);
        if (coords.add(packLocalCoord(block))) {
            writeTaggedCoords(chunk, coords);
        }
    }

    /** BlockBreakEvent（原因を問わず丸太が消える全ケース）から呼ぶ。タグを消す。 */
    public void unmarkArtificialLog(Block block) {
        Chunk chunk = block.getChunk();
        Set<Long> coords = readTaggedCoords(chunk);
        if (coords.remove(packLocalCoord(block))) {
            writeTaggedCoords(chunk, coords);
        }
    }

    private Set<Long> readTaggedCoords(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        long[] packed = pdc.get(artificialLogsKey, PersistentDataType.LONG_ARRAY);
        Set<Long> coords = new HashSet<>();
        if (packed != null) {
            for (long value : packed) {
                coords.add(value);
            }
        }
        return coords;
    }

    private void writeTaggedCoords(Chunk chunk, Set<Long> coords) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        if (coords.isEmpty()) {
            pdc.remove(artificialLogsKey);
            return;
        }
        long[] packed = new long[coords.size()];
        int i = 0;
        for (long value : coords) {
            packed[i++] = value;
        }
        pdc.set(artificialLogsKey, PersistentDataType.LONG_ARRAY, packed);
    }

    /** チャンク内ローカル座標（X4bit・Z4bit・ワールド高さ9bit）を1つのlongにパックする。 */
    private long packLocalCoord(Block block) {
        long localX = block.getX() & 0xF;
        long localZ = block.getZ() & 0xF;
        long heightIndex = block.getY() + 64L; // -64〜319 -> 0〜383
        return (localX << 13) | (localZ << 9) | heightIndex;
    }

    // ------------------------------------------------------------------
    // ライフサイクル
    // ------------------------------------------------------------------

    /** 退出時に呼ぶ。トグル状態・pass予約を破棄する。 */
    public void removePlayer(UUID uuid) {
        enabledPlayers.remove(uuid);
        lastFellMillis.remove(uuid);
        pendingPass.remove(uuid);
    }
}
