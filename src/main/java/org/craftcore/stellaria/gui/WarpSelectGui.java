package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.commands.WarpCommand;
import org.craftcore.stellaria.managers.WarpManager;
import org.craftcore.stellaria.utils.FormatUtil;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** /warps gui で開く、サーバーのワープ一覧選択画面。 */
public final class WarpSelectGui extends Gui {

    private static final int CONTENT_SLOTS = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int PAGE_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final int BACK_BUTTON_SLOT = 48;

    private final StellariaCore plugin;
    private final WarpCommand warpCommand;
    private final List<WarpManager.WarpEntry> warps;
    private final int page;
    private final int maxPage;
    private final @Nullable Gui parent;

    public WarpSelectGui(StellariaCore plugin, WarpCommand warpCommand) {
        this(plugin, warpCommand, null, plugin.getWarpManager().listAll(), 0);
    }

    /** メニュー画面から開く場合、戻るボタンを出すために親画面を渡す。 */
    public WarpSelectGui(StellariaCore plugin, WarpCommand warpCommand, @Nullable Gui parent) {
        this(plugin, warpCommand, parent, plugin.getWarpManager().listAll(), 0);
    }

    private WarpSelectGui(StellariaCore plugin, WarpCommand warpCommand, @Nullable Gui parent, List<WarpManager.WarpEntry> warps, int page) {
        super(54, title(plugin), parent, BACK_BUTTON_SLOT);
        this.plugin = plugin;
        this.warpCommand = warpCommand;
        this.parent = parent;
        this.warps = warps;
        this.maxPage = Math.max(0, (warps.size() - 1) / CONTENT_SLOTS);
        this.page = Math.clamp(page, 0, maxPage);
        populate();
    }

    private static Component title(StellariaCore plugin) {
        return FormatUtil.component(plugin.getConfigManager().getMessage("warp.gui_title", null));
    }

    private void populate() {
        int first = page * CONTENT_SLOTS;
        for (int slot = 0; slot < CONTENT_SLOTS && first + slot < warps.size(); slot++) {
            WarpManager.WarpEntry warp = warps.get(first + slot);
            String owner = warp.ownerName() != null ? warp.ownerName() : "?";
            getInventory().setItem(slot, item(Material.ENDER_PEARL, Component.text(warp.name(), NamedTextColor.WHITE), List.of(
                    message("warp.gui_entry_owner", "%owner%", owner),
                    message("warp.gui_entry_lore", "%name%", warp.name())
            )));
        }

        if (warps.isEmpty()) {
            getInventory().setItem(PAGE_SLOT, item(Material.BARRIER, message("warp.gui_empty"), List.of()));
            return;
        }

        if (page > 0) {
            getInventory().setItem(PREVIOUS_SLOT, item(Material.ARROW, message("warp.gui_previous_page"), List.of()));
        }
        getInventory().setItem(PAGE_SLOT, item(Material.PAPER,
                message("warp.gui_page", "%page%", String.valueOf(page + 1), "%max_page%", String.valueOf(maxPage + 1)), List.of()));
        if (page < maxPage) {
            getInventory().setItem(NEXT_SLOT, item(Material.ARROW, message("warp.gui_next_page"), List.of()));
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= getInventory().getSize()) {
            return;
        }
        if (handleBackButton(event, player)) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot == PREVIOUS_SLOT && page > 0) {
            new WarpSelectGui(plugin, warpCommand, parent, warps, page - 1).open(player);
            return;
        }
        if (slot == NEXT_SLOT && page < maxPage) {
            new WarpSelectGui(plugin, warpCommand, parent, warps, page + 1).open(player);
            return;
        }
        if (slot < CONTENT_SLOTS) {
            int index = page * CONTENT_SLOTS + slot;
            if (index < warps.size()) {
                player.closeInventory();
                warpCommand.teleportToWarp(player, warps.get(index).name());
            }
        }
    }

    private Component message(String path, String... replacements) {
        String value = plugin.getConfigManager().getMessage(path, null);
        for (int i = 0; i < replacements.length; i += 2) {
            value = FormatUtil.replace(value, replacements[i], replacements[i + 1]);
        }
        return FormatUtil.component(value);
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = ItemStack.of(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(org.craftcore.stellaria.utils.GuiItemUtil.text(name));
        meta.lore(org.craftcore.stellaria.utils.GuiItemUtil.lore(lore));
        item.setItemMeta(meta);
        return item;
    }
}
