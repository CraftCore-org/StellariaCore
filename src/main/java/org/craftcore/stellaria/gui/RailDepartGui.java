package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.commands.RailCommand;
import org.craftcore.stellaria.rail.RailLineManager;
import org.craftcore.stellaria.rail.RailManager;
import org.craftcore.stellaria.utils.FormatUtil;

import java.util.List;

/**
 * /rail depart gui、またはレール上のトロッコに乗車した瞬間（RailListener#onVehicleEnter）に開く
 * 目的地選択画面。表示するのは今乗っているトロッコの位置から実際にレールをたどって発車できる駅だけ
 * （一方通行で逆走になる駅は除外済み）。所属路線・方向・おおよその距離をLoreに出し、
 * 路線・駅が増えても「どれがどこ？」にならないようにする。
 * 実際の発車処理はRailCommand#departToStationに委譲する（/rail depart <駅名>と同じ経路）。
 */
public final class RailDepartGui extends Gui {

    private static final int CONTENT_SLOTS = 27;
    private static final int PREVIOUS_SLOT = 27;
    private static final int PAGE_SLOT = 31;
    private static final int NEXT_SLOT = 35;
    private static final int BACK_BUTTON_SLOT = 30;

    private final StellariaCore plugin;
    private final RailCommand railCommand;
    private final Minecart cart;
    private final List<RailManager.ReachableStation> stations;
    private final int page;
    private final int maxPage;

    public RailDepartGui(StellariaCore plugin, RailCommand railCommand, Minecart cart) {
        this(plugin, railCommand, cart, plugin.getRailManager().findReachableStations(cart), 0);
    }

    private RailDepartGui(StellariaCore plugin, RailCommand railCommand, Minecart cart, List<RailManager.ReachableStation> stations, int page) {
        super(36, title(plugin), null, BACK_BUTTON_SLOT);
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
            RailManager.ReachableStation entry = stations.get(first + slot);
            getInventory().setItem(slot, item(Material.MINECART, Component.text(entry.station().name(), NamedTextColor.WHITE), List.of(
                    lineLore(entry.station().name()),
                    message("rail.gui_entry_direction", "%direction%", directionLabel(entry.direction())),
                    message("rail.gui_entry_distance", "%distance%", String.valueOf(entry.approxDistanceBlocks())),
                    message("rail.gui_entry_lore", "%name%", entry.station().name())
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

    private Component lineLore(String stationName) {
        RailLineManager.RailLine line = plugin.getRailLineManager().findLineForStation(stationName);
        if (line == null) {
            return message("rail.gui_entry_no_line");
        }
        return message("rail.gui_entry_line", "%line%", line.name());
    }

    private static String directionLabel(BlockFace direction) {
        return switch (direction) {
            case NORTH -> "北";
            case SOUTH -> "南";
            case EAST -> "東";
            case WEST -> "西";
            default -> direction.name();
        };
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
                railCommand.departToStation(player, cart, stations.get(index).station());
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
