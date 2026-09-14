package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * インベントリベースのGUI画面を作るための汎用フレームワーク。継承先はコンストラクタで
 * アイテムを並べ、必要に応じて {@link #onClick}/{@link #onClose} をオーバーライドする。
 * クリック・クローズイベントの購読・振り分けは {@link GuiListener} が一括で担当するので、
 * 継承先で {@code @EventHandler} を書く必要はない。
 *
 * 標準Bukkit Inventory APIのみを使用する（packeteventsは使わない — 通常のクリック式メニューは
 * 標準APIで十分に実現でき、パケット層を直接いじる必要が無いため）。
 */
public abstract class Gui implements InventoryHolder {

    private final Inventory inventory;

    protected Gui(int size, Component title) {
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    @Override
    @NotNull
    public Inventory getInventory() {
        return inventory;
    }

    /** この画面をプレイヤーに開く。 */
    public void open(Player player) {
        player.openInventory(inventory);
    }

    /**
     * この画面内でクリックされた時に呼ばれる（{@link GuiListener} 経由）。
     * デフォルトは何もしない。アイテムを持ち出されたくない場合は継承先で
     * {@code event.setCancelled(true)} を呼ぶこと。
     */
    public void onClick(InventoryClickEvent event) {
    }

    /** この画面が閉じられた時に呼ばれる（{@link GuiListener} 経由）。デフォルトは何もしない。 */
    public void onClose(InventoryCloseEvent event) {
    }
}
