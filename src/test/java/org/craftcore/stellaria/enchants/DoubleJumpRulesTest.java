package org.craftcore.stellaria.enchants;

import org.craftcore.stellaria.enchants.DoubleJumpRules.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoubleJumpRulesTest {

    private static State airborne() {
        return new State(false, true, false, false, false, false, false, false, 20);
    }

    @Test
    void levelOneAllowsOneAirJumpAndLevelTwoAllowsTwo() {
        assertTrue(DoubleJumpRules.canAirJump(airborne(), 0, 1, 7));
        assertFalse(DoubleJumpRules.canAirJump(airborne(), 1, 1, 7));
        assertTrue(DoubleJumpRules.canAirJump(airborne(), 1, 2, 7));
        assertFalse(DoubleJumpRules.canAirJump(airborne(), 2, 2, 7));
    }

    @Test
    void groundJumpIsLeftToVanilla() {
        State onGround = new State(true, true, false, false, false, false, false, false, 20);
        assertFalse(DoubleJumpRules.canAirJump(onGround, 0, 2, 7));
    }

    @Test
    void elytraTakesPriorityOverDoubleJump() {
        State wearingElytra = new State(false, true, false, false, false, false, true, false, 20);
        assertFalse(DoubleJumpRules.canAirJump(wearingElytra, 0, 2, 7));
    }

    @Test
    void blockedWhileFlyingGlidingSwimmingClimbingOrInCreative() {
        assertFalse(DoubleJumpRules.canAirJump(new State(false, false, false, false, false, false, false, false, 20), 0, 2, 7));
        assertFalse(DoubleJumpRules.canAirJump(new State(false, true, true, false, false, false, false, false, 20), 0, 2, 7));
        assertFalse(DoubleJumpRules.canAirJump(new State(false, true, false, true, false, false, false, false, 20), 0, 2, 7));
        assertFalse(DoubleJumpRules.canAirJump(new State(false, true, false, false, true, false, false, false, 20), 0, 2, 7));
        assertFalse(DoubleJumpRules.canAirJump(new State(false, true, false, false, false, true, false, false, 20), 0, 2, 7));
    }

    @Test
    void blockedWhileRidingAVehicleOrMount() {
        State riding = new State(false, true, false, false, false, false, false, true, 20);
        assertFalse(DoubleJumpRules.canAirJump(riding, 0, 2, 7));
    }

    @Test
    void blockedWhenHungry() {
        State hungry = new State(false, true, false, false, false, false, false, false, 6);
        assertFalse(DoubleJumpRules.canAirJump(hungry, 0, 2, 7));
    }
}
