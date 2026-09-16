package org.craftcore.stellaria.listeners;

import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.OreUtil;

/**
 * 鉱石一括破壊機能（/mine）用のブロックイベント処理。
 * 鉱石の設置/破壊で人工物タグを管理し、条件を満たした破壊で採掘を発動する。
 */
public class MineListener implements Listener {

    private final StellariaCore plugin;

    public MineListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
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

        boolean wasArtificial = plugin.getMineManager().isArtificialOre(block);
        plugin.getMineManager().unmarkArtificialOre(block);

        Player player = event.getPlayer();
        if (!plugin.getMineManager().isEnabled(player.getUniqueId())) {
            return;
        }
        if (!isHoldingPickaxe(player)) {
            return;
        }
        if (wasArtificial) {
            return; // 自分で置いた1個を素直に壊すのは想定内の操作なので採掘は発動しない
        }
        plugin.getMineManager().tryStartMining(player, block);
    }

    private boolean isHoldingPickaxe(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        return Tag.ITEMS_PICKAXES.isTagged(item.getType());
    }
}
