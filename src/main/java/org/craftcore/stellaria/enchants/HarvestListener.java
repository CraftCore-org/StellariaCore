package org.craftcore.stellaria.enchants;

import org.bukkit.Tag;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.craftcore.stellaria.enchants.premium.PremiumCrops;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 豊穣: 成熟した作物をクワで収穫したとき、作物 1 個ごとに確率で上質作物に置き換える。
 * 種（小麦の種・ビートルートの種）は PremiumCrops.CROPS に含まれないため対象外。
 */
public final class HarvestListener implements Listener {

    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;
    private final PremiumCrops crops;

    public HarvestListener(CustomEnchantRegistry registry, CustomEnchantConfig config, PremiumCrops crops) {
        this.registry = registry;
        this.config = config;
        this.crops = crops;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!(event.getBlockState().getBlockData() instanceof Ageable ageable)
                || ageable.getAge() < ageable.getMaximumAge()) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!Tag.ITEMS_HOES.isTagged(tool.getType())) {
            return;
        }
        int level = registry.level(tool, CustomEnchant.HARVEST);
        if (level <= 0) {
            return;
        }
        double chance = EnchantMath.chanceForLevel(config.harvestChancePerLevel(), level);

        List<ItemStack> premiumDrops = new ArrayList<>();
        for (Item item : event.getItems()) {
            ItemStack drop = item.getItemStack();
            if (!PremiumCrops.CROPS.contains(drop.getType()) || crops.isPremium(drop)) {
                continue;
            }
            int premium = EnchantMath.rollSuccesses(drop.getAmount(), chance, ThreadLocalRandom.current()::nextDouble);
            if (premium == 0) {
                continue;
            }
            premiumDrops.add(crops.create(drop.getType(), premium));
            if (premium == drop.getAmount()) {
                item.setItemStack(premiumDrops.removeLast());
            } else {
                drop.setAmount(drop.getAmount() - premium);
                item.setItemStack(drop);
            }
        }
        for (ItemStack premium : premiumDrops) {
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(0.5, 0.5, 0.5), premium);
        }
    }
}
