package org.craftcore.stellaria.managers;

import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.craftcore.stellaria.StellariaCore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** 個別コンテナロックの永続化と、イベント用の座標キャッシュを管理する。 */
public class ContainerLockManager {

    public enum CreateResult { SUCCESS, ALREADY_LOCKED, CONFLICTING_LOCK, DATABASE_ERROR }
    public enum MemberResult { SUCCESS, OWNER, ALREADY_MEMBER, NOT_MEMBER, DATABASE_ERROR }
    public enum RemoveResult { SUCCESS, NOT_FOUND, DATABASE_ERROR }
    public enum AutoLockToggleResult { ENABLED, DISABLED, DATABASE_ERROR }

    private final Map<ContainerLock.BlockKey, ContainerLock> locksByBlock = new ConcurrentHashMap<>();
    private final Map<UUID, ContainerLock> locksById = new ConcurrentHashMap<>();
    private final Set<UUID> bypassEnabled = ConcurrentHashMap.newKeySet();
    private final Set<UUID> autoLockEnabled = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingWorlds = ConcurrentHashMap.newKeySet();
    private final StellariaCore plugin;
    private final boolean databaseBacked;

    /** テスト用。DBをロードしない。 */
    ContainerLockManager() {
        plugin = null;
        databaseBacked = false;
    }

    public ContainerLockManager(StellariaCore plugin) {
        this.plugin = plugin;
        databaseBacked = true;
        loadFromDatabase();
    }

