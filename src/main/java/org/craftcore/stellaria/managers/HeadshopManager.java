package org.craftcore.stellaria.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.HeadshopRotationUtil;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * headshop_pool（運営が登録した頭の一覧）と headshop_rotation（日替わりで選ばれた5件の履歴）を
 * DatabaseManager経由で読み書きする。日替わり抽選は自前の ScheduledTask で1分毎にチェックし、
 * headshop.reset-time を過ぎた最初のtickで当日分を生成する（AutoBroadcastManagerと同じ、
 * 自前でタスクを持ち start() で登録する形）。
 *
 * 「本日」は暦日ではなく reset-time を境にした「ショップの1日」（shopDate）で数える：
 * reset-time 前は前日分、reset-time 以降は当日分を指す。書き込み（generateRotation）と
 * 読み込み（getTodayHeads）の両方が同じ shopDate() を使うことで、reset-time 前に
 * 「今日の日付の行がまだ無い」状態で空表示になる不整合を防ぐ。
 */
public class HeadshopManager {

    private static final int ROTATION_SIZE = 5;
    private static final long TICK_INTERVAL_TICKS = 1200L; // 1分

    public record PoolHead(
            int id,
            String displayName,
            String texture,
            String itemData
    ) {
    }

    public record RecentPlayer(UUID uuid, String name) {
    }

    public enum RotationResetResult {
        SUCCESS,
        EMPTY_POOL,
        DATABASE_ERROR
    }

    private final StellariaCore plugin;
    private ScheduledTask task;
    private String emptyPoolWarnedDate;

