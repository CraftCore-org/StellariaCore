package org.craftcore.stellaria.listeners;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.List;
import java.util.UUID;

/**
 * 土地保護（/land）のイベント判定。判定の中心は
 * LandManager#canBuild（ブロック操作・PvP以外）とLandManager#isPvpAllowed（PvPのみ）の2つ。
 */
public class LandProtectionListener implements Listener {

    /**
     * 保護通知はチャットではなくアクションバーの一時フラッシュ表示にする（ActionBarManager#flash）。
     * 木こりの連鎖伐採やPvP連打で同じ判定が短時間に何度も走っても、アクションバーは同じチャンネルを
     * 上書きするだけでチャット欄を汚さないため、独自のクールダウン管理が不要になる。
     */
    private static final long NOTICE_DURATION_TICKS = 40L;
    private static final String NOTICE_CHANNEL = "land_protection";

    private final StellariaCore plugin;

    public LandProtectionListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    private void notify(Player player, String messageKey) {
        String message = plugin.getConfigManager().getMessage(messageKey, player);
        plugin.getActionBarManager().flash(player, NOTICE_CHANNEL, ColorUtil.component(message), NOTICE_DURATION_TICKS);
    }

    /**
     * LOW優先度で登録する（StellariaCore側）。KikoriListener#onBlockBreakより先に評価させ、
     * ここでキャンセルしたブロックについては連鎖伐採の起動判定自体が走らないようにするため。
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getLandManager().canBuild(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            notify(event.getPlayer(), "land.protected_block");
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getLandManager().canBuild(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            notify(event.getPlayer(), "land.protected_block");
        }
    }

    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!plugin.getLandManager().canBuild(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            notify(event.getPlayer(), "land.protected_block");
        }
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!plugin.getLandManager().canBuild(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            notify(event.getPlayer(), "land.protected_block");
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
            notify(event.getPlayer(), "land.protected_block");
        }
    }

    private boolean isProtectedInteractable(Material material) {
        List<String> names = plugin.getConfigManager().getStringList("land.protected-interactables");
        return names.contains(material.name());
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.hasPermission("stellaria.land.admin")) {
            return;
        }

        // アーマースタンドはPvPではなく設置物の一種として扱う（破壊・攻撃はcanBuildで判定）。
        if (event.getEntity() instanceof ArmorStand armorStand) {
            if (!plugin.getLandManager().canBuild(armorStand.getLocation(), attacker)) {
                event.setCancelled(true);
                notify(attacker, "land.protected_block");
            }
            return;
        }

        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }

        // claim不可ワールド（land.enabled-worlds外）では土地保護のPvP制限を適用しない。
        // バニラ/他プラグインのPvP挙動にそのまま委ねる。
        List<String> enabledWorlds = plugin.getConfigManager().getStringList("land.enabled-worlds");
        if (!enabledWorlds.contains(victim.getWorld().getName())) {
            return;
        }

        if (!plugin.getLandManager().isPvpAllowed(victim.getLocation())) {
            event.setCancelled(true);
            notify(attacker, "land.pvp_blocked");
        }
    }

    /** 直接攻撃・投射物・起爆されたTNT・懐かれた動物（オオカミ等）の攻撃者をPlayerまで解決する。 */
    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) {
            return player;
        }
        if (damager instanceof Tameable tameable && tameable.isTamed() && tameable.getOwner() instanceof Player player) {
            return player;
        }
        return null;
    }

    @EventHandler
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.hangings", true)) {
            return;
        }
        if (!(event.getRemover() instanceof Player player)) {
            return; // 爆発等プレイヤー以外が原因の場合は他のイベントハンドラ（爆発保護等）に任せる
        }
        if (!plugin.getLandManager().canBuild(event.getEntity().getLocation(), player)) {
            event.setCancelled(true);
            notify(player, "land.protected_block");
        }
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.pistons", true)) {
            return;
        }
        if (touchesClaim(event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.pistons", true)) {
            return;
        }
        if (touchesClaim(event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    /** 移動対象ブロック、またはその移動先チャンクのいずれかがclaim済みならtrue（ピストンには行為者が無いため、
     * オーナー・信頼リストに関わらず一律でclaim済みチャンクをまたぐ移動そのものを禁止する）。 */
    private boolean touchesClaim(List<Block> movedBlocks, BlockFace direction) {
        for (Block block : movedBlocks) {
            if (plugin.getLandManager().ownerOf(block.getLocation()) != null) {
                return true;
            }
            Block destination = block.getRelative(direction);
            if (plugin.getLandManager().ownerOf(destination.getLocation()) != null) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onLiquidFlow(BlockFromToEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.liquid-flow", true)) {
            return;
        }
        UUID toOwner = plugin.getLandManager().ownerOf(event.getToBlock().getLocation());
        if (toOwner == null) {
            return; // 流入先が未claimなら何もしない
        }
        UUID fromOwner = plugin.getLandManager().ownerOf(event.getBlock().getLocation());
        if (!toOwner.equals(fromOwner)) {
            // 流入元が別オーナー（または未claim）の場合のみキャンセル。同じ縄張り内の自然な流れは許可する。
            event.setCancelled(true);
        }
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
