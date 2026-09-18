package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

/** 任意の処理を確認・キャンセルできる、機能固有の知識を持たない確認画面。 */
public class ConfirmGui extends Gui {

    private static final int CONFIRM_SLOT = 11;
    private static final int CANCEL_SLOT = 15;

    private final Runnable onConfirm;
    private final @Nullable Runnable onCancel;

    public ConfirmGui(Component title, Component description, Runnable onConfirm, @Nullable Runnable onCancel) {
        super(27, title);
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;

        getInventory().setItem(13, item(Material.PAPER, description));
        getInventory().setItem(CONFIRM_SLOT, item(Material.LIME_DYE, Component.text("確認", NamedTextColor.GREEN)));
        getInventory().setItem(CANCEL_SLOT, item(Material.RED_DYE, Component.text("キャンセル", NamedTextColor.RED)));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= getInventory().getSize()) {
            return;
        }

        if (event.getRawSlot() == CONFIRM_SLOT) {
            player.closeInventory();
            onConfirm.run();
        } else if (event.getRawSlot() == CANCEL_SLOT) {
            player.closeInventory();
            if (onCancel != null) {
                onCancel.run();
            }
        }
    }

    private ItemStack item(Material material, Component displayName) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(org.craftcore.stellaria.utils.GuiItemUtil.text(displayName));
        item.setItemMeta(meta);
        return item;
    }
}