    public HeadshopManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** 既存タスクがあれば止めてから、1分毎の日替わりチェックを開始する。起動直後に1回即時チェックも行う。 */
    public void start() {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> checkRotation(), TICK_INTERVAL_TICKS, TICK_INTERVAL_TICKS);
        checkRotation();
    }

    /**
     * reset-timeを境にした「ショップの1日」を返す。reset-time前は前日、reset-time以降は当日。
     * generateRotation()（書き込み）とgetTodayHeads()（読み込み）の両方がこれを使うことで、
     * キーのずれによる「reset-time前は空表示になる」不整合を防ぐ。
     */
    private LocalDate shopDate() {
        LocalDate today = LocalDate.now();
        return LocalTime.now().isBefore(resetTime()) ? today.minusDays(1) : today;
    }

    private void checkRotation() {
        String shopDate = shopDate().toString();
        if (DatabaseManager.exists("headshop_rotation", "date = ?", shopDate)) {
            return;
        }
        generateRotation(shopDate, listPool(), Set.of());
    }

    private LocalTime resetTime() {
        String raw = plugin.getConfigManager().getString("headshop.reset-time", "12:00");
        try {
            return LocalTime.parse(raw);
        } catch (DateTimeParseException e) {
            plugin.getLogger().warning("headshop.reset-time の形式が不正です(HH:mm形式で指定してください): " + raw);
            return LocalTime.NOON;
        }
    }

    private boolean generateRotation(String shopDate, List<PoolHead> pool, Set<Integer> currentIds) {
        if (pool.isEmpty()) {
            if (!shopDate.equals(emptyPoolWarnedDate)) {
                plugin.getLogger().warning("headshop_pool が空のため、本日の日替わりヘッドを生成できません。");
                emptyPoolWarnedDate = shopDate;
            }
            return false;
        }

        List<PoolHead> selected = HeadshopRotationUtil.select(
                pool, previousRotationIds(shopDate), currentIds, ROTATION_SIZE, new Random());
        for (PoolHead head : selected) {
            if (DatabaseManager.insert("headshop_rotation", Map.of("date", shopDate, "pool_id", head.id())) != 1) {
                DatabaseManager.execute("DELETE FROM headshop_rotation WHERE date = ?", shopDate);
                return false;
            }
        }
        return true;
    }

    /** 現在のショップ日のローテーションを消し、現在表示中のヘッドを避けて再抽選する。 */
    public RotationResetResult resetTodayRotation() {
        String today = shopDate().toString();
        List<PoolHead> pool = listPool();
        if (pool.isEmpty()) {
            return RotationResetResult.EMPTY_POOL;
        }

        Set<Integer> currentIds = rotationIds(today);
        if (DatabaseManager.execute("DELETE FROM headshop_rotation WHERE date = ?", today) < 0) {
            return RotationResetResult.DATABASE_ERROR;
        }
        return generateRotation(today, pool, currentIds)
                ? RotationResetResult.SUCCESS
                : RotationResetResult.DATABASE_ERROR;
    }

    private Set<Integer> previousRotationIds(String shopDate) {
        String previousDate = DatabaseManager.queryOne(
                "SELECT date FROM headshop_rotation WHERE date < ? ORDER BY date DESC LIMIT 1",
                rs -> rs.getString("date"),
                shopDate
        );
        return previousDate == null ? Set.of() : rotationIds(previousDate);
    }

    private Set<Integer> rotationIds(String shopDate) {
        return new HashSet<>(DatabaseManager.query(
                "SELECT pool_id FROM headshop_rotation WHERE date = ?",
                rs -> rs.getInt("pool_id"),
                shopDate
        ));
    }

    /** 本日（shopDate）のローテーションに選ばれた頭の一覧（headshop_rotationとheadshop_poolのJOIN）。 */
    public List<PoolHead> getTodayHeads() {
        return DatabaseManager.query(
                "SELECT headshop_pool.id AS id, "
                        + "headshop_pool.display_name AS display_name, "
                        + "headshop_pool.texture AS texture, "
                        + "headshop_pool.item_data AS item_data "
                        + "FROM headshop_rotation "
                        + "JOIN headshop_pool ON headshop_pool.id = headshop_rotation.pool_id "
                        + "WHERE headshop_rotation.date = ?",
                HeadshopManager::mapPoolHead,
                shopDate().toString()
        );
    }

    /** 登録済みの全ヘッド（id昇順）。管理者GUIの一覧表示用。 */
    public List<PoolHead> listPool() {
        return DatabaseManager.query(
                "SELECT id, display_name, texture, item_data "
                        + "FROM headshop_pool ORDER BY id",
                HeadshopManager::mapPoolHead
        );
    }

    public boolean textureExists(String texture) {
        return DatabaseManager.exists("headshop_pool", "texture = ?", texture);
    }

    public @Nullable PoolHead addToPool(
            ItemStack sourceItem,
            UUID addedBy
    ) {
        if (sourceItem == null
                || sourceItem.getType() != Material.PLAYER_HEAD
                || !(sourceItem.getItemMeta() instanceof SkullMeta skullMeta)) {
            return null;
        }

        String texture = extractTexture(skullMeta);
        if (texture == null) {
            return null;
        }

        ItemStack storedItem = sourceItem.clone();
        storedItem.setAmount(1);

        String displayName = skullMeta.hasDisplayName()
                ? PlainTextComponentSerializer.plainText()
                .serialize(skullMeta.displayName())
                : plugin.getConfigManager()
                .getMessage("headshop.admin.unnamed-head", null);

        String itemData = serializeItem(storedItem);

        OptionalInt id = DatabaseManager.insertAndGetId(
                "headshop_pool",
                Map.of(
                        "display_name", displayName,
                        "texture", texture,
                        "item_data", itemData,
                        "added_by", addedBy.toString(),
                        "added_at", System.currentTimeMillis()
                )
        );

        if (id.isEmpty()) {
            return null;
        }

        return new PoolHead(
                id.getAsInt(),
                displayName,
                texture,
                itemData
        );
    }

    /** プールから削除する。参照が残らないよう、このheadを含む過去のheadshop_rotation行も一緒に削除する。 */
    public void removeFromPool(int id) {
        DatabaseManager.execute("DELETE FROM headshop_rotation WHERE pool_id = ?", id);
        DatabaseManager.execute("DELETE FROM headshop_pool WHERE id = ?", id);
    }

    /**
     * 現在オンライン、または player_stats.last_logout が headshop.player-head-recent-days 以内の
     * プレイヤー一覧（オンライン優先、以降は名前順）。/headshop のプレイヤーヘッドGUI用。
     */
    public List<RecentPlayer> listRecentPlayers() {
        List<RecentPlayer> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (seen.add(online.getUniqueId())) {
                result.add(new RecentPlayer(online.getUniqueId(), online.getName()));
            }
        }

        int days = plugin.getConfigManager().getInt("headshop.player-head-recent-days", 30);
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        List<RecentPlayer> recentOffline = DatabaseManager.query(
                "SELECT players.uuid AS uuid, players.name AS name FROM player_stats " +
                        "JOIN players ON players.uuid = player_stats.uuid " +
                        "WHERE player_stats.last_logout >= ? ORDER BY players.name",
                rs -> new RecentPlayer(UUID.fromString(rs.getString("uuid")), rs.getString("name")),
                cutoff
        );
        for (RecentPlayer recent : recentOffline) {
            if (seen.add(recent.uuid())) {
                result.add(recent);
            }
        }
        return result;
    }

    /**
     * texture(base64) からPLAYER_HEADのItemStackを作る（プールの日替わりヘッド用、実プレイヤーの頭ではない）。
     * プロフィールUUIDはtextureから決定的に導出する（毎回ランダムだと同じ頭を複数買ってもスタックしないため）。
     */
    public ItemStack createHeadItem(PoolHead head) {
        if (head.itemData() != null && !head.itemData().isBlank()) {
            try {
                ItemStack item = deserializeItem(head.itemData());
                item.setAmount(1);
                return item;
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "headshop item_data の復元に失敗しました: id=" + head.id()
                );
            }
        }

        // 古いDBデータ用fallback
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();

        UUID profileId = UUID.nameUUIDFromBytes(
                head.texture().getBytes(StandardCharsets.UTF_8)
        );

        PlayerProfile profile = Bukkit.createProfile(profileId);
        profile.setProperty(
                new ProfileProperty("textures", head.texture())
        );

        meta.setPlayerProfile(profile);
        meta.displayName(
                org.craftcore.stellaria.utils.GuiItemUtil.text(
                        head.displayName()
                )
        );

        item.setItemMeta(meta);

        return item;
    }

    /** カーソルのアイテムのSkullMetaからtextureプロパティ(base64)を読み取る。見つからなければnull。 */
    public static String extractTexture(SkullMeta meta) {
        PlayerProfile profile = meta.getPlayerProfile();
        if (profile == null) {
            return null;
        }
        for (ProfileProperty property : profile.getProperties()) {
            if (property.getName().equals("textures")) {
                return property.getValue();
            }
        }
        return null;
    }

    private static String serializeItem(ItemStack item) {
        return Base64.getEncoder()
                .encodeToString(item.serializeAsBytes());
    }

    private static ItemStack deserializeItem(String data) {
        return ItemStack.deserializeBytes(
                Base64.getDecoder().decode(data)
        );
    }

    private static PoolHead mapPoolHead(ResultSet rs) throws SQLException {
        return new PoolHead(
                rs.getInt("id"),
                rs.getString("display_name"),
                rs.getString("texture"),
                rs.getString("item_data")
        );
    }
}
