package org.craftcore.stellaria.listeners;

import org.bukkit.Location;
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
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.ContainerLock;
import org.craftcore.stellaria.managers.ContainerLockManager;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.LinkedHashSet;
import java.util.Set;

/** ロック済みコンテナへのプレイヤー操作、搬送、環境破壊を止める。 */
public class ContainerLockListener implements Listener {
    private static final String CHANNEL = "container_lock";
    private final StellariaCore plugin;
    public ContainerLockListener(StellariaCore plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (!plugin.getContainerLockManager().canAccess(event.getClickedBlock(), event.getPlayer())) deny(event.getPlayer(), event);
    }
    @EventHandler(ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        if (plugin.getContainerLockManager().isLocked(event.getBlock()) && !event.getPlayer().hasPermission("stellaria.lock.admin")) deny(event.getPlayer(), event);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void joinLockedChest(BlockPlaceEvent event) {
        if (!(event.getBlockPlaced().getState() instanceof Chest chest)) return;
        InventoryHolder holder = chest.getInventory().getHolder();
        if (!(holder instanceof DoubleChest doubleChest)) return;
        Set<ContainerLock> neighboringLocks = new LinkedHashSet<>();
        addLock(neighboringLocks, doubleChest.getLeftSide());
        addLock(neighboringLocks, doubleChest.getRightSide());
        if (neighboringLocks.isEmpty()) return;
        ContainerLock.BlockKey placedKey = ContainerLock.BlockKey.of(event.getBlockPlaced());
        if (neighboringLocks.size() != 1) { deny(event.getPlayer(), event); return; }
        ContainerLock lock = neighboringLocks.iterator().next();
        if (lock.blocks().contains(placedKey)) return;
        if (!lock.canManage(event.getPlayer().getUniqueId(), event.getPlayer().hasPermission("stellaria.lock.admin"))
                || plugin.getContainerLockManager().attachBlock(lock, placedKey) != ContainerLockManager.CreateResult.SUCCESS) {
            deny(event.getPlayer(), event);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void removeBroken(BlockBreakEvent event) {
        if (event.getPlayer().hasPermission("stellaria.lock.admin")) plugin.getContainerLockManager().removeDestroyedBlock(ContainerLock.BlockKey.of(event.getBlock()));
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
    private void deny(Player player, org.bukkit.event.Cancellable event) { event.setCancelled(true); plugin.getActionBarManager().flash(player, CHANNEL, ColorUtil.component(plugin.getConfigManager().getMessage("lock.protected", player)), 40L); }
}
