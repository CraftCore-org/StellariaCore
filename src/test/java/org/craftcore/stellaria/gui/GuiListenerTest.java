package org.craftcore.stellaria.gui;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiListenerTest {

    @Test
    void permitsOrdinaryBottomInventoryClicks() {
        assertFalse(GuiListener.shouldCancelBottomClick(false, ClickType.LEFT));
    }

    @Test
    void blocksBottomInventoryTransfersThatCouldTouchTheGui() {
        assertTrue(GuiListener.shouldCancelBottomClick(true, ClickType.LEFT));
        assertTrue(GuiListener.shouldCancelBottomClick(false, ClickType.DOUBLE_CLICK));
    }

    @Test
    void blocksOnlyDragsThatEnterTopInventory() {
        assertTrue(GuiListener.shouldCancelDrag(Set.of(4, 60), 54));
        assertFalse(GuiListener.shouldCancelDrag(Set.of(54, 60), 54));
    }
}
