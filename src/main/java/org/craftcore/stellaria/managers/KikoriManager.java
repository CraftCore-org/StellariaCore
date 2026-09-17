package org.craftcore.stellaria.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.TreeUtil;

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
    private final Map<UUID, List<ScheduledTask>> activeFellTasks = new HashMap<>();
    private final Set<Block> claimedBlocks = new HashSet<>();
    private final Set<UUID> suppressLeafDurability = new HashSet<>();

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

    public int getPrice() {
        return plugin.getConfigManager().getInt("kikori.price", 50000);
    }

    /** /kikori buy から呼ぶ。kikori.price をEconomyManagerから引き落とし、成功したらDBのフラグを立てる。 */
    public PurchaseResult purchase(Player player) {
        if (isUnlocked(player)) {
            return PurchaseResult.ALREADY_UNLOCKED;
        }
        int price = getPrice();
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
    // 伐採ロジック
    // ------------------------------------------------------------------

    /** 斧を持ってトグルON・天然丸太を壊した時にBlockBreakEventから呼ぶ。 */
    public void tryStartFelling(Player player, Block origin) {
        UUID uuid = player.getUniqueId();
        if (claimedBlocks.contains(origin)) {
            return; // 既に別の伐採タスクが処理中のブロック（連鎖破壊で発生する内部BlockBreakEventの再入や、
                    // 複数の木を同時に伐採している時の重複も含む）
        }

        int maxLogs = plugin.getConfigManager().getInt("kikori.max-logs", 256);
        boolean passAvailable = hasPendingPass(uuid);
        boolean passUsed = false;
        boolean aborted = false;

        Set<Block> visited = new HashSet<>();
        Set<Block> collectedLogs = new LinkedHashSet<>();
        Deque<Block> frontier = new ArrayDeque<>();
        visited.add(origin);
        frontier.add(origin);

        while (!frontier.isEmpty() && collectedLogs.size() < maxLogs) {
            Block current = frontier.poll();
            if (!current.equals(origin) && isArtificialLog(current)) {
                if (passAvailable) {
                    passAvailable = false;
                    passUsed = true;
                } else {
                    aborted = true;
                    break;
                }
            }
            collectedLogs.add(current);
            for (Block neighbor : neighbors26(current)) {
                if (TreeUtil.isLog(neighbor.getType()) && !claimedBlocks.contains(neighbor) && visited.add(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }

        lastFellMillis.put(uuid, System.currentTimeMillis());

        if (aborted) {
            setEnabled(player, false);
            String warning = plugin.getConfigManager().getMessage("kikori.artificial_detected", player);
            player.sendMessage(warning);
            plugin.getActionBarManager().flash(player, "kikori_warning", ColorUtil.component(warning), 60L);
            return;
        }

        if (passUsed) {
            pendingPass.remove(uuid);
        }

        int leafRadius = plugin.getConfigManager().getInt("kikori.leaf-radius", 3);
        Set<Block> leaves = collectLeaves(collectedLogs, leafRadius);

        Deque<Block> breakQueue = new ArrayDeque<>();
        for (Block log : collectedLogs) {
            if (!log.equals(origin)) {
                breakQueue.add(log);
            }
        }
        breakQueue.addAll(leaves);

        if (breakQueue.isEmpty()) {
            return; // 起点1本だけの木（隣接丸太も葉も無し） — バニラの単発破壊のみで完結
        }

        claimedBlocks.addAll(breakQueue);
        startFellTask(player, breakQueue);
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

    /** 収集した丸太それぞれの半径radiusブロック球内にある、天然（persistent=falseの）葉っぱを集める。 */
    private Set<Block> collectLeaves(Set<Block> logs, int radius) {
        Set<Block> leaves = new LinkedHashSet<>();
        int radiusSquared = radius * radius;
        for (Block log : logs) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                            continue;
                        }
                        Block candidate = log.getRelative(dx, dy, dz);
                        if (!TreeUtil.isLeaves(candidate.getType())) {
                            continue;
                        }
                        if (!(candidate.getBlockData() instanceof Leaves leafData) || leafData.isPersistent()) {
                            continue;
                        }
                        leaves.add(candidate);
                    }
                }
            }
        }
        return leaves;
    }

    /** PlayerItemDamageEventから呼ぶ。葉っぱの伐採中は耐久値ダメージをキャンセルするためのフラグ。 */
    public boolean isSuppressingLeafDurability(UUID uuid) {
        return suppressLeafDurability.contains(uuid);
    }

    /**
     * 破壊キューを1tickに1ブロックずつ処理する。プレイヤーのエンティティスケジューラを使うので、
     * 切断時は自動的にタスクが終了する（TpaCoreのカウントダウンと同方式）。
     * 1プレイヤーが同時に複数の木を伐採できるよう、タスクはプレイヤーごとに複数並行して走れる
     * （個々のブロックはclaimedBlocksで排他制御しているので、同じブロックが二重に処理されることはない）。
     */
    private void startFellTask(Player player, Deque<Block> breakQueue) {
        UUID uuid = player.getUniqueId();
        ScheduledTask[] taskRef = new ScheduledTask[1];
        taskRef[0] = player.getScheduler().runAtFixedRate(plugin, scheduledTask -> {
            Block block = breakQueue.poll();
            if (block != null) {
                Player current = Bukkit.getPlayer(uuid);
                boolean isLeaf = TreeUtil.isLeaves(block.getType());
                if (current != null && (TreeUtil.isLog(block.getType()) || isLeaf)) {
                    if (isLeaf) {
                        suppressLeafDurability.add(uuid);
                    }
                    current.breakBlock(block);
                    if (isLeaf) {
                        suppressLeafDurability.remove(uuid);
                    }
                }
                claimedBlocks.remove(block); // 壊し終わってから解放（壊してる最中の内部BlockBreakEvent再入を防ぐため）
            }
            if (breakQueue.isEmpty()) {
                scheduledTask.cancel();
                removeActiveTask(uuid, taskRef[0]);
            }
        }, () -> {
            breakQueue.forEach(claimedBlocks::remove); // 切断等で途中終了した分の解放
            removeActiveTask(uuid, taskRef[0]);
        }, 1L, 1L);
        addActiveTask(uuid, taskRef[0]);
    }

    private void addActiveTask(UUID uuid, ScheduledTask task) {
        activeFellTasks.computeIfAbsent(uuid, key -> new ArrayList<>()).add(task);
    }

    private void removeActiveTask(UUID uuid, ScheduledTask task) {
        List<ScheduledTask> tasks = activeFellTasks.get(uuid);
        if (tasks == null) {
            return;
        }
        tasks.remove(task);
        if (tasks.isEmpty()) {
            activeFellTasks.remove(uuid);
        }
    }

    // ------------------------------------------------------------------
    // ライフサイクル
    // ------------------------------------------------------------------

    /** 退出時に呼ぶ。トグル状態・pass予約を破棄し、進行中の伐採タスクがあれば全てキャンセルする。 */
    public void removePlayer(UUID uuid) {
        enabledPlayers.remove(uuid);
        lastFellMillis.remove(uuid);
        pendingPass.remove(uuid);
        suppressLeafDurability.remove(uuid);
        List<ScheduledTask> tasks = activeFellTasks.remove(uuid);
        if (tasks != null) {
            for (ScheduledTask task : tasks) {
                task.cancel();
            }
        }
    }
}
