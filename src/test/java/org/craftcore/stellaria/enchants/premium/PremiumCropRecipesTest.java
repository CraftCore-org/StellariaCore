package org.craftcore.stellaria.enchants.premium;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PremiumCropRecipesTest {

    @Test
    void beetrootHasNoToNormalRecipeBecauseItWouldShadowRedDye() {
        assertFalse(PremiumCropRecipes.hasToNormalRecipe(Material.BEETROOT));
    }

    @Test
    void otherCropsHaveAToNormalRecipe() {
        assertTrue(PremiumCropRecipes.hasToNormalRecipe(Material.WHEAT));
        assertTrue(PremiumCropRecipes.hasToNormalRecipe(Material.CARROT));
        assertTrue(PremiumCropRecipes.hasToNormalRecipe(Material.POTATO));
        assertTrue(PremiumCropRecipes.hasToNormalRecipe(Material.NETHER_WART));
    }
}
