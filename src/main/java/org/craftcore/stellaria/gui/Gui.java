package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * インベントリベースのGUI画面を作るための汎用フレームワーク。継承先はコンストラクタで
 * アイテムを並べ、必要に応じて {@link #onClick}/{@link #onClose} をオーバーライドする。
 * クリック・クローズイベントの購読・振り分けは {@link GuiListener} が一括で担当するので、
 * 継承先で {@code @EventHandler} を書く必要はない。
 *
 * 標準Bukkit Inventory APIのみを使用する（packeteventsは使わない — 通常のクリック式メニューは
 * 標準APIで十分に実現でき、パケット層を直接いじる必要が無いため）。
 *
 * <p>{@code parent} を渡すと、画面右下（既定は最終スロット。ページング矢印等と被る場合は
 * {@link #Gui(int, Component, Gui, int)} で位置をずらす）に「戻る」ボタンが自動で出る。
 * 継承先の {@link #onClick} の先頭で {@link #handleBackButton(InventoryClickEvent, Player)}
 * を呼び、{@code true} が返ったら以降の処理をスキップすること。コマンドから直接開いた場合
 * （{@code parent == null}）はボタン自体が出ないので、呼び出し側で分岐する必要はない。
 */
public abstract class Gui implements InventoryHolder {

    private final Inventory inventory;
    private final @Nullable Gui parent;
    private final int backButtonSlot;

    protected Gui(int size, Component title) {
        this(size, title, null);
    }

    protected Gui(int size, Component title, @Nullable Gui parent) {
        this(size, title, parent, size - 1);
    }

    protected Gui(int size, Component title, @Nullable Gui parent, int backButtonSlot) {
        this.inventory = Bukkit.createInventory(this, size, title);
        this.parent = parent;
        this.backButtonSlot = backButtonSlot;
        if (parent != null) {
            ItemStack item = new ItemStack(Material.ARROW);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text("← 戻る", NamedTextColor.GRAY).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
            inventory.setItem(backButtonSlot, item);
        }
    }

    @Override
    @NotNull
    public Inventory getInventory() {
        return inventory;
    }

    public boolean allowBottomShiftClick() {
        return false;
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

    /**
     * クリックされたスロットが戻るボタンなら親画面を開いて {@code true} を返す。
     * 継承先の {@link #onClick} 冒頭（{@code event.setCancelled(true)} の後）で呼ぶこと。
     */
    protected boolean handleBackButton(InventoryClickEvent event, Player player) {
        if (parent != null && event.getRawSlot() == backButtonSlot) {
            player.openInventory(parent.getInventory());
            return true;
        }
        return false;
    }

    /** この画面が閉じられた時に呼ばれる（{@link GuiListener} 経由）。デフォルトは何もしない。 */
    public void onClose(InventoryCloseEvent event) {
    }

    /** GUI上へドラッグされた時に呼ばれる。既定ではアイテム移動を禁止する。 */
    public void onDrag(InventoryDragEvent event) {
        event.setCancelled(true);
    }
}
