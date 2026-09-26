package org.craftcore.stellaria.enchants;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlideMathTest {

    @Test
    void apexIsReachedOnceTheHeightStopsIncreasing() {
        assertFalse(GlideMath.reachedApex(70.0, 70.8));
        assertTrue(GlideMath.reachedApex(75.0, 75.0));
        assertTrue(GlideMath.reachedApex(75.0, 74.9));
    }

    @Test
    void boostAddsToTheMeasuredMotionInsteadOfReplacingIt() {
        // サーバー側の速度ではなく、実際の 1tick の移動量に上乗せする
        Vector motion = new Vector(1.0, -0.1, 0.0);
        Vector look = new Vector(0.0, 0.0, 2.0); // 正規化されていない向きでも strength の長さで足す
        Vector boosted = GlideMath.boostedVelocity(motion, look, 0.6);
        assertEquals(1.0, boosted.getX(), 1e-9);
        assertEquals(-0.1, boosted.getY(), 1e-9);
        assertEquals(0.6, boosted.getZ(), 1e-9);
    }

    @Test
    void boostDoesNotMutateItsInputs() {
        Vector motion = new Vector(1.0, 0.0, 0.0);
        Vector look = new Vector(0.0, 0.0, 1.0);
        GlideMath.boostedVelocity(motion, look, 0.6);
        assertEquals(new Vector(1.0, 0.0, 0.0), motion);
        assertEquals(new Vector(0.0, 0.0, 1.0), look);
    }
}
