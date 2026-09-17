package org.craftcore.stellaria.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.ContainerLock;
import org.craftcore.stellaria.managers.ContainerLockManager;
import org.craftcore.stellaria.utils.ContainerLockMessages;

import java.util.LinkedHashSet;
import java.util.Set;

/** ロック済みコンテナへのプレイヤー操作、搬送、環境破壊を止める。 */
public class ContainerLockListener implements Listener {
    private final StellariaCore plugin;
    public ContainerLockListener(StellariaCore plugin) { this.plugin = plugin; }

    @EventHandler
    public void worldLoad(WorldLoadEvent event) {
        plugin.getContainerLockManager().reconcileWorld(event.getWorld());
    }

    @EventHandler(ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (!plugin.getContainerLockManager().canAccess(event.getClickedBlock(), event.getPlayer())) deny(event.getPlayer(), event, event.getClickedBlock());
    }
    @EventHandler(ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        ContainerLock lock = plugin.getContainerLockManager().find(event.getBlock()).orElse(null);
        if (lock != null && !canDestroy(lock, event.getPlayer().getUniqueId(), plugin.getContainerLockManager().isBypassing(event.getPlayer()))) {
            deny(event.getPlayer(), event, event.getBlock());
        }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void joinLockedChest(BlockPlaceEvent event) {
        if (!(event.getBlockPlaced().getState() instanceof Chest chest)) return;
        InventoryHolder holder = chest.getInventory().getHolder();
        if (!(holder instanceof DoubleChest doubleChest)) return;
        Set<ContainerLock> neighboringLocks = new LinkedHashSet<>();
        addLock(neighboringLocks, doubleChest.getLeftSide());
        addLock(neighboringLocks, doubleChest.getRightSide());
        if (neighboringLocks.isEmpty()) return;
        ContainerLock.BlockKey placedKey = ContainerLock.BlockKey.of(event.getBlockPlaced());
        if (neighboringLocks.size() != 1) { deny(event.getPlayer(), event, event.getBlockPlaced()); return; }
        ContainerLock lock = neighboringLocks.iterator().next();
        if (lock.blocks().contains(placedKey)) return;
        if (!lock.canManage(event.getPlayer().getUniqueId(), plugin.getContainerLockManager().isBypassing(event.getPlayer()))
                || plugin.getContainerLockManager().attachBlock(lock, placedKey) != ContainerLockManager.CreateResult.SUCCESS) {
            deny(event.getPlayer(), event, event.getBlockPlaced());
        } else {
            scheduleAttachedBlockVerification(event.getBlockPlaced(), placedKey, lock.lockId());
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void removeBroken(BlockBreakEvent event) {
        plugin.getContainerLockManager().find(event.getBlock()).ifPresent(lock -> {
            if (canDestroy(lock, event.getPlayer().getUniqueId(), plugin.getContainerLockManager().isBypassing(event.getPlayer()))) {
                scheduleDestroyedBlockCleanup(event.getPlayer(), event.getBlock(), ContainerLock.BlockKey.of(event.getBlock()));
            }
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void autoLockPlacedContainer(BlockPlaceEvent event) {
        ContainerLockManager manager = plugin.getContainerLockManager();
        Block placed = event.getBlockPlaced();
        if (!manager.hasAutoLockEnabled(event.getPlayer().getUniqueId())
                || !ContainerLockManager.isLockable(placed.getType())
                || manager.isLocked(placed)) {
            return;
        }
        ContainerLockManager.CreateResult result = manager.create(event.getPlayer(), targetKeys(placed));
        if (result != ContainerLockManager.CreateResult.SUCCESS) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ContainerLockMessages.message(plugin.getConfigManager(), "lock.auto_failed", event.getPlayer()));
            return;
        }
        event.getPlayer().sendMessage(ContainerLockMessages.message(plugin.getConfigManager(), "lock.auto_locked", event.getPlayer()));
        ContainerLock lock = manager.find(placed).orElseThrow();
        scheduleAutoLockVerification(placed, lock.lockId());
    }
    @EventHandler(ignoreCancelled = true)
    public void move(InventoryMoveItemEvent event) {
        if (locked(event.getSource().getLocation()) || locked(event.getDestination().getLocation())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void entityExplode(EntityExplodeEvent event) { event.blockList().removeIf(plugin.getContainerLockManager()::isLocked); }
    @EventHandler(ignoreCancelled = true)
    public void blockExplode(BlockExplodeEvent event) { event.blockList().removeIf(plugin.getContainerLockManager()::isLocked); }
    @EventHandler(ignoreCancelled = true)
    public void pistonExtend(BlockPistonExtendEvent event) { if (event.getBlocks().stream().anyMatch(plugin.getContainerLockManager()::isLocked)) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void pistonRetract(BlockPistonRetractEvent event) { if (event.getBlocks().stream().anyMatch(plugin.getContainerLockManager()::isLocked)) event.setCancelled(true); }
    private boolean locked(Location location) { return location != null && plugin.getContainerLockManager().isLocked(location.getBlock()); }
    private void addLock(Set<ContainerLock> locks, InventoryHolder holder) {
        if (holder instanceof Chest chest) plugin.getContainerLockManager().find(chest.getBlock()).ifPresent(locks::add);
    }
    private static Set<ContainerLock.BlockKey> targetKeys(Block target) {
        Set<ContainerLock.BlockKey> keys = new LinkedHashSet<>();
        keys.add(ContainerLock.BlockKey.of(target));
        if (!(target.getState() instanceof Chest chest)) return keys;
        InventoryHolder holder = chest.getInventory().getHolder();
        if (holder instanceof DoubleChest doubleChest) {
            addChestKey(keys, doubleChest.getLeftSide());
            addChestKey(keys, doubleChest.getRightSide());
        }
        return keys;
    }
    private static void addChestKey(Set<ContainerLock.BlockKey> keys, InventoryHolder holder) {
        if (holder instanceof Chest chest) keys.add(ContainerLock.BlockKey.of(chest.getBlock()));
    }
    private void scheduleDestroyedBlockCleanup(Player player, Block block, ContainerLock.BlockKey key) {
        plugin.getServer().getScheduler().runTask(plugin, () -> cleanupDestroyedBlock(player, block, key));
    }
    private void cleanupDestroyedBlock(Player player, Block block, ContainerLock.BlockKey key) {
        ContainerLockManager.RemoveResult result = plugin.getContainerLockManager().removeDestroyedBlock(key);
        if (result == ContainerLockManager.RemoveResult.DATABASE_ERROR) {
            plugin.getLogger().warning("破壊済みコンテナのロック削除に失敗したため再試行します: " + key);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> cleanupDestroyedBlock(player, block, key), 20L);
            return;
        }
        if (result == ContainerLockManager.RemoveResult.SUCCESS) {
            player.sendMessage(ContainerLockMessages.message(plugin.getConfigManager(), "lock.auto_unlocked", player));
        }
    }
    private void scheduleAttachedBlockVerification(Block block, ContainerLock.BlockKey key, java.util.UUID lockId) {
        plugin.getServer().getScheduler().runTask(plugin, () -> rollbackUnplacedAttachedBlock(block, key, lockId));
    }
    private void rollbackUnplacedAttachedBlock(Block block, ContainerLock.BlockKey key, java.util.UUID lockId) {
        if (isPlacementConfirmed(block.getType())) return;
        ContainerLock lock = plugin.getContainerLockManager().find(key).orElse(null);
        if (lock == null || !lock.lockId().equals(lockId)) return;
        if (plugin.getContainerLockManager().removeDestroyedBlock(key) == ContainerLockManager.RemoveResult.DATABASE_ERROR) {
            plugin.getLogger().warning("取り消された二連チェスト設置のロック削除に失敗したため再試行します: " + key);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> rollbackUnplacedAttachedBlock(block, key, lockId), 20L);
        }
    }
    private void scheduleAutoLockVerification(Block block, java.util.UUID lockId) {
        plugin.getServer().getScheduler().runTask(plugin, () -> rollbackUnplacedAutoLock(block, lockId));
    }
    private void rollbackUnplacedAutoLock(Block block, java.util.UUID lockId) {
        if (isPlacementConfirmed(block.getType())) return;
        ContainerLock lock = plugin.getContainerLockManager().find(block).orElse(null);
        if (lock == null || !lock.lockId().equals(lockId)) return;
        if (plugin.getContainerLockManager().unlock(lock) == ContainerLockManager.RemoveResult.DATABASE_ERROR) {
            plugin.getLogger().warning("取り消された自動ロック設置のロック削除に失敗したため再試行します: " + lockId);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> rollbackUnplacedAutoLock(block, lockId), 20L);
        }
    }
    static String protectedMessageKey(Material material) {
        if (material == Material.BARREL) return "lock.protected_barrel";
        if (material.name().endsWith("SHULKER_BOX")) return "lock.protected_shulker_box";
        return "lock.protected_chest";
    }
    static boolean canDestroy(ContainerLock lock, java.util.UUID playerId, boolean bypassing) {
        return lock.canManage(playerId, bypassing);
    }
    static boolean isPlacementConfirmed(Material material) {
        return ContainerLockManager.isLockable(material);
    }
    private void deny(Player player, org.bukkit.event.Cancellable event, Block block) {
        event.setCancelled(true);
        player.sendMessage(ContainerLockMessages.message(plugin.getConfigManager(), protectedMessageKey(block.getType()), player));
    }
}