    private boolean loadFromDatabase() {
        locksByBlock.clear();
        locksById.clear();
        Map<UUID, UUID> owners = new HashMap<>();
        for (LockRow row : DatabaseManager.query(
                "SELECT lock_id, owner_uuid FROM container_locks",
                rs -> new LockRow(UUID.fromString(rs.getString("lock_id")), UUID.fromString(rs.getString("owner_uuid"))))) {
            owners.put(row.lockId(), row.owner());
        }

        List<BlockRow> blockRows = DatabaseManager.query(
                "SELECT lock_id, world, x, y, z FROM container_lock_blocks",
                rs -> new BlockRow(UUID.fromString(rs.getString("lock_id")),
                        new ContainerLock.BlockKey(rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"))));

        Map<UUID, Set<UUID>> members = new HashMap<>();
        for (MemberRow row : DatabaseManager.query(
                "SELECT lock_id, member_uuid FROM container_lock_members",
                rs -> new MemberRow(UUID.fromString(rs.getString("lock_id")), UUID.fromString(rs.getString("member_uuid"))))) {
            members.computeIfAbsent(row.lockId(), ignored -> new LinkedHashSet<>()).add(row.member());
        }

        Set<BlockRow> discardedRows = new LinkedHashSet<>();
        Map<ContainerLock.BlockKey, UUID> loadedBlockOwners = new HashMap<>();
        for (BlockRow row : blockRows) {
            if (!owners.containsKey(row.lockId())) {
                discardedRows.add(row);
                continue;
            }
            World world = Bukkit.getWorld(row.key().world());
            if (world == null) {
                pendingWorlds.add(row.key().world());
                continue;
            }
            if (!isLockable(world, row.key())) {
                discardedRows.add(row);
                continue;
            }
            loadedBlockOwners.put(row.key(), row.lockId());
        }

        Set<ContainerLock.BlockKey> conflictingKeys = new LinkedHashSet<>();
        Set<String> loggedConflicts = new HashSet<>();
        for (BlockRow row : blockRows) {
            if (discardedRows.contains(row) || !loadedBlockOwners.containsKey(row.key())) continue;
            World world = Bukkit.getWorld(row.key().world());
            if (world == null) continue;
            Set<ContainerLock.BlockKey> doubleChestKeys = doubleChestKeys(world, row.key());
            for (ContainerLock.BlockKey partner : doubleChestKeys) {
                UUID partnerLockId = loadedBlockOwners.get(partner);
                if (partnerLockId == null || partnerLockId.equals(row.lockId())) continue;
                conflictingKeys.add(row.key());
                conflictingKeys.add(partner);
                String conflict = row.key().toString().compareTo(partner.toString()) < 0
                        ? row.key() + " / " + partner
                        : partner + " / " + row.key();
                if (loggedConflicts.add(conflict)) {
                    warn("異なるロックIDに分割された二連チェストを検出したため、両側のロック座標を削除します: " + conflict);
                }
            }
        }
        for (BlockRow row : blockRows) {
            if (conflictingKeys.contains(row.key())) discardedRows.add(row);
        }

        Map<UUID, Set<ContainerLock.BlockKey>> reconciledBlocks = new HashMap<>();
        for (BlockRow row : blockRows) {
            if (!discardedRows.contains(row)) {
                reconciledBlocks.computeIfAbsent(row.lockId(), ignored -> new LinkedHashSet<>()).add(row.key());
            }
        }
        Set<UUID> emptyLockIds = new LinkedHashSet<>(owners.keySet());
        emptyLockIds.removeAll(reconciledBlocks.keySet());
        boolean reconciled = reconcileDatabase(discardedRows, emptyLockIds);
        Map<UUID, Set<ContainerLock.BlockKey>> blocksToCache = new HashMap<>();
        for (BlockRow row : blockRows) {
            if (discardedRows.contains(row) || Bukkit.getWorld(row.key().world()) == null) continue;
            blocksToCache.computeIfAbsent(row.lockId(), ignored -> new LinkedHashSet<>()).add(row.key());
        }

        for (Map.Entry<UUID, UUID> entry : owners.entrySet()) {
            Set<ContainerLock.BlockKey> lockBlocks = blocksToCache.getOrDefault(entry.getKey(), Set.of());
            if (!lockBlocks.isEmpty()) {
                registerLoadedLock(new ContainerLock(entry.getKey(), entry.getValue(), lockBlocks,
                        members.getOrDefault(entry.getKey(), Set.of())));
            }
        }

        for (UUID playerId : DatabaseManager.query(
                "SELECT player_uuid FROM container_lock_auto_players",
                rs -> UUID.fromString(rs.getString("player_uuid")))) {
            autoLockEnabled.add(playerId);
        }
        for (String worldName : pendingWorlds) {
            warn("コンテナロックのワールド '" + worldName + "' は未ロードのため、ワールドロード時に整合性を確認します。");
        }
        return reconciled;
    }

    /** ワールドロード後に、保留中の座標を検証してからキャッシュへ反映する。 */
    public void reconcileWorld(World world) {
        if (world == null) return;
        if (loadFromDatabase()) pendingWorlds.remove(world.getName());
    }

    boolean isWorldPending(String worldName) {
        return pendingWorlds.contains(worldName);
    }

    private boolean isLockable(World world, ContainerLock.BlockKey key) {
        try {
            return isLockable(world.getBlockAt(key.x(), key.y(), key.z()).getType());
        } catch (RuntimeException exception) {
            warn("ロック座標の検証に失敗したため、無効な座標として扱います: " + key + " / " + exception.getMessage());
            return false;
        }
    }

    private Set<ContainerLock.BlockKey> doubleChestKeys(World world, ContainerLock.BlockKey key) {
        Block block = world.getBlockAt(key.x(), key.y(), key.z());
        if (!(block.getState() instanceof Chest chest)) return Set.of();
        InventoryHolder holder = chest.getInventory().getHolder();
        if (!(holder instanceof DoubleChest doubleChest)) return Set.of();
        Set<ContainerLock.BlockKey> keys = new LinkedHashSet<>();
        addChestKey(keys, doubleChest.getLeftSide());
        addChestKey(keys, doubleChest.getRightSide());
        return keys;
    }

    private static void addChestKey(Set<ContainerLock.BlockKey> keys, InventoryHolder holder) {
        if (holder instanceof Chest chest) keys.add(ContainerLock.BlockKey.of(chest.getBlock()));
    }

    private boolean reconcileDatabase(Set<BlockRow> discardedRows, Set<UUID> emptyLockIds) {
        if (discardedRows.isEmpty() && emptyLockIds.isEmpty()) return true;
        boolean persisted = DatabaseManager.transaction(connection -> {
            for (BlockRow row : discardedRows) {
                executeDelete(connection,
                        "DELETE FROM container_lock_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?",
                        row.key().world(), row.key().x(), row.key().y(), row.key().z());
            }
            for (UUID lockId : emptyLockIds) {
                executeDelete(connection, "DELETE FROM container_lock_members WHERE lock_id = ?", lockId.toString());
                executeDelete(connection, "DELETE FROM container_locks WHERE lock_id = ?", lockId.toString());
            }
        });
        if (!persisted) {
            warn("コンテナロックの起動時整合性修復に失敗したため、キャッシュを修正せずロードします。");
        }
        return persisted;
    }

    public Optional<ContainerLock> find(Block block) {
        return find(ContainerLock.BlockKey.of(block));
    }

    public Optional<ContainerLock> find(ContainerLock.BlockKey key) {
        return Optional.ofNullable(locksByBlock.get(key));
    }

    Optional<ContainerLock> findCached(ContainerLock.BlockKey key) {
        return find(key);
    }

    Optional<ContainerLock> findCached(UUID lockId) {
        return Optional.ofNullable(locksById.get(lockId));
    }

    public boolean isLocked(Block block) {
        return locksByBlock.containsKey(ContainerLock.BlockKey.of(block));
    }

    public static boolean isLockable(Material material) {
        return material == Material.CHEST
                || material == Material.TRAPPED_CHEST
                || material == Material.BARREL
                || material.name().endsWith("SHULKER_BOX");
    }

    public boolean canAccess(Block block, Player player) {
        return find(block).map(lock -> lock.canAccess(player.getUniqueId(), isBypassing(player))).orElse(true);
    }

    public boolean canManage(Block block, Player player) {
        return find(block).map(lock -> lock.canManage(player.getUniqueId(), isBypassing(player))).orElse(true);
    }

    public CreateResult create(Player owner, Set<ContainerLock.BlockKey> keys) {
        if (keys.isEmpty()) {
            return CreateResult.DATABASE_ERROR;
        }
        for (ContainerLock.BlockKey key : keys) {
            if (locksByBlock.containsKey(key)) {
                return CreateResult.ALREADY_LOCKED;
            }
        }
        ContainerLock lock = new ContainerLock(UUID.randomUUID(), owner.getUniqueId(), keys, Set.of());
        if (!insertLock(lock)) {
            return CreateResult.DATABASE_ERROR;
        }
        registerLoadedLock(lock);
        return CreateResult.SUCCESS;
    }

    public CreateResult attachBlock(ContainerLock lock, ContainerLock.BlockKey key) {
        ContainerLock existing = locksByBlock.get(key);
        if (existing != null) {
            return existing.lockId().equals(lock.lockId()) ? CreateResult.SUCCESS : CreateResult.CONFLICTING_LOCK;
        }
        boolean persisted = DatabaseManager.transaction(connection -> execute(connection,
                "INSERT INTO container_lock_blocks (world, x, y, z, lock_id) VALUES (?, ?, ?, ?, ?)",
                key.world(), key.x(), key.y(), key.z(), lock.lockId().toString()));
        if (!persisted) {
            return CreateResult.DATABASE_ERROR;
        }
        lock.addBlock(key);
        locksByBlock.put(key, lock);
        return CreateResult.SUCCESS;
    }

    public MemberResult trust(ContainerLock lock, UUID member) {
        if (lock.owner().equals(member)) return MemberResult.OWNER;
        if (lock.members().contains(member)) return MemberResult.ALREADY_MEMBER;
        boolean persisted = DatabaseManager.transaction(connection -> execute(connection,
                "INSERT INTO container_lock_members (lock_id, member_uuid) VALUES (?, ?)",
                lock.lockId().toString(), member.toString()));
        if (!persisted) return MemberResult.DATABASE_ERROR;
        lock.addMember(member);
        return MemberResult.SUCCESS;
    }

    public MemberResult untrust(ContainerLock lock, UUID member) {
        if (!lock.members().contains(member)) return MemberResult.NOT_MEMBER;
        boolean persisted = DatabaseManager.transaction(connection -> execute(connection,
                "DELETE FROM container_lock_members WHERE lock_id = ? AND member_uuid = ?",
                lock.lockId().toString(), member.toString()));
        if (!persisted) return MemberResult.DATABASE_ERROR;
        lock.removeMember(member);
        return MemberResult.SUCCESS;
    }

    public RemoveResult unlock(ContainerLock lock) {
        if (!locksById.containsKey(lock.lockId())) return RemoveResult.NOT_FOUND;
        boolean persisted = DatabaseManager.transaction(connection -> {
            executeDelete(connection, "DELETE FROM container_lock_blocks WHERE lock_id = ?", lock.lockId().toString());
            executeDelete(connection, "DELETE FROM container_lock_members WHERE lock_id = ?", lock.lockId().toString());
            executeDelete(connection, "DELETE FROM container_locks WHERE lock_id = ?", lock.lockId().toString());
        });
        if (!persisted) return RemoveResult.DATABASE_ERROR;
        removeLockFromCache(lock);
        return RemoveResult.SUCCESS;
    }

    public RemoveResult removeDestroyedBlock(ContainerLock.BlockKey key) {
        ContainerLock lock = locksByBlock.get(key);
        if (lock == null) return RemoveResult.NOT_FOUND;
        boolean lastBlock = lock.blocks().size() == 1;
        boolean persisted = DatabaseManager.transaction(connection -> {
            executeDelete(connection, "DELETE FROM container_lock_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?",
                    key.world(), key.x(), key.y(), key.z());
            if (lastBlock) {
                executeDelete(connection, "DELETE FROM container_lock_members WHERE lock_id = ?", lock.lockId().toString());
                executeDelete(connection, "DELETE FROM container_locks WHERE lock_id = ?", lock.lockId().toString());
            }
        });
        if (!persisted) return RemoveResult.DATABASE_ERROR;
        detachFromCache(key);
        return RemoveResult.SUCCESS;
    }

    void registerLoadedLock(ContainerLock lock) {
        locksById.put(lock.lockId(), lock);
        for (ContainerLock.BlockKey key : lock.blocks()) {
            locksByBlock.put(key, lock);
        }
    }

    boolean detachFromCache(ContainerLock.BlockKey key) {
        ContainerLock lock = locksByBlock.remove(key);
        if (lock == null) return false;
        lock.removeBlock(key);
        if (lock.isEmpty()) locksById.remove(lock.lockId());
        return true;
    }

    private boolean insertLock(ContainerLock lock) {
        return DatabaseManager.transaction(connection -> {
            execute(connection, "INSERT INTO container_locks (lock_id, owner_uuid, created_at) VALUES (?, ?, ?)",
                    lock.lockId().toString(), lock.owner().toString(), System.currentTimeMillis());
            for (ContainerLock.BlockKey key : lock.blocks()) {
                execute(connection, "INSERT INTO container_lock_blocks (world, x, y, z, lock_id) VALUES (?, ?, ?, ?, ?)",
                        key.world(), key.x(), key.y(), key.z(), lock.lockId().toString());
            }
        });
    }

    private void removeLockFromCache(ContainerLock lock) {
        locksById.remove(lock.lockId());
        for (ContainerLock.BlockKey key : new ArrayList<>(lock.blocks())) {
            locksByBlock.remove(key);
            lock.removeBlock(key);
        }
    }

    void removeWorldFromCache(String worldName) {
        for (ContainerLock.BlockKey key : new ArrayList<>(locksByBlock.keySet())) {
            if (key.world().equals(worldName)) {
                detachFromCache(key);
            }
        }
    }

    /** ワールド再生成後に、そのワールドに紐づく座標と空ロックを一括削除する。 */
    public boolean removeWorld(String worldName) {
        Set<UUID> emptyLockIds = new LinkedHashSet<>();
        boolean persisted = DatabaseManager.transaction(connection -> {
            executeDelete(connection, "DELETE FROM container_lock_blocks WHERE world = ?", worldName);
            emptyLockIds.addAll(emptyLockIds(connection));
            for (UUID lockId : emptyLockIds) {
                executeDelete(connection, "DELETE FROM container_lock_members WHERE lock_id = ?", lockId.toString());
                executeDelete(connection, "DELETE FROM container_locks WHERE lock_id = ?", lockId.toString());
            }
        });
        if (!persisted) {
            warn("ワールド '" + worldName + "' のコンテナロック削除に失敗しました。");
            return false;
        }
        removeWorldFromCache(worldName);
        for (UUID lockId : emptyLockIds) {
            ContainerLock lock = locksById.get(lockId);
            if (lock != null && lock.isEmpty()) locksById.remove(lockId, lock);
        }
        return true;
    }

    private static Set<UUID> emptyLockIds(Connection connection) {
        Set<UUID> ids = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT lock_id FROM container_locks WHERE NOT EXISTS "
                        + "(SELECT 1 FROM container_lock_blocks WHERE container_lock_blocks.lock_id = container_locks.lock_id)")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) ids.add(UUID.fromString(resultSet.getString("lock_id")));
            }
            return ids;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public boolean hasBypassEnabled(UUID id) { return bypassEnabled.contains(id); }
    public boolean toggleBypass(UUID id) { return bypassEnabled.remove(id) ? false : bypassEnabled.add(id); }
    public void removeBypassState(UUID id) { bypassEnabled.remove(id); }
    public boolean isBypassing(Player player) { return player.hasPermission("stellaria.lock.admin") && hasBypassEnabled(player.getUniqueId()); }

    public boolean hasAutoLockEnabled(UUID id) {
        return autoLockEnabled.contains(id);
    }

    /** 自動ロックを切り替え、DBへの反映に失敗した場合は現在の状態を維持する。 */
    public AutoLockToggleResult toggleAutoLock(UUID id) {
        boolean currentlyEnabled = autoLockEnabled.contains(id);
        if (databaseBacked) {
            boolean persisted = currentlyEnabled
                    ? DatabaseManager.transaction(connection -> execute(connection,
                    "DELETE FROM container_lock_auto_players WHERE player_uuid = ?", id.toString()))
                    : DatabaseManager.transaction(connection -> execute(connection,
                    "INSERT INTO container_lock_auto_players (player_uuid) VALUES (?)", id.toString()));
            if (!persisted) {
                return AutoLockToggleResult.DATABASE_ERROR;
            }
        }
        if (currentlyEnabled) {
            autoLockEnabled.remove(id);
            return AutoLockToggleResult.DISABLED;
        }
        autoLockEnabled.add(id);
        return AutoLockToggleResult.ENABLED;
    }

    private static void execute(Connection connection, String sql, Object... values) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            if (statement.executeUpdate() <= 0) throw new SQLException("更新対象がありません: " + sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void executeDelete(Connection connection, String sql, Object... values) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void warn(String message) {
        if (plugin != null) plugin.getLogger().warning(message);
        else Logger.getLogger(ContainerLockManager.class.getName()).warning(message);
    }

    private record LockRow(UUID lockId, UUID owner) {}
    private record BlockRow(UUID lockId, ContainerLock.BlockKey key) {}
    private record MemberRow(UUID lockId, UUID member) {}
}
