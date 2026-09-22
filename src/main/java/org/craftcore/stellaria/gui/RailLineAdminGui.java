package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.rail.RailLineManager;
import org.craftcore.stellaria.utils.FormatUtil;

import java.util.List;

/**
 * /rail line gui で開く、路線一覧・削除画面（stellaria.rail.admin）。
 * 路線の作成（駅の並び順指定が必要）はGUIの一覧選択と相性が悪いため、
 * /rail line create コマンドのみで行う想定（このGUIは閲覧・削除専用）。
 */
public final class RailLineAdminGui extends Gui {

    private static final int CONTENT_SLOTS = 27;
    private static final int PREVIOUS_SLOT = 27;
    private static final int PAGE_SLOT = 31;
    private static final int NEXT_SLOT = 35;

    private final StellariaCore plugin;
    private final List<RailLineManager.RailLine> lines;
    private final int page;
    private final int maxPage;

    public RailLineAdminGui(StellariaCore plugin) {
        this(plugin, plugin.getRailLineManager().listAll(), 0);
    }

    private RailLineAdminGui(StellariaCore plugin, List<RailLineManager.RailLine> lines, int page) {
        super(36, title(plugin));
        this.plugin = plugin;
        this.lines = lines;
        this.maxPage = Math.max(0, (lines.size() - 1) / CONTENT_SLOTS);
        this.page = Math.clamp(page, 0, maxPage);
        populate();
    }

    private static Component title(StellariaCore plugin) {
        return FormatUtil.component(plugin.getConfigManager().getMessage("rail.line_gui_title", null));
    }

    private void populate() {
        int first = page * CONTENT_SLOTS;
        for (int slot = 0; slot < CONTENT_SLOTS && first + slot < lines.size(); slot++) {
            RailLineManager.RailLine line = lines.get(first + slot);
            Material material = line.oneWay() ? Material.RED_CONCRETE : Material.LIME_CONCRETE;
            String modeLabel = plugin.getConfigManager().getMessage(
                    line.oneWay() ? "rail.line_mode_oneway" : "rail.line_mode_twoway", null);
            getInventory().setItem(slot, item(material, Component.text(line.name(), NamedTextColor.WHITE), List.of(
                    message("rail.line_gui_entry_mode", "%mode%", modeLabel),
                    message("rail.line_gui_entry_stations", "%stations%", String.join(" → ", line.stationNamesInOrder())),
                    message("rail.line_gui_entry_delete")
            )));
        }

        if (lines.isEmpty()) {
            getInventory().setItem(PAGE_SLOT, item(Material.BARRIER, message("rail.line_list_empty"), List.of()));
            return;
        }

        if (page > 0) {
            getInventory().setItem(PREVIOUS_SLOT, item(Material.ARROW, message("rail.gui_previous_page"), List.of()));
        }
        getInventory().setItem(PAGE_SLOT, item(Material.PAPER,
                message("rail.gui_page", "%page%", String.valueOf(page + 1), "%max_page%", String.valueOf(maxPage + 1)), List.of()));
        if (page < maxPage) {
            getInventory().setItem(NEXT_SLOT, item(Material.ARROW, message("rail.gui_next_page"), List.of()));
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= getInventory().getSize()) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot == PREVIOUS_SLOT && page > 0) {
            new RailLineAdminGui(plugin, lines, page - 1).open(player);
            return;
        }
        if (slot == NEXT_SLOT && page < maxPage) {
            new RailLineAdminGui(plugin, lines, page + 1).open(player);
            return;
        }
        if (slot < CONTENT_SLOTS) {
            int index = page * CONTENT_SLOTS + slot;
            if (index < lines.size()) {
                confirmDelete(player, lines.get(index));
            }
        }
    }

    private void confirmDelete(Player player, RailLineManager.RailLine line) {
        new ConfirmGui(
                message("rail.line_gui_confirm_title"),
                message("rail.line_gui_confirm_description", "%name%", line.name()),
                message("gui.confirm"),
                message("gui.cancel"),
                () -> {
                    plugin.getRailLineManager().remove(line.name());
                    player.sendMessage(FormatUtil.replace(
                            plugin.getConfigManager().getMessage("rail.line_removed", player), "%name%", line.name()));
                    new RailLineAdminGui(plugin).open(player);
                },
                () -> new RailLineAdminGui(plugin, lines, page).open(player)
        ).open(player);
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
