package org.craftcore.stellaria.enchants.premium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PremiumCraftRulesTest {

    @Test
    void recipesWithoutPremiumCropsAreUntouched() {
        assertEquals(PremiumCraftRules.Verdict.ALLOW, PremiumCraftRules.judge(0, 3));
    }

    @Test
    void aLonePremiumCropConvertsBackToNormalCrops() {
        assertEquals(PremiumCraftRules.Verdict.CONVERT_TO_NORMAL, PremiumCraftRules.judge(1, 1));
    }

    @Test
    void premiumCropsMixedIntoAnyOtherRecipeAreBlocked() {
        assertEquals(PremiumCraftRules.Verdict.BLOCK, PremiumCraftRules.judge(1, 2));
        assertEquals(PremiumCraftRules.Verdict.BLOCK, PremiumCraftRules.judge(3, 3));
    }
}
