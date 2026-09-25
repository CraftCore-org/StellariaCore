package org.craftcore.stellaria.enchants;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.enchants.SaplingPlanner.Pos;
import org.craftcore.stellaria.managers.KikoriManager;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.List;

/**
 * 植樹: 木こり機能での伐採が終わったとき、根元に同じ種類の苗木をインベントリから消費して植える。
 * 2x2 の木は苗木が 4 本そろっているときだけ植える（ダークオークは 1 本では育たないため）。
 */
public final class ReplantHandler implements KikoriManager.FellCompleteHandler {

    private final StellariaCore plugin;
    private final CustomEnchantRegistry registry;

    public ReplantHandler(StellariaCore plugin, CustomEnchantRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override
    public void onFellComplete(Player player, ItemStack axe, Material logType, List<Block> roots) {
        if (!player.isOnline() || roots.isEmpty() || registry.level(axe, CustomEnchant.REPLANT) <= 0) {
            return;
        }
        String saplingName = SaplingPlanner.saplingFor(logType.name());
        Material sapling = saplingName == null ? null : Material.matchMaterial(saplingName);
        if (sapling == null) {
            return;
        }

        World world = roots.get(0).getWorld();
        List<Block> targets = SaplingPlanner.plan(roots.stream()
                        .map(block -> new Pos(block.getX(), block.getY(), block.getZ()))
                        .toList())
                .stream()
                .map(pos -> world.getBlockAt(pos.x(), pos.y(), pos.z()))
                .toList();
        for (Block target : targets) {
            if (!target.getType().isAir() || !Tag.DIRT.isTagged(target.getRelative(BlockFace.DOWN).getType())) {
                return;
            }
        }

        int required = targets.size();
        if (!player.getInventory().containsAtLeast(new ItemStack(sapling), required)) {
            String message = plugin.getConfigManager()
                    .getMessage("custom-enchants.replant_not_enough_saplings", player)
                    .replace("%required%", String.valueOf(required));
            plugin.getActionBarManager().flash(player, "replant", ColorUtil.component(message), 60L);
            return;
        }
        player.getInventory().removeItem(new ItemStack(sapling, required));
        for (Block target : targets) {
            target.setType(sapling);
        }
        world.playSound(targets.get(0).getLocation(), Sound.BLOCK_GRASS_PLACE, 1.0f, 1.0f);
    }
}
