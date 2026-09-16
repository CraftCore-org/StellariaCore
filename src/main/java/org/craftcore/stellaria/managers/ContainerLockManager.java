package org.craftcore.stellaria.managers;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 個別コンテナロックの永続化と、イベント用の座標キャッシュを管理する。 */
public class ContainerLockManager {

    public enum CreateResult { SUCCESS, ALREADY_LOCKED, CONFLICTING_LOCK, DATABASE_ERROR }
    public enum MemberResult { SUCCESS, OWNER, ALREADY_MEMBER, NOT_MEMBER, DATABASE_ERROR }
    public enum RemoveResult { SUCCESS, NOT_FOUND, DATABASE_ERROR }

    private final Map<ContainerLock.BlockKey, ContainerLock> locksByBlock = new ConcurrentHashMap<>();
    private final Map<UUID, ContainerLock> locksById = new ConcurrentHashMap<>();

    /** テスト用。DBをロードしない。 */
    ContainerLockManager() {
    }

    public ContainerLockManager(StellariaCore plugin) {
        loadFromDatabase();
    }

    private void loadFromDatabase() {
        Map<UUID, UUID> owners = new HashMap<>();
        for (LockRow row : DatabaseManager.query(
                "SELECT lock_id, owner_uuid FROM container_locks",
                rs -> new LockRow(UUID.fromString(rs.getString("lock_id")), UUID.fromString(rs.getString("owner_uuid"))))) {
            owners.put(row.lockId(), row.owner());
        }

        Map<UUID, Set<ContainerLock.BlockKey>> blocks = new HashMap<>();
        for (BlockRow row : DatabaseManager.query(
                "SELECT lock_id, world, x, y, z FROM container_lock_blocks",
                rs -> new BlockRow(UUID.fromString(rs.getString("lock_id")),
                        new ContainerLock.BlockKey(rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"))))) {
            blocks.computeIfAbsent(row.lockId(), ignored -> new LinkedHashSet<>()).add(row.key());
        }

        Map<UUID, Set<UUID>> members = new HashMap<>();
        for (MemberRow row : DatabaseManager.query(
                "SELECT lock_id, member_uuid FROM container_lock_members",
                rs -> new MemberRow(UUID.fromString(rs.getString("lock_id")), UUID.fromString(rs.getString("member_uuid"))))) {
            members.computeIfAbsent(row.lockId(), ignored -> new LinkedHashSet<>()).add(row.member());
        }

        for (Map.Entry<UUID, UUID> entry : owners.entrySet()) {
            Set<ContainerLock.BlockKey> lockBlocks = blocks.getOrDefault(entry.getKey(), Set.of());
            if (!lockBlocks.isEmpty()) {
                registerLoadedLock(new ContainerLock(entry.getKey(), entry.getValue(), lockBlocks,
                        members.getOrDefault(entry.getKey(), Set.of())));
            }
        }
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
        return find(block).map(lock -> lock.canAccess(player.getUniqueId(), isAdmin(player))).orElse(true);
    }

    public boolean canManage(Block block, Player player) {
        return find(block).map(lock -> lock.canManage(player.getUniqueId(), isAdmin(player))).orElse(true);
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
            execute(connection, "DELETE FROM container_lock_blocks WHERE lock_id = ?", lock.lockId().toString());
            execute(connection, "DELETE FROM container_lock_members WHERE lock_id = ?", lock.lockId().toString());
            execute(connection, "DELETE FROM container_locks WHERE lock_id = ?", lock.lockId().toString());
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
            execute(connection, "DELETE FROM container_lock_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?",
                    key.world(), key.x(), key.y(), key.z());
            if (lastBlock) {
                execute(connection, "DELETE FROM container_lock_members WHERE lock_id = ?", lock.lockId().toString());
                execute(connection, "DELETE FROM container_locks WHERE lock_id = ?", lock.lockId().toString());
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

    private static boolean isAdmin(Player player) {
        return player.hasPermission("stellaria.lock.admin");
    }

    private static void execute(Connection connection, String sql, Object... values) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            if (statement.executeUpdate() <= 0) throw new SQLException("更新対象がありません: " + sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private record LockRow(UUID lockId, UUID owner) {}
    private record BlockRow(UUID lockId, ContainerLock.BlockKey key) {}
    private record MemberRow(UUID lockId, UUID member) {}
}
