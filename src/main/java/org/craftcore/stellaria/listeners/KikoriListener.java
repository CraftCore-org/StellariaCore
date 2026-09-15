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
import org.craftcore.stellaria.utils.TreeUtil;

/**
 * 木こり機能（/kikori）用のブロックイベント処理。
 * 丸太の設置/破壊で人工物タグを管理し、条件を満たした破壊で伐採を発動する。
 */
public class KikoriListener implements Listener {

    private final StellariaCore plugin;

    public KikoriListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (TreeUtil.isLog(block.getType())) {
            plugin.getKikoriManager().markArtificialLog(block);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!TreeUtil.isLog(block.getType())) {
            return;
        }

        boolean wasArtificial = plugin.getKikoriManager().isArtificialLog(block);
        plugin.getKikoriManager().unmarkArtificialLog(block);

        Player player = event.getPlayer();
        if (!plugin.getKikoriManager().isEnabled(player.getUniqueId())) {
            return;
        }
        if (!isHoldingAxe(player)) {
            return;
        }
        if (wasArtificial) {
            return; // 自分で置いた1本を素直に壊すのは想定内の操作なので伐採は発動しない
        }
        plugin.getKikoriManager().tryStartFelling(player, block);
    }

    private boolean isHoldingAxe(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        return Tag.ITEMS_AXES.isTagged(item.getType());
    }
}
