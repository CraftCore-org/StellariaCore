package org.craftcore.stellaria.enchants.premium;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;

/**
 * 上質作物の変換レシピ。
 * - 通常 → 上質: 縦一列に 3 個（横一列だと小麦 3 個のパンと衝突するため）。
 * - 上質 → 通常: 作物 1 個の不定形レシピ。実際の結果は PremiumCropGuardListener が上質作物のときだけ 3 個に差し替え、
 *   通常作物 1 個のときは結果を消す。ビートルートはバニラの「ビートルート → 赤色の染料」と衝突するため登録せず、
 *   赤色の染料のレシピに乗って差し替えだけで逆変換する。
 */
public final class PremiumCropRecipes {

    private final Plugin plugin;
    private final PremiumCrops crops;
    private final Set<NamespacedKey> toPremiumKeys = new HashSet<>();
    private final Set<NamespacedKey> toNormalKeys = new HashSet<>();

    public PremiumCropRecipes(Plugin plugin, PremiumCrops crops) {
        this.plugin = plugin;
        this.crops = crops;
    }

    static boolean hasToNormalRecipe(Material crop) {
        return crop != Material.BEETROOT;
    }

    public void register() {
        for (Material crop : PremiumCrops.CROPS) {
            String name = crop.name().toLowerCase();

            NamespacedKey toPremiumKey = new NamespacedKey(plugin, "premium_" + name);
            ShapedRecipe toPremium = new ShapedRecipe(toPremiumKey, crops.create(crop, 1));
            toPremium.shape("C", "C", "C");
            toPremium.setIngredient('C', crop);
            Bukkit.removeRecipe(toPremiumKey);
            Bukkit.addRecipe(toPremium);
            toPremiumKeys.add(toPremiumKey);

            if (hasToNormalRecipe(crop)) {
                NamespacedKey toNormalKey = new NamespacedKey(plugin, "unpremium_" + name);
                ShapelessRecipe toNormal = new ShapelessRecipe(toNormalKey,
                        new ItemStack(crop, PremiumCrops.CONVERSION_RATIO));
                toNormal.addIngredient(crop);
                Bukkit.removeRecipe(toNormalKey);
                Bukkit.addRecipe(toNormal);
                toNormalKeys.add(toNormalKey);
            }
        }
    }

    public boolean isToPremium(NamespacedKey key) {
        return toPremiumKeys.contains(key);
    }

    public boolean isToNormal(NamespacedKey key) {
        return toNormalKeys.contains(key);
    }
}
