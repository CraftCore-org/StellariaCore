package org.craftcore.stellaria.enchants;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.craftcore.stellaria.utils.OreUtil;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 自動精錬: 鉱石を掘ったときのドロップを、かまどレシピの精錬結果に置き換える。
 * 幸運で増えた分もドロップに含まれるため、そのまま精錬される。/mine の一括採掘も player.breakBlock() 経由で
 * 同じイベントが発火するため対象になる。シルクタッチとは StellariaEnchants 側で排他にしている。
 */
public final class SmeltingListener implements Listener {

    private final CustomEnchantRegistry registry;
    private final Map<Material, FurnaceRecipe> recipesByInput = new EnumMap<>(Material.class);

    public SmeltingListener(CustomEnchantRegistry registry) {
        this.registry = registry;
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            if (iterator.next() instanceof FurnaceRecipe recipe
                    && recipe.getInputChoice() instanceof RecipeChoice.MaterialChoice choice) {
                for (Material input : choice.getChoices()) {
                    if (isSmeltTarget(input)) {
                        recipesByInput.putIfAbsent(input, recipe);
                    }
                }
            }
        }
    }

    /** 鉱石・原石系だけを精錬の対象にする（丸石→石などは変換しない）。 */
    static boolean isSmeltTarget(Material material) {
        return material == Material.RAW_IRON
                || material == Material.RAW_GOLD
                || material == Material.RAW_COPPER
                || OreUtil.isOre(material);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!OreUtil.isOre(event.getBlockState().getType())) {
            return;
        }
        Player player = event.getPlayer();
        if (registry.level(player.getInventory().getItemInMainHand(), CustomEnchant.SMELTING) <= 0) {
            return;
        }

        double experience = 0;
        for (Item item : event.getItems()) {
            ItemStack drop = item.getItemStack();
            FurnaceRecipe recipe = recipesByInput.get(drop.getType());
            if (recipe == null) {
                continue;
            }
            ItemStack result = recipe.getResult().clone();
            result.setAmount(drop.getAmount() * result.getAmount());
            item.setItemStack(result);
            experience += recipe.getExperience() * drop.getAmount();
        }

        int orbs = EnchantMath.roundExperience(experience, ThreadLocalRandom.current()::nextDouble);
        if (orbs > 0) {
            player.giveExp(orbs);
        }
    }
}
