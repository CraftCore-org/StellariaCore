package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.commands.RailCommand;
import org.craftcore.stellaria.rail.RailStationManager;
import org.craftcore.stellaria.utils.FormatUtil;

import java.util.List;

/**
 * /rail depart gui で開く目的地選択画面。表示するのは今乗っているトロッコの位置から実際に
 * レールをたどって発車できる駅だけ（一方通行で逆走になる駅は除外済み）。
 * 実際の発車処理はRailCommand#departToStationに委譲する（/rail depart <駅名>と同じ経路）。
 */
public final class RailDepartGui extends Gui {

    private static final int CONTENT_SLOTS = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int PAGE_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final int BACK_BUTTON_SLOT = 48;

    private final StellariaCore plugin;
    private final RailCommand railCommand;
    private final Minecart cart;
    private final List<RailStationManager.Station> stations;
    private final int page;
    private final int maxPage;

    public RailDepartGui(StellariaCore plugin, RailCommand railCommand, Minecart cart) {
        this(plugin, railCommand, cart, plugin.getRailManager().findReachableStations(cart), 0);
    }

    private RailDepartGui(StellariaCore plugin, RailCommand railCommand, Minecart cart, List<RailStationManager.Station> stations, int page) {
        super(54, title(plugin), null, BACK_BUTTON_SLOT);
        this.plugin = plugin;
        this.railCommand = railCommand;
        this.cart = cart;
        this.stations = stations;
        this.maxPage = Math.max(0, (stations.size() - 1) / CONTENT_SLOTS);
        this.page = Math.clamp(page, 0, maxPage);
        populate();
    }

    private static Component title(StellariaCore plugin) {
        return FormatUtil.component(plugin.getConfigManager().getMessage("rail.gui_title", null));
    }

    private void populate() {
        int first = page * CONTENT_SLOTS;
        for (int slot = 0; slot < CONTENT_SLOTS && first + slot < stations.size(); slot++) {
            RailStationManager.Station station = stations.get(first + slot);
            getInventory().setItem(slot, item(Material.MINECART, Component.text(station.name(), NamedTextColor.WHITE), List.of(
                    message("rail.gui_entry_lore", "%name%", station.name())
            )));
        }

        if (stations.isEmpty()) {
            getInventory().setItem(PAGE_SLOT, item(Material.BARRIER, message("rail.gui_empty"), List.of()));
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
            new RailDepartGui(plugin, railCommand, cart, stations, page - 1).open(player);
            return;
        }
        if (slot == NEXT_SLOT && page < maxPage) {
            new RailDepartGui(plugin, railCommand, cart, stations, page + 1).open(player);
            return;
        }
        if (slot < CONTENT_SLOTS) {
            int index = page * CONTENT_SLOTS + slot;
            if (index < stations.size()) {
                player.closeInventory();
                railCommand.departToStation(player, cart, stations.get(index));
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
