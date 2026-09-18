package org.craftcore.stellaria.managers;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 鉱石一括破壊機能（/mine）の状態管理・人工物タグ管理・購入処理。
 * pass予約はKikoriManagerと同様インメモリのみ（永続化なし）。
 * 購入済みフラグ（players.mine_unlocked）とトグルON/OFF状態（players.mine_enabled）はDB永続化する
 * （KikoriManagerと違い、再ログイン・サーバー再起動を挟んでもONを維持したいという要望のため）。
 * kikoriと違い、連結した鉱石は1tickずつではなく同一tick内で同期的に全て破壊する
 * （進行中タスクを跨いで保持する必要が無い分、KikoriManagerよりシンプル）。
 */
public class MineManager {

    private final StellariaCore plugin;
    private final NamespacedKey artificialOresKey;

    private final Set<UUID> enabledPlayers = new HashSet<>();
    private final Map<UUID, Long> lastMineMillis = new HashMap<>();
    private final Set<UUID> pendingPass = new HashSet<>();
    private final Set<Block> claimedBlocks = new HashSet<>();

    public MineManager(StellariaCore plugin) {
        this.plugin = plugin;
        this.artificialOresKey = new NamespacedKey(plugin, "mine_artificial_ores");
    }

    // ------------------------------------------------------------------
    // トグル状態
    // ------------------------------------------------------------------

    public boolean isEnabled(UUID uuid) {
        return enabledPlayers.contains(uuid);
    }

    /** /mine・/mine on・/mine off・タイムアウトから呼ぶ。OFFにする時は必ずpassもリセットする。 */
    public void setEnabled(Player player, boolean enabled) {
        UUID uuid = player.getUniqueId();
        if (enabled) {
            enabledPlayers.add(uuid);
            lastMineMillis.put(uuid, System.currentTimeMillis());
        } else {
            enabledPlayers.remove(uuid);
            pendingPass.remove(uuid);
        }
        DatabaseManager.executeAsync(
                "UPDATE players SET mine_enabled = ? WHERE uuid = ?",
                enabled ? 1 : 0, uuid.toString());
    }

    /**
     * 参加時に呼ぶ。DBに保存されたトグルON/OFF状態を読み込み、ONだった場合はインメモリ状態に復元する
     * （再ログイン・サーバー再起動を挟んでもONを維持するため）。
     */
    public void loadEnabled(Player player) {
        UUID uuid = player.getUniqueId();
        Integer enabled = DatabaseManager.queryOne(
                "SELECT mine_enabled FROM players WHERE uuid = ?",
                rs -> rs.getInt("mine_enabled"),
                uuid.toString()
        );
        if (enabled != null && enabled != 0) {
            enabledPlayers.add(uuid);
            lastMineMillis.put(uuid, System.currentTimeMillis());
        }
    }

