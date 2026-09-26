package org.craftcore.stellaria.enchants;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
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
 */
public final class ExcavationListener implements Listener {

    private static final double DEFAULT_REACH = 4.5;

    private final StellariaCore plugin;
    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;
    private final Set<UUID> excavating = new HashSet<>();

    public ExcavationListener(StellariaCore plugin, CustomEnchantRegistry registry, CustomEnchantConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
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
                if (!canBreak(target, tool, centerHardness)) {
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

    private static @Nullable BlockFace hitFace(Player player, Block center) {
        AttributeInstance reach = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        double distance = (reach == null ? DEFAULT_REACH : reach.getValue()) + 1.0;
        RayTraceResult hit = player.rayTraceBlocks(distance, FluidCollisionMode.NEVER);
        if (hit == null || !center.equals(hit.getHitBlock())) {
            return null;
        }
        return hit.getHitBlockFace();
    }

    private static boolean canBreak(Block target, ItemStack tool, float centerHardness) {
        Material type = target.getType();
        return ExcavationRules.canBreakAround(
                type.isAir() || target.isLiquid(),
                target.isPreferredTool(tool),
                type.getHardness(),
                centerHardness,
                target.getState(false) instanceof Container);
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
