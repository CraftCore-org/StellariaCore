package org.craftcore.stellaria.enchants;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.util.RayTraceResult;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.enchants.ExcavationRules.Offset;
import org.craftcore.stellaria.utils.ColorUtil;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 範囲破壊: 殴った面に垂直な 3x3 の範囲をまとめて掘る。周囲のブロックは player.breakBlock() で壊すため、
 * 土地保護・/mine の鉱石報酬・自動精錬・耐久消費が通常の破壊と同じく適用される。
 * breakBlock() は BlockBreakEvent を再び発火させるので、処理中のプレイヤーを excavating で除外して連鎖を防ぐ。
 * 土地保護（LOW）・ロビー保護（NORMAL）がキャンセルした破壊では動かないよう HIGH で受ける。
 * 中心・周囲とも道具の mineable タグ（ツルハシなら #mineable/pickaxe）に入るブロックだけを対象にし、
 * ガラスや松明、作物、木材など道具を選ばないブロックを巻き込まないようにする。
 * 範囲の向きは掘り始めたときにクライアントが送ってきた面（BlockDamageEvent）を優先し、無ければ視線から求める。
 */
public final class ExcavationListener implements Listener {

    private static final double DEFAULT_REACH = 4.5;

    private final StellariaCore plugin;
    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;
    private final Set<UUID> excavating = new HashSet<>();
    private final LastHitFaces lastHitFaces = new LastHitFaces();

    public ExcavationListener(StellariaCore plugin, CustomEnchantRegistry registry, CustomEnchantConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        if (registry.level(event.getItemInHand(), CustomEnchant.EXCAVATION) <= 0) {
            return;
        }
        Block block = event.getBlock();
        lastHitFaces.record(player.getUniqueId(), block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ(), event.getBlockFace());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastHitFaces.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (excavating.contains(uuid)
                || registry.level(player.getInventory().getItemInMainHand(), CustomEnchant.EXCAVATION) <= 0
                || player.isSneaking()
                || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        Block center = event.getBlock();
        Tag<Material> mineable = mineableTag(player.getInventory().getItemInMainHand());
        if (mineable == null || !mineable.isTagged(center.getType())) {
            return; // ツルハシで木材を掘ったときなど、道具の本来の対象でなければ範囲破壊しない
        }
        BlockFace face = hitFace(player, center);
        if (face == null) {
            return; // 面を特定できないときは誤った方向に掘らないよう中心だけにする
        }
        float centerHardness = center.getType().getHardness();

        excavating.add(uuid);
        try {
            for (Offset offset : ExcavationRules.offsets(face)) {
                ItemStack tool = player.getInventory().getItemInMainHand();
                if (registry.level(tool, CustomEnchant.EXCAVATION) <= 0) {
                    return;
                }
                Block target = center.getRelative(offset.dx(), offset.dy(), offset.dz());
                if (!canBreak(target, tool, mineable, centerHardness)) {
                    continue;
                }
                // 壊せるブロックがあるときだけ確認し、空中の 1 ブロックを掘っただけで警告が出ないようにする
                if (!ExcavationRules.hasEnoughDurability(remainingDurability(tool), config.excavationMinDurability())) {
                    warnLowDurability(player);
                    return;
                }
                player.breakBlock(target);
            }
        } finally {
            excavating.remove(uuid);
        }
    }

    private static @Nullable Tag<Material> mineableTag(ItemStack tool) {
        ExcavationRules.Tool kind = ExcavationRules.toolOf(tool.getType());
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case PICKAXE -> Tag.MINEABLE_PICKAXE;
            case SHOVEL -> Tag.MINEABLE_SHOVEL;
        };
    }

    private @Nullable BlockFace hitFace(Player player, Block center) {
        BlockFace recorded = lastHitFaces.faceFor(player.getUniqueId(), center.getWorld().getUID(),
                center.getX(), center.getY(), center.getZ());
        if (recorded != null) {
            return recorded;
        }
        AttributeInstance reach = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        double distance = (reach == null ? DEFAULT_REACH : reach.getValue()) + 1.0;
        RayTraceResult hit = player.rayTraceBlocks(distance, FluidCollisionMode.NEVER);
        if (hit == null || !center.equals(hit.getHitBlock())) {
            return null;
        }
        return hit.getHitBlockFace();
    }

    private static boolean canBreak(Block target, ItemStack tool, Tag<Material> mineable, float centerHardness) {
        Material type = target.getType();
        return ExcavationRules.canBreakAround(
                type.isAir() || target.isLiquid(),
                mineable.isTagged(type),
                target.isPreferredTool(tool),
                type.getHardness(),
                centerHardness,
                ExcavationRules.isIrreplaceable(type) || target.getState(false) instanceof TileState);
    }

    /** 残り耐久値。耐久値の無い道具（Unbreakable など）は null。 */
    private static @Nullable Integer remainingDurability(ItemStack tool) {
        if (!(tool.getItemMeta() instanceof Damageable damageable) || damageable.isUnbreakable()) {
            return null;
        }
        int max = damageable.hasMaxDamage() ? damageable.getMaxDamage() : tool.getType().getMaxDurability();
        if (max <= 0) {
            return null;
        }
        return max - damageable.getDamage();
    }

    private void warnLowDurability(Player player) {
        String message = plugin.getConfigManager().getMessage("custom-enchants.excavation_low_durability", player);
        plugin.getActionBarManager().flash(player, "excavation", ColorUtil.component(message), 60L);
    }
}
