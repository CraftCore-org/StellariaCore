package org.craftcore.stellaria.listeners;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.craftcore.stellaria.StellariaCore;

import java.util.List;

/**
 * 土地保護（/land）のイベント判定。判定の中心は
 * LandManager#canBuild（ブロック操作・PvP以外）とLandManager#isPvpAllowed（PvPのみ）の2つ。
 */
public class LandProtectionListener implements Listener {

    private final StellariaCore plugin;

    public LandProtectionListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /**
     * LOW優先度で登録する（StellariaCore側）。KikoriListener#onBlockBreakより先に評価させ、
     * ここでキャンセルしたブロックについては連鎖伐採の起動判定自体が走らないようにするため。
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getLandManager().canBuild(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getConfigManager().getMessage("land.protected-block", event.getPlayer()));
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getLandManager().canBuild(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getConfigManager().getMessage("land.protected-block", event.getPlayer()));
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!isProtectedInteractable(block.getType())) {
            return;
        }
        if (!plugin.getLandManager().canBuild(block.getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getConfigManager().getMessage("land.protected-block", event.getPlayer()));
        }
    }

    private boolean isProtectedInteractable(Material material) {
        List<String> names = plugin.getConfigManager().getStringList("land.protected-interactables");
        return names.contains(material.name());
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.hasPermission("stellaria.land.admin")) {
            return;
        }
        if (!plugin.getLandManager().isPvpAllowed(victim.getLocation())) {
            event.setCancelled(true);
            attacker.sendMessage(plugin.getConfigManager().getMessage("land.pvp-blocked", attacker));
        }
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.explosions", true)) {
            return;
        }
        event.blockList().removeIf(block -> plugin.getLandManager().ownerOf(block.getLocation()) != null);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.explosions", true)) {
            return;
        }
        event.blockList().removeIf(block -> plugin.getLandManager().ownerOf(block.getLocation()) != null);
    }

    @EventHandler
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.fire", true)) {
            return;
        }
        if (plugin.getLandManager().ownerOf(event.getBlock().getLocation()) == null) {
            return;
        }
        Player igniter = event.getPlayer();
        if (igniter != null && plugin.getLandManager().canBuild(event.getBlock().getLocation(), igniter)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onBlockBurn(BlockBurnEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.fire", true)) {
            return;
        }
        if (plugin.getLandManager().ownerOf(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }
}
