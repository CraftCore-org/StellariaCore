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
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.craftcore.stellaria.StellariaCore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 土地保護（/land）のイベント判定。判定の中心は
 * LandManager#canBuild（ブロック操作・PvP以外）とLandManager#isPvpAllowed（PvPのみ）の2つ。
 */
public class LandProtectionListener implements Listener {

    /** 保護通知メッセージのクールダウン（ミリ秒）。連鎖伐採やPvP連打で同じメッセージが連投されるのを防ぐ。 */
    private static final long NOTICE_COOLDOWN_MILLIS = 2000L;

    private final StellariaCore plugin;
    private final Map<UUID, Long> lastNoticeMillis = new HashMap<>();

    public LandProtectionListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /**
     * 保護通知メッセージを送ってよいか（かつ送るなら最終送信時刻を更新）。
     * event.setCancelled(true)自体には決して関与しない ― キャンセルは常に無条件で行い、
     * このメソッドはチャット通知の連投を防ぐためだけに使う。
     */
    private boolean shouldNotify(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastNoticeMillis.get(player.getUniqueId());
        if (last != null && now - last < NOTICE_COOLDOWN_MILLIS) {
            return false;
        }
        lastNoticeMillis.put(player.getUniqueId(), now);
        return true;
    }

    /**
     * LOW優先度で登録する（StellariaCore側）。KikoriListener#onBlockBreakより先に評価させ、
     * ここでキャンセルしたブロックについては連鎖伐採の起動判定自体が走らないようにするため。
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getLandManager().canBuild(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            if (shouldNotify(event.getPlayer())) {
                event.getPlayer().sendMessage(plugin.getConfigManager().getMessage("land.protected-block", event.getPlayer()));
            }
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getLandManager().canBuild(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            if (shouldNotify(event.getPlayer())) {
                event.getPlayer().sendMessage(plugin.getConfigManager().getMessage("land.protected-block", event.getPlayer()));
            }
        }
    }

    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!plugin.getLandManager().canBuild(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            if (shouldNotify(event.getPlayer())) {
                event.getPlayer().sendMessage(plugin.getConfigManager().getMessage("land.protected-block", event.getPlayer()));
            }
        }
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!plugin.getLandManager().canBuild(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            if (shouldNotify(event.getPlayer())) {
                event.getPlayer().sendMessage(plugin.getConfigManager().getMessage("land.protected-block", event.getPlayer()));
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!isProtectedInteractable(block.getType())) {
            return;
        }
        if (!plugin.getLandManager().canBuild(block.getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            if (shouldNotify(event.getPlayer())) {
                event.getPlayer().sendMessage(plugin.getConfigManager().getMessage("land.protected-block", event.getPlayer()));
            }
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
        if (attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        if (!plugin.getLandManager().isPvpAllowed(victim.getLocation())) {
            event.setCancelled(true);
            if (shouldNotify(attacker)) {
                attacker.sendMessage(plugin.getConfigManager().getMessage("land.pvp-blocked", attacker));
            }
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