    /** タイムアウト監視。mine.enabled が true の間、10秒毎に呼ばれる想定（KikoriManagerと同方式）。 */
    public void tick() {
        if (plugin.getConfigManager().getInt("mine.timeout-seconds", 300) == 0) { return; }
        long timeoutMillis = plugin.getConfigManager().getInt("mine.timeout-seconds", 300) * 1000L;
        long now = System.currentTimeMillis();

        for (UUID uuid : new HashSet<>(enabledPlayers)) {
            Long lastMine = lastMineMillis.get(uuid);
            if (lastMine == null || now - lastMine <= timeoutMillis) {
                continue;
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            setEnabled(player, false);
            player.sendMessage(plugin.getConfigManager().getMessage("mine.timeout_disabled", player));
        }
    }

    // ------------------------------------------------------------------
    // pass（人工物の一時無視）
    // ------------------------------------------------------------------

    public boolean hasPendingPass(UUID uuid) {
        return pendingPass.contains(uuid);
    }

    /** /mine pass から呼ぶ。passを予約し、まだOFFなら自動でONにする。 */
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
                "SELECT mine_unlocked FROM players WHERE uuid = ?",
                rs -> rs.getInt("mine_unlocked"),
                player.getUniqueId().toString()
        );
        return unlocked != null && unlocked != 0;
    }

    public enum PurchaseResult { SUCCESS, ALREADY_UNLOCKED, INSUFFICIENT_FUNDS, DATABASE_ERROR }

    public int getPrice() {
        return Math.max(0, plugin.getConfigManager().getInt("mine.price", 3000));
    }

    /** /mine buy から呼ぶ。mine.price をEconomyManagerから引き落とし、成功したらDBのフラグを立てる。 */
    public PurchaseResult purchase(Player player) {
        int price = getPrice();
        int affected = DatabaseManager.execute(
                "UPDATE players SET coins = coins - ?, mine_unlocked = 1 " +
                        "WHERE uuid = ? AND coins >= ? AND mine_unlocked = 0",
                price, player.getUniqueId().toString(), price);
        if (affected == 1) {
            plugin.getEconomyManager().invalidateBalance(player.getUniqueId());
            return PurchaseResult.SUCCESS;
        }
        if (affected < 0) {
            return PurchaseResult.DATABASE_ERROR;
        }
        return isUnlocked(player) ? PurchaseResult.ALREADY_UNLOCKED : PurchaseResult.INSUFFICIENT_FUNDS;
    }

    // ------------------------------------------------------------------
    // 人工物タグ（鉱石）: チャンクのPersistentDataContainerに座標を記録
    // ------------------------------------------------------------------

    /** チャンクPDCのタグで人工物（プレイヤーが設置した鉱石）かどうか判定する。 */
    public boolean isArtificialOre(Block block) {
        return readTaggedCoords(block.getChunk()).contains(packLocalCoord(block));
    }

    /** BlockPlaceEventから呼ぶ。鉱石が置かれた座標をチャンクPDCに追加する。 */
    public void markArtificialOre(Block block) {
        Chunk chunk = block.getChunk();
        Set<Long> coords = readTaggedCoords(chunk);
        if (coords.add(packLocalCoord(block))) {
            writeTaggedCoords(chunk, coords);
        }
    }

    /** BlockBreakEvent（原因を問わず鉱石が消える全ケース）から呼ぶ。タグを消す。 */
    public void unmarkArtificialOre(Block block) {
        Chunk chunk = block.getChunk();
        Set<Long> coords = readTaggedCoords(chunk);
        if (coords.remove(packLocalCoord(block))) {
            writeTaggedCoords(chunk, coords);
        }
    }

    private Set<Long> readTaggedCoords(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        long[] packed = pdc.get(artificialOresKey, PersistentDataType.LONG_ARRAY);
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
            pdc.remove(artificialOresKey);
            return;
        }
        long[] packed = new long[coords.size()];
        int i = 0;
        for (long value : coords) {
            packed[i++] = value;
        }
        pdc.set(artificialOresKey, PersistentDataType.LONG_ARRAY, packed);
    }

    /** チャンク内ローカル座標（X4bit・Z4bit・ワールド高さ9bit）を1つのlongにパックする。 */
    private long packLocalCoord(Block block) {
        long localX = block.getX() & 0xF;
        long localZ = block.getZ() & 0xF;
        long heightIndex = block.getY() + 64L; // -64〜319 -> 0〜383
        return (localX << 13) | (localZ << 9) | heightIndex;
    }

    // ------------------------------------------------------------------
    // 採掘ロジック
    // ------------------------------------------------------------------

    /**
     * ツルハシを持ってトグルON・天然鉱石を壊した時にBlockBreakEventから呼ぶ。
     * 起点と同じMaterialで26方向連結した鉱石をBFSで収集し、同一tick内で同期的に全て破壊する
     * （kikoriのようにtickをまたいで1個ずつ処理しない）。
     */
    public void tryStartMining(Player player, Block origin) {
        UUID uuid = player.getUniqueId();
        if (claimedBlocks.contains(origin)) {
            return; // 連鎖破壊で発生する内部BlockBreakEventの再入防止
        }

        Material targetType = origin.getType();
        int maxOres = plugin.getConfigManager().getInt("mine.max-ores", 128);
        boolean passAvailable = hasPendingPass(uuid);
        boolean passUsed = false;
        boolean aborted = false;

        Set<Block> visited = new HashSet<>();
        Set<Block> collectedOres = new LinkedHashSet<>();
        Deque<Block> frontier = new ArrayDeque<>();
        visited.add(origin);
        frontier.add(origin);

        while (!frontier.isEmpty() && collectedOres.size() < maxOres) {
            Block current = frontier.poll();
            if (!current.equals(origin) && isArtificialOre(current)) {
                if (passAvailable) {
                    passAvailable = false;
                    passUsed = true;
                } else {
                    aborted = true;
                    break;
                }
            }
            collectedOres.add(current);
            for (Block neighbor : neighbors26(current)) {
                if (neighbor.getType() == targetType && !claimedBlocks.contains(neighbor) && visited.add(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }

        lastMineMillis.put(uuid, System.currentTimeMillis());

        if (aborted) {
            setEnabled(player, false);
            String warning = plugin.getConfigManager().getMessage("mine.artificial_detected", player);
            player.sendMessage(warning);
            plugin.getActionBarManager().flash(player, "mine_warning", ColorUtil.component(warning), 60L);
            return;
        }

        if (passUsed) {
            pendingPass.remove(uuid);
        }

        Deque<Block> breakQueue = new ArrayDeque<>();
        for (Block ore : collectedOres) {
            if (!ore.equals(origin)) {
                breakQueue.add(ore);
            }
        }

        if (breakQueue.isEmpty()) {
            return; // 起点1個だけの鉱石（隣接無し） — バニラの単発破壊のみで完結
        }

        claimedBlocks.addAll(breakQueue);
        try {
            for (Block ore : breakQueue) {
                player.breakBlock(ore);
            }
        } finally {
            claimedBlocks.removeAll(breakQueue);
        }
    }

    /** 26方向（斜め含む）の隣接ブロックを返す。 */
    private List<Block> neighbors26(Block block) {
        List<Block> result = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    result.add(block.getRelative(dx, dy, dz));
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // ライフサイクル
    // ------------------------------------------------------------------

    /** 退出時に呼ぶ。トグル状態・pass予約を破棄する。 */
    public void removePlayer(UUID uuid) {
        enabledPlayers.remove(uuid);
        lastMineMillis.remove(uuid);
        pendingPass.remove(uuid);
    }
}
