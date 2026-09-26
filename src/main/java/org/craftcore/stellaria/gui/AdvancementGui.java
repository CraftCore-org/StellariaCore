package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.AdvancementManager;
import org.craftcore.stellaria.managers.AdvancementStore;
import org.craftcore.stellaria.utils.AdvancementDefinitions;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.GuiItemUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** /advancements のトップ画面。タブごとの達成数と、全体の達成数・受け取った報酬の合計を出す。 */
public final class AdvancementGui extends Gui {

    private static final int[] TAB_SLOTS = {1, 2, 3, 4, 5, 6, 7, 10, 11, 12, 13, 14, 15, 16};
    private static final int SUMMARY_SLOT = 22;

    private final StellariaCore plugin;
    private final Map<Integer, String> tabBySlot = new HashMap<>();

    public AdvancementGui(StellariaCore plugin, Player player, @Nullable Gui parent) {
        super(27, FormatUtil.component(plugin.getConfigManager().getMessage("advancements.gui_title", player)), parent);
        this.plugin = plugin;
        populate(player);
    }

    private void populate(Player player) {
        AdvancementManager manager = plugin.getAdvancementManager();
        List<AdvancementDefinitions.Definition> all = manager.getParsed().definitions();
        int index = 0;
        for (AdvancementDefinitions.Tab tab : manager.getParsed().tabs().values()) {
            if (index >= TAB_SLOTS.length) {
                break;
            }
            List<AdvancementDefinitions.Definition> inTab = all.stream().filter(d -> d.tab().equals(tab.id())).toList();
            long done = inTab.stream().filter(d -> manager.isCompleted(player, d.id())).count();
            Material icon = Material.matchMaterial(tab.icon());
            int slot = TAB_SLOTS[index++];
            getInventory().setItem(slot, item(icon != null ? icon : Material.BOOK, FormatUtil.component(FormatUtil.color(tab.title())),
                    List.of(FormatUtil.component(FormatUtil.color(tab.description())),
                            message(player, "advancements.gui_tab_lore", "%done%", String.valueOf(done),
                                    "%total%", String.valueOf(inTab.size())))));
            tabBySlot.put(slot, tab.id());
        }
        long doneAll = all.stream().filter(d -> manager.isCompleted(player, d.id())).count();
        List<Component> summary = new ArrayList<>();
        for (String line : plugin.getConfigManager().getMessageList("advancements.gui_summary_lore")) {
            summary.add(FormatUtil.component(FormatUtil.text(player, line
                    .replace("%done%", String.valueOf(doneAll))
                    .replace("%total%", String.valueOf(all.size()))
                    .replace("%reward%", plugin.getEconomyManager().formatExact(AdvancementStore.rewardTotal(player.getUniqueId()))))));
        }
        getInventory().setItem(SUMMARY_SLOT, item(Material.NETHER_STAR, message(player, "advancements.gui_summary_name"), summary));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= getInventory().getSize()) {
            return;
        }
        if (handleBackButton(event, player)) {
            return;
        }
        String tab = tabBySlot.get(event.getRawSlot());
        if (tab != null) {
            new AdvancementCategoryGui(plugin, player, this, tab, 0).open(player);
        }
    }

    private Component message(Player player, String path, String... replacements) {
        String value = plugin.getConfigManager().getMessage(path, player);
        for (int i = 0; i < replacements.length; i += 2) {
            value = FormatUtil.replace(value, replacements[i], replacements[i + 1]);
        }
        return FormatUtil.component(value);
    }

    static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = GuiItemUtil.cleanIcon(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(GuiItemUtil.text(name));
        meta.lore(GuiItemUtil.lore(lore));
        item.setItemMeta(meta);
        return item;
    }
}
