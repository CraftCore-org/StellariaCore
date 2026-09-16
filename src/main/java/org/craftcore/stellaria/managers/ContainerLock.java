package org.craftcore.stellaria.managers;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.block.Block;

/**
 * 個別コンテナロックの所有者・共同利用者・対象ブロックを表すドメインモデル。
 * DBとPaperイベントには依存せず、ContainerLockManagerが永続化と索引を担当する。
 */
public final class ContainerLock {

    /** ワールド名とブロック座標からなる永続化・キャッシュ用キー。 */
    public record BlockKey(String world, int x, int y, int z) {
        public static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }
    }

    private final UUID lockId;
    private final UUID owner;
    private final Set<BlockKey> blocks;
    private final Set<UUID> members;

    public ContainerLock(UUID lockId, UUID owner, Set<BlockKey> blocks, Set<UUID> members) {
        this.lockId = lockId;
        this.owner = owner;
        this.blocks = new LinkedHashSet<>(blocks);
        this.members = new LinkedHashSet<>(members);
    }

    public UUID lockId() {
        return lockId;
    }

    public UUID owner() {
        return owner;
    }

    public Set<BlockKey> blocks() {
        return Collections.unmodifiableSet(blocks);
    }

    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    public synchronized boolean canAccess(UUID playerId, boolean admin) {
        return admin || owner.equals(playerId) || members.contains(playerId);
    }

    public synchronized boolean canManage(UUID playerId, boolean admin) {
        return admin || owner.equals(playerId);
    }

    /** 所有者自身は共同利用者として重複登録しない。 */
    public synchronized boolean addMember(UUID playerId) {
        return !owner.equals(playerId) && members.add(playerId);
    }

    public synchronized boolean removeMember(UUID playerId) {
        return members.remove(playerId);
    }

    public synchronized boolean addBlock(BlockKey blockKey) {
        return blocks.add(blockKey);
    }

    public synchronized boolean removeBlock(BlockKey blockKey) {
        return blocks.remove(blockKey);
    }

    public synchronized boolean isEmpty() {
        return blocks.isEmpty();
    }
}
