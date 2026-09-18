package org.craftcore.stellaria.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WorldSelectGuiTest {

    @Test
    void parentBackedFortyFiveWorldLayoutReservesBackSlot() {
        // Filling the back slot with a world prevents the player from returning to the parent menu.
        WorldSelectGui.Layout layout = WorldSelectGui.Layout.autoLayout(45, true);

        assertEquals(54, layout.inventorySize());
        assertEquals(53, layout.worldsPerPage());
        assertEquals(53, layout.backButtonSlot());
        assertNotEquals(layout.worldsPerPage() - 1, layout.backButtonSlot());
    }
}
