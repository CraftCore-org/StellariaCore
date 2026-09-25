package org.craftcore.stellaria.enchants;

/**
 * 二段跳びを発動できるかの判定。レベル I で空中 1 回（地面を含め合計 2 回）、レベル II で空中 2 回（合計 3 回）。
 * エリトラ着用中は、空中でのジャンプキーがバニラの滑空開始と重なるため発動しない（滑空を優先）。
 */
public final class DoubleJumpRules {

    public record State(
            boolean onGround,
            boolean survivalLike,
            boolean flying,
            boolean gliding,
            boolean inWater,
            boolean climbing,
            boolean wearingElytra,
            int foodLevel
    ) {
    }

    private DoubleJumpRules() {
    }

    public static boolean canAirJump(State state, int airJumpsUsed, int level, int minFoodLevel) {
        return level > 0
                && airJumpsUsed < level
                && !state.onGround()
                && state.survivalLike()
                && !state.flying()
                && !state.gliding()
                && !state.inWater()
                && !state.climbing()
                && !state.wearingElytra()
                && EnchantMath.hasEnoughFood(state.foodLevel(), minFoodLevel);
    }
}
