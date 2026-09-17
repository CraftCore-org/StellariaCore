package org.craftcore.stellaria.managers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerLockManagerTest {

    private Connection connection;

    @BeforeEach
    void setUpDatabase() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE container_locks (lock_id TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, created_at INTEGER NOT NULL)");
            statement.execute("CREATE TABLE container_lock_blocks (world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, lock_id TEXT NOT NULL, PRIMARY KEY (world, x, y, z))");
            statement.execute("CREATE TABLE container_lock_members (lock_id TEXT NOT NULL, member_uuid TEXT NOT NULL, PRIMARY KEY (lock_id, member_uuid))");
        }
        databaseConnectionField().set(null, connection);
    }

    @AfterEach
    void tearDownDatabase() throws Exception {
        databaseConnectionField().set(null, null);
        connection.close();
    }

    @Test
    void doubleChestKeysResolveToTheSameCachedLock() {
        ContainerLockManager manager = new ContainerLockManager();
        ContainerLock.BlockKey left = key(0);
        ContainerLock.BlockKey right = key(1);
        ContainerLock lock = lock(left, right);

        manager.registerLoadedLock(lock);

        assertSame(manager.findCached(left).orElseThrow(), manager.findCached(right).orElseThrow());
    }

    @Test
    void detachingOneDoubleChestHalfKeepsTheRemainingHalfLocked() {
        ContainerLockManager manager = new ContainerLockManager();
        ContainerLock.BlockKey left = key(0);
        ContainerLock.BlockKey right = key(1);
        manager.registerLoadedLock(lock(left, right));

        assertTrue(manager.detachFromCache(left));
        assertTrue(manager.findCached(left).isEmpty());
        assertTrue(manager.findCached(right).isPresent());
    }

    @Test
    void detachingTheLastBlockRemovesTheLockFromBothIndexes() {
        ContainerLockManager manager = new ContainerLockManager();
        ContainerLock.BlockKey only = key(0);
        ContainerLock lock = lock(only);
        manager.registerLoadedLock(lock);

        assertTrue(manager.detachFromCache(only));
        assertTrue(manager.findCached(only).isEmpty());
        assertTrue(manager.findCached(lock.lockId()).isEmpty());
    }

    @Test
    void bypassIsDisabledByDefaultAndTogglesPerPlayer() {
        ContainerLockManager manager = new ContainerLockManager();
        UUID admin = UUID.randomUUID();

        assertTrue(!manager.hasBypassEnabled(admin));
        assertTrue(manager.toggleBypass(admin));
        assertTrue(manager.hasBypassEnabled(admin));
        assertTrue(!manager.toggleBypass(admin));
        assertTrue(!manager.hasBypassEnabled(admin));
    }

    @Test
    void removingBypassStateDisablesAnEnabledBypass() {
        ContainerLockManager manager = new ContainerLockManager();
        UUID admin = UUID.randomUUID();
        manager.toggleBypass(admin);

        manager.removeBypassState(admin);

        assertFalse(manager.hasBypassEnabled(admin));
    }

    @Test
    void autoLockIsDisabledByDefaultAndTogglesPerPlayer() {
        ContainerLockManager manager = new ContainerLockManager();
        UUID player = UUID.randomUUID();

        assertFalse(manager.hasAutoLockEnabled(player));
        assertEquals(ContainerLockManager.AutoLockToggleResult.ENABLED, manager.toggleAutoLock(player));
        assertTrue(manager.hasAutoLockEnabled(player));
        assertEquals(ContainerLockManager.AutoLockToggleResult.DISABLED, manager.toggleAutoLock(player));
        assertFalse(manager.hasAutoLockEnabled(player));
    }

    @Test
    void removingTheLastMemberlessLockBlockSucceedsAndClearsBothIndexes() throws Exception {
        ContainerLockManager manager = new ContainerLockManager();
        ContainerLock.BlockKey only = key(0);
        ContainerLock lock = lock(only);
        manager.registerLoadedLock(lock);
        insert(lock, only);

        assertEquals(ContainerLockManager.RemoveResult.SUCCESS, manager.removeDestroyedBlock(only));
        assertTrue(manager.findCached(only).isEmpty());
        assertTrue(manager.findCached(lock.lockId()).isEmpty());
    }

    private static ContainerLock lock(ContainerLock.BlockKey... blocks) {
        return new ContainerLock(UUID.randomUUID(), UUID.randomUUID(), Set.of(blocks), Set.of());
    }

    private static ContainerLock.BlockKey key(int x) {
        return new ContainerLock.BlockKey("world", x, 64, 0);
    }

    private void insert(ContainerLock lock, ContainerLock.BlockKey key) throws Exception {
        try (var lockStatement = connection.prepareStatement("INSERT INTO container_locks (lock_id, owner_uuid, created_at) VALUES (?, ?, ?)");
             var blockStatement = connection.prepareStatement("INSERT INTO container_lock_blocks (world, x, y, z, lock_id) VALUES (?, ?, ?, ?, ?)")) {
            lockStatement.setString(1, lock.lockId().toString());
            lockStatement.setString(2, lock.owner().toString());
            lockStatement.setLong(3, 0L);
            lockStatement.executeUpdate();
            blockStatement.setString(1, key.world());
            blockStatement.setInt(2, key.x());
            blockStatement.setInt(3, key.y());
            blockStatement.setInt(4, key.z());
            blockStatement.setString(5, lock.lockId().toString());
            blockStatement.executeUpdate();
        }
    }

    private static Field databaseConnectionField() throws Exception {
        Field field = DatabaseManager.class.getDeclaredField("connection");
        field.setAccessible(true);
        return field;
    }
}
