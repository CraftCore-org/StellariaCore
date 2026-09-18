package org.craftcore.stellaria.listeners;

import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.OreUtil;
import org.craftcore.stellaria.utils.WorldBlacklistUtil;
import org.bukkit.enchantments.Enchantment;

/**
 * 鉱石一括破壊機能（/mine）用のブロックイベント処理。
 * 鉱石の設置/破壊で人工物タグを管理し、条件を満たした破壊で採掘を発動する。
 */
public class MineListener implements Listener {

    private final StellariaCore plugin;

    public MineListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (OreUtil.isOre(block.getType())) {
            plugin.getMineManager().markArtificialOre(block);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!OreUtil.isOre(block.getType())) {
            return;
        }

        Player player = event.getPlayer();

        boolean wasArtificial = plugin.getMineManager().isArtificialOre(block);
        plugin.getMineManager().unmarkArtificialOre(block);

        // 天然鉱石 + ツルハシ + シルクタッチ無しなら報酬
        if (!wasArtificial
                && isHoldingPickaxe(player)
                && !hasSilkTouch(player)) {

            int reward = getOreReward(block.getType());

            if (reward > 0) {
                plugin.getIncomeManager().reward(player, reward);
            }
        }

        if (WorldBlacklistUtil.isBlacklisted(
                plugin.getConfigManager().getStringList("mine.disabled-worlds", true),
                block.getWorld().getName())) {

            if (plugin.getMineManager().isEnabled(player.getUniqueId())
                    && isHoldingPickaxe(player)) {

                String warning = plugin.getConfigManager()
                        .getMessage("mine.world_disabled", player);

                plugin.getActionBarManager().flash(
                        player,
                        "mine_warning",
                        ColorUtil.component(warning),
                        60L
                );
            }

            return;
        }

        if (!plugin.getMineManager().isEnabled(player.getUniqueId())) {
            return;
        }

        if (!isHoldingPickaxe(player)) {
            return;
        }

        if (wasArtificial) {
            return;
        }

        plugin.getMineManager().tryStartMining(player, block);
    }

    private boolean isHoldingPickaxe(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        return Tag.ITEMS_PICKAXES.isTagged(item.getType());
    }

    private boolean hasSilkTouch(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();

        return item.getEnchantmentLevel(Enchantment.SILK_TOUCH) > 0;
    }

    private int getOreReward(org.bukkit.Material material) {
        String materialName = material.name();

        // 深層岩鉱石は通常鉱石と同じ価格にする
        if (materialName.startsWith("DEEPSLATE_")) {
            materialName = materialName.substring("DEEPSLATE_".length());
        }

        return Math.max(
                0,
                plugin.getConfigManager().getInt(
                        "income.mining.rewards." + materialName,
                        0,
                        true
                )
        );
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event){
        int newSlot = event.getNewSlot();
        ItemStack item = event.getPlayer().getInventory().getItem(newSlot);
        if (item == null) { return; }
        if (!Tag.ITEMS_PICKAXES.isTagged(item.getType())){ return; }
        if (!plugin.getMineManager().isEnabled(event.getPlayer().getUniqueId())) { return; }
        String warning = plugin.getConfigManager().getMessage("mine.actionbar_enabled", event.getPlayer());
        plugin.getActionBarManager().flash(event.getPlayer(),"mine_actionbar",ColorUtil.component(warning),60L);
    }
}
