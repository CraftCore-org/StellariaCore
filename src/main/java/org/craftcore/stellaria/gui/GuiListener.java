package org.craftcore.stellaria.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * {@link Gui} を継承した画面へのクリック・クローズイベントをまとめて振り分ける唯一のリスナー。
 * {@code getHolder()} が {@link Gui} のインスタンスでなければ（通常のチェスト・かまど等の場合）
 * 何もしない。
 */
public class GuiListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof Gui gui) {
            gui.onClick(event);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof Gui gui) {
            gui.onClose(event);
        }
    }
}
