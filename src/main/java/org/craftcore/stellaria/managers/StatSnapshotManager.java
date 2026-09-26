package org.craftcore.stellaria.managers;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.StatDefinitions;
import org.craftcore.stellaria.utils.StatFileParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * バニラの統計を player_stat_snapshots に書き出し、/ranking 用に並べ替えて返す。
 * オンラインのプレイヤーは Bukkit API から読み（メインスレッド）、書き込みだけ非同期にする。
 * 導入前の記録は、起動時に stats/&lt;uuid&gt;.json を非同期で解析して取り込む。
 */
public class StatSnapshotManager {

    private final StellariaCore plugin;
    private List<String> enabledKeys = List.of();
    /** customIds から解決した Statistic。サーバーに存在しない ID は含まれない。 */
    private Map<String, Statistic> customStatistics;
    private Material[] minedMaterials;
    private Material[] placedMaterials;

    public StatSnapshotManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        enabledKeys = List.copyOf(StatDefinitions.filterConfigured(
                plugin.getConfigManager().getStringList("ranking.stats"),
                message -> plugin.getLogger().warning(message)));
    }

    public List<String> getEnabledKeys() {
        return enabledKeys;
    }

    // ------------------------------------------------------------------
    // 読み取り
    // ------------------------------------------------------------------

    /** オンラインのプレイヤーの全統計を読む。メインスレッドから呼ぶこと。 */
    public Map<String, Long> readLive(Player player) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (StatDefinitions.Definition def : StatDefinitions.all()) {
            values.put(def.key(), compute(player, def));
        }
        return values;
    }

    /** 1 種類だけ読む（/ranking の自分の順位用）。ブロック系以外は全ブロックの走査をしない。 */
    public long readLive(Player player, String statKey) {
        return StatDefinitions.find(statKey).map(def -> compute(player, def)).orElse(0L);
    }

    private long compute(Player player, StatDefinitions.Definition def) {
        long sum = 0L;
        switch (def.kind()) {
            case CUSTOM_SUM -> {
                Map<String, Statistic> statistics = customStatistics();
                for (String id : def.customIds()) {
                    Statistic statistic = statistics.get(id);
                    if (statistic != null) {
                        sum += player.getStatistic(statistic);
                    }
                }
            }
            case MINED_ALL -> {
                ensureMaterials(player);
                for (Material material : minedMaterials) {
                    sum += player.getStatistic(Statistic.MINE_BLOCK, material);
                }
            }
            case PLACED_BLOCKS -> {
                ensureMaterials(player);
                for (Material material : placedMaterials) {
                    sum += player.getStatistic(Statistic.USE_ITEM, material);
                }
            }
        }
        return sum;
    }

    /** customIds から Statistic への対応を初回だけ作る。サーバーに存在しない統計は警告して除外する。 */
    private Map<String, Statistic> customStatistics() {
        if (customStatistics == null) {
            Map<String, Statistic> resolved = new LinkedHashMap<>();
            for (StatDefinitions.Definition def : StatDefinitions.all()) {
                for (String id : def.customIds()) {
                    try {
                        resolved.put(id, Statistic.valueOf(StatDefinitions.bukkitName(id)));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("統計 " + id + " はこのサーバーに存在しないため、ランキングの集計から除外します。");
                    }
                }
            }
            customStatistics = resolved;
        }
        return customStatistics;
    }

    /**
     * 集計対象の Material を初回だけ作る。getStatistic が例外を投げる種類
     * （空気・レガシー・アイテム化できないブロックなど）はここで除外しておく。
     */
    private void ensureMaterials(Player probe) {
        if (minedMaterials != null) {
            return;
        }
        List<Material> mined = new ArrayList<>();
        List<Material> placed = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.isLegacy() || !material.isBlock() || material.isAir()) {
                continue;
            }
            if (accepts(probe, Statistic.MINE_BLOCK, material)) {
                mined.add(material);
            }
            if (material.isItem() && accepts(probe, Statistic.USE_ITEM, material)) {
                placed.add(material);
            }
        }
        placedMaterials = placed.toArray(Material[]::new);
        minedMaterials = mined.toArray(Material[]::new);
    }

    private static boolean accepts(Player probe, Statistic statistic, Material material) {
        try {
            probe.getStatistic(statistic, material);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 書き出し
    // ------------------------------------------------------------------

    public void snapshotAsync(Player player) {
        UUID uuid = player.getUniqueId();
        Map<String, Long> values = readLive(player);
        AdvancementManager advancements = plugin.getAdvancementManager();
        if (advancements != null) {
            advancements.onStatsRead(player, values);
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> write(uuid, values));
    }

    /** オンラインの全員を書き出す。1 tick に全員分の統計を読むと重いので、1 秒に分散させる。 */
    public void snapshotAllOnlineAsync() {
        int index = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().runDelayed(plugin, task -> snapshotAsync(player), null, spreadDelayTicks(index++));
        }
    }

    /** シャットダウン時用。DB 切断前に終わらせる必要があるため同期で書く。 */
    public void flushAllSync() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            write(player.getUniqueId(), readLive(player));
        }
    }

    private void write(UUID uuid, Map<String, Long> values) {
        writeValues(uuid, values, true);
    }

    /**
     * 1 人分の値をまとめて書き込む。1 行でも失敗したら全体を取り消して false を返す
     * （DatabaseManager.execute は SQLException を握りつぶして -1 を返すため、ここで例外に変えてロールバックさせる）。
     *
     * @param overwrite false なら既存の行を上書きしない（起動時の取り込みが、先に書かれた新しい値を古い値で潰さないため）
     */
    static boolean writeValues(UUID uuid, Map<String, Long> values, boolean overwrite) {
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO player_stat_snapshots (uuid, stat_key, value, updated_at) VALUES (?, ?, ?, ?) "
            + (overwrite
                ? "ON CONFLICT (uuid, stat_key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at"
                : "ON CONFLICT (uuid, stat_key) DO NOTHING");
        try {
            return DatabaseManager.transaction(conn -> {
                for (Map.Entry<String, Long> entry : values.entrySet()) {
                    if (DatabaseManager.execute(sql, uuid.toString(), entry.getKey(), entry.getValue(), now) < 0) {
                        throw new IllegalStateException("統計スナップショットの書き込みに失敗しました: " + entry.getKey());
                    }
                }
            });
        } catch (IllegalStateException e) {
            // サーバー停止で DB 接続が閉じた後に、非同期の書き込みが遅れて届いた場合。取りこぼしは次回の起動・ログアウトで埋まる。
            return false;
        }
    }

    /** 定期書き出しで、index 番目のプレイヤーを何 tick 後に処理するか。20 tick（1 秒）に分散させる。 */
    static long spreadDelayTicks(int index) {
        return 1L + index % 20;
    }

    /** ランキングの一覧に載る値（1 以上）が保存されているか。「あなたの順位」の母数に自分が含まれているかの判定に使う。 */
    public static boolean isListed(UUID uuid, String statKey) {
        return DatabaseManager.exists("player_stat_snapshots", "uuid = ? AND stat_key = ? AND value > 0", uuid.toString(), statKey);
    }

    // ------------------------------------------------------------------
    // 起動時の取り込み
    // ------------------------------------------------------------------

    /** スナップショットが 1 件もないプレイヤーの統計ファイルを非同期で取り込む。 */
    public void backfillAsync() {
        Path statsDir = Bukkit.getWorlds().get(0).getWorldFolder().toPath().resolve("stats");
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            List<String> targets = DatabaseManager.query(
                "SELECT uuid FROM players p WHERE NOT EXISTS "
                    + "(SELECT 1 FROM player_stat_snapshots s WHERE s.uuid = p.uuid)",
                rs -> rs.getString("uuid"));
            int imported = 0;
            for (String uuid : targets) {
                Path file = statsDir.resolve(uuid + ".json");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    if (writeValues(UUID.fromString(uuid), StatFileParser.parse(json, StatSnapshotManager::isBlockItem), false)) {
                        imported++;
                    }
                } catch (IOException | IllegalArgumentException e) {
                    plugin.getLogger().warning("統計ファイルを取り込めませんでした: " + file.getFileName() + " / " + e.getMessage());
                }
            }
            if (imported > 0) {
                plugin.getLogger().info("統計ランキング: " + imported + " 人分の過去の統計を取り込みました。");
            }
        });
    }

    private static boolean isBlockItem(String namespacedId) {
        Material material = Material.matchMaterial(namespacedId);
        return material != null && !material.isLegacy() && material.isBlock() && material.isItem();
    }

    // ------------------------------------------------------------------
    // ランキング
    // ------------------------------------------------------------------

    public record Entry(String name, long value) {
    }

    public List<Entry> getTop(String statKey, int limit, int offset) {
        return DatabaseManager.query(
            "SELECT p.name AS name, s.value AS value FROM player_stat_snapshots s "
                + "JOIN players p ON p.uuid = s.uuid "
                + "WHERE s.stat_key = ? AND p.hide_stats_ranking = 0 AND s.value > 0 "
                + "ORDER BY s.value DESC, p.name ASC LIMIT ? OFFSET ?",
            rs -> new Entry(rs.getString("name"), rs.getLong("value")),
            statKey, limit, offset);
    }

    public int getPublicCount(String statKey) {
        Integer count = DatabaseManager.queryOne(
            "SELECT COUNT(*) AS cnt FROM player_stat_snapshots s JOIN players p ON p.uuid = s.uuid "
                + "WHERE s.stat_key = ? AND p.hide_stats_ranking = 0 AND s.value > 0",
            rs -> rs.getInt("cnt"), statKey);
        return count != null ? count : 0;
    }

    public int countPublicAbove(String statKey, long value) {
        Integer count = DatabaseManager.queryOne(
            "SELECT COUNT(*) AS cnt FROM player_stat_snapshots s JOIN players p ON p.uuid = s.uuid "
                + "WHERE s.stat_key = ? AND p.hide_stats_ranking = 0 AND s.value > ?",
            rs -> rs.getInt("cnt"), statKey, value);
        return count != null ? count : 0;
    }

    // ------------------------------------------------------------------
    // 公開設定
    // ------------------------------------------------------------------

    public boolean isHidden(OfflinePlayer player) {
        if (player == null) {
            return false;
        }
        Integer hidden = DatabaseManager.queryOne(
            "SELECT hide_stats_ranking FROM players WHERE uuid = ?",
            rs -> rs.getInt("hide_stats_ranking"), player.getUniqueId().toString());
        return hidden != null && hidden != 0;
    }

    /** 連打時の順序逆転を避けるため同期で書き込み、成否を返す（EconomyManager#setHideBalance と同じ方針）。 */
    public boolean setHidden(Player player, boolean hidden) {
        int affected = DatabaseManager.update("players", Map.of("hide_stats_ranking", hidden ? 1 : 0),
            "uuid = ?", player.getUniqueId().toString());
        return affected > 0;
    }
}
