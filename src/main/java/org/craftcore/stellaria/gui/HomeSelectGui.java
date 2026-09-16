package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.commands.HomeCommand;
import org.craftcore.stellaria.utils.FormatUtil;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** /homes gui で開く、実行者自身のホーム一覧選択画面。 */
public final class HomeSelectGui extends Gui {

    private static final int CONTENT_SLOTS = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int PAGE_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final int BACK_BUTTON_SLOT = 48;

    private final StellariaCore plugin;
    private final HomeCommand homeCommand;
    private final List<String> homes;
    private final int page;
    private final int maxPage;
    private final @Nullable Gui parent;

    public HomeSelectGui(StellariaCore plugin, HomeCommand homeCommand, Player player) {
        this(plugin, homeCommand, null, plugin.getHomeManager().listNames(player.getUniqueId()), 0);
    }

    /** メニュー画面から開く場合、戻るボタンを出すために親画面を渡す。 */
    public HomeSelectGui(StellariaCore plugin, HomeCommand homeCommand, Player player, @Nullable Gui parent) {
        this(plugin, homeCommand, parent, plugin.getHomeManager().listNames(player.getUniqueId()), 0);
    }

    private HomeSelectGui(StellariaCore plugin, HomeCommand homeCommand, @Nullable Gui parent, List<String> homes, int page) {
        super(54, title(plugin), parent, BACK_BUTTON_SLOT);
        this.plugin = plugin;
        this.homeCommand = homeCommand;
        this.parent = parent;
        this.homes = homes;
        this.maxPage = Math.max(0, (homes.size() - 1) / CONTENT_SLOTS);
        this.page = Math.clamp(page, 0, maxPage);
        populate();
    }

    private static Component title(StellariaCore plugin) {
        return FormatUtil.component(plugin.getConfigManager().getMessage("home.gui_title", null));
    }

    private void populate() {
        int first = page * CONTENT_SLOTS;
        for (int slot = 0; slot < CONTENT_SLOTS && first + slot < homes.size(); slot++) {
            getInventory().setItem(slot, item(Material.RED_BED, Component.text(homes.get(first + slot), NamedTextColor.WHITE),
                    List.of(message("home.gui_entry_lore"))));
        }

        if (homes.isEmpty()) {
            getInventory().setItem(PAGE_SLOT, item(Material.BARRIER, message("home.gui_empty"), List.of()));
            return;
        }

        if (page > 0) {
            getInventory().setItem(PREVIOUS_SLOT, item(Material.ARROW, message("home.gui_previous_page"), List.of()));
        }
        getInventory().setItem(PAGE_SLOT, item(Material.PAPER,
                message("home.gui_page", "%page%", String.valueOf(page + 1), "%max_page%", String.valueOf(maxPage + 1)), List.of()));
        if (page < maxPage) {
            getInventory().setItem(NEXT_SLOT, item(Material.ARROW, message("home.gui_next_page"), List.of()));
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
            new HomeSelectGui(plugin, homeCommand, parent, homes, page - 1).open(player);
            return;
        }
        if (slot == NEXT_SLOT && page < maxPage) {
            new HomeSelectGui(plugin, homeCommand, parent, homes, page + 1).open(player);
            return;
        }
        if (slot < CONTENT_SLOTS) {
            int index = page * CONTENT_SLOTS + slot;
            if (index < homes.size()) {
                player.closeInventory();
                homeCommand.teleportToHome(player, homes.get(index));
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
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
