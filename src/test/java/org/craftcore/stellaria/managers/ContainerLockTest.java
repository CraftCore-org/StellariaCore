package org.craftcore.stellaria.managers;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerLockTest {

    @Test
    void ownerAndMemberCanAccessButOnlyOwnerCanManage() {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        ContainerLock lock = lock(owner, Set.of(member), Set.of(key(0)));

        assertTrue(lock.canAccess(owner, false));
        assertTrue(lock.canAccess(member, false));
        assertFalse(lock.canManage(member, false));
        assertTrue(lock.canManage(owner, false));
    }

    @Test
    void adminCanAccessAndManageWithoutMembership() {
        ContainerLock lock = lock(UUID.randomUUID(), Set.of(), Set.of(key(0)));
        UUID stranger = UUID.randomUUID();

        assertTrue(lock.canAccess(stranger, true));
        assertTrue(lock.canManage(stranger, true));
    }

    @Test
    void memberMutationRejectsOwnerAndDuplicateMembers() {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        ContainerLock lock = lock(owner, Set.of(), Set.of(key(0)));

        assertFalse(lock.addMember(owner));
        assertTrue(lock.addMember(member));
        assertFalse(lock.addMember(member));
        assertTrue(lock.removeMember(member));
        assertFalse(lock.removeMember(member));
    }

    @Test
    void removingLastBlockMakesLockEmptyButRemovingOneOfTwoDoesNot() {
        ContainerLock.BlockKey left = key(0);
        ContainerLock.BlockKey right = key(1);
        ContainerLock lock = lock(UUID.randomUUID(), Set.of(), Set.of(left, right));

        assertTrue(lock.removeBlock(left));
        assertFalse(lock.isEmpty());
        assertTrue(lock.removeBlock(right));
        assertTrue(lock.isEmpty());
    }

    private static ContainerLock lock(UUID owner, Set<UUID> members, Set<ContainerLock.BlockKey> blocks) {
        return new ContainerLock(UUID.randomUUID(), owner, blocks, members);
    }

    private static ContainerLock.BlockKey key(int x) {
        return new ContainerLock.BlockKey("world", x, 64, 0);
    }
}
