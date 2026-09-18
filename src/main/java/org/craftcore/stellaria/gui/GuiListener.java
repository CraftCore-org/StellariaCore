package org.craftcore.stellaria.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Set;

/**
 * {@link Gui} を継承した画面へのクリック・クローズイベントをまとめて振り分ける唯一のリスナー。
 * {@code getHolder()} が {@link Gui} のインスタンスでなければ（通常のチェスト・かまど等の場合）
 * 何もしない。
 */
public class GuiListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        InventoryHolder holder = topInventory.getHolder();
        if (holder instanceof Gui gui) {
            if (event.getClickedInventory() == topInventory) {
                gui.onClick(event);
                return;
            }

            if (event.getClickedInventory() != null
                    && shouldCancelBottomClick(event.isShiftClick(), event.getClick())) {

                if (!gui.allowBottomShiftClick()) {
                    event.setCancelled(true);
                    return;
                }

                // Shiftクリックを許可するGUIにはイベントも通知する
                gui.onClick(event);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (topInventory.getHolder() instanceof Gui gui
                && shouldCancelDrag(event.getRawSlots(), topInventory.getSize())) {
            gui.onDrag(event);
        }
    }

    static boolean shouldCancelBottomClick(boolean shiftClick, ClickType click) {
        return shiftClick || click == ClickType.DOUBLE_CLICK;
    }

    static boolean shouldCancelDrag(Set<Integer> rawSlots, int topInventorySize) {
        return rawSlots.stream().anyMatch(slot -> slot >= 0 && slot < topInventorySize);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof Gui gui) {
            gui.onClose(event);
        }
    }
}
