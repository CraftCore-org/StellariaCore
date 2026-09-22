package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.rail.RailLineManager;
import org.craftcore.stellaria.rail.RailStationManager;
import org.craftcore.stellaria.utils.FormatUtil;

import java.util.List;

/** /rail station gui で開く、駅一覧・削除画面（stellaria.rail.admin）。 */
public final class RailStationAdminGui extends Gui {

    private static final int CONTENT_SLOTS = 27;
    private static final int PREVIOUS_SLOT = 27;
    private static final int PAGE_SLOT = 31;
    private static final int NEXT_SLOT = 35;

    private final StellariaCore plugin;
    private final List<RailStationManager.Station> stations;
    private final int page;
    private final int maxPage;

    public RailStationAdminGui(StellariaCore plugin) {
        this(plugin, plugin.getRailStationManager().listAll(), 0);
    }

    private RailStationAdminGui(StellariaCore plugin, List<RailStationManager.Station> stations, int page) {
        super(36, title(plugin));
        this.plugin = plugin;
        this.stations = stations;
        this.maxPage = Math.max(0, (stations.size() - 1) / CONTENT_SLOTS);
        this.page = Math.clamp(page, 0, maxPage);
        populate();
    }

    private static Component title(StellariaCore plugin) {
        return FormatUtil.component(plugin.getConfigManager().getMessage("rail.station_gui_title", null));
    }

    private void populate() {
        int first = page * CONTENT_SLOTS;
        for (int slot = 0; slot < CONTENT_SLOTS && first + slot < stations.size(); slot++) {
            RailStationManager.Station station = stations.get(first + slot);
            RailLineManager.RailLine line = plugin.getRailLineManager().findLineForStation(station.name());
            getInventory().setItem(slot, item(Material.RAIL, FormatUtil.component(station.name()), List.of(
                    message("rail.station_gui_entry_world", "%world%", station.world()),
                    line != null
                            ? message("rail.station_gui_entry_line", "%line%", line.name())
                            : message("rail.station_gui_entry_no_line"),
                    line != null ? message("rail.station_gui_entry_delete_blocked") : message("rail.station_gui_entry_delete")
            )));
        }

        if (stations.isEmpty()) {
            getInventory().setItem(PAGE_SLOT, item(Material.BARRIER, message("rail.station_list_empty"), List.of()));
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
            new RailStationAdminGui(plugin, stations, page - 1).open(player);
            return;
        }
        if (slot == NEXT_SLOT && page < maxPage) {
            new RailStationAdminGui(plugin, stations, page + 1).open(player);
            return;
        }
        if (slot < CONTENT_SLOTS) {
            int index = page * CONTENT_SLOTS + slot;
            if (index < stations.size()) {
                requestDelete(player, stations.get(index));
            }
        }
    }

    /** 所属路線があれば削除確認すら出さず拒否する（幽霊駅防止。RailStationManager#removeの拒否と対になる）。 */
    private void requestDelete(Player player, RailStationManager.Station station) {
        RailLineManager.RailLine line = plugin.getRailLineManager().findLineForStation(station.name());
        if (line != null) {
            player.closeInventory();
            String message = plugin.getConfigManager().getMessage("rail.station_remove_belongs_to_line", player);
            message = FormatUtil.replace(message, "%name%", FormatUtil.color(station.name()));
            message = FormatUtil.replace(message, "%line%", line.name());
            player.sendMessage(message);
            return;
        }
        confirmDelete(player, station);
    }

    private void confirmDelete(Player player, RailStationManager.Station station) {
        new ConfirmGui(
                message("rail.station_gui_confirm_title"),
                message("rail.station_gui_confirm_description", "%name%", station.name()),
                message("gui.confirm"),
                message("gui.cancel"),
                () -> {
                    plugin.getRailStationManager().remove(station.name());
                    player.sendMessage(FormatUtil.replace(
                            plugin.getConfigManager().getMessage("rail.station_removed", player), "%name%", FormatUtil.color(station.name())));
                    new RailStationAdminGui(plugin).open(player);
                },
                () -> new RailStationAdminGui(plugin, stations, page).open(player)
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
