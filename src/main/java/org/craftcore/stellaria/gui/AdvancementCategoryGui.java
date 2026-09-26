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
import org.craftcore.stellaria.utils.AdvancementRules;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.RankingFormat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** /advancements のカテゴリ画面。タブ内の進捗を定義順に並べ、未達成は進み具合を出す。 */
public final class AdvancementCategoryGui extends Gui {

    private static final int CONTENT_SLOTS = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int BACK_BUTTON_SLOT = 48;
    private static final int PAGE_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.of("Asia/Tokyo"));

    private final StellariaCore plugin;
    private final Gui parent;
    private final String tabId;
    private final int page;
    private final int maxPage;

    public AdvancementCategoryGui(StellariaCore plugin, Player player, Gui parent, String tabId, int page) {
        super(54, title(plugin, player, tabId), parent, BACK_BUTTON_SLOT);
        this.plugin = plugin;
        this.parent = parent;
        this.tabId = tabId;
        List<AdvancementDefinitions.Definition> defs = definitions(plugin, tabId);
        this.maxPage = Math.max(0, (defs.size() - 1) / CONTENT_SLOTS);
        this.page = Math.clamp(page, 0, maxPage);
        populate(player, defs);
    }

    private static List<AdvancementDefinitions.Definition> definitions(StellariaCore plugin, String tabId) {
        return plugin.getAdvancementManager().getParsed().definitions().stream()
                .filter(d -> d.tab().equals(tabId)).toList();
    }

    private static Component title(StellariaCore plugin, Player player, String tabId) {
        AdvancementDefinitions.Tab tab = plugin.getAdvancementManager().getParsed().tabs().get(tabId);
        String name = tab != null ? tab.title() : tabId;
        return FormatUtil.component(FormatUtil.replace(
                plugin.getConfigManager().getMessage("advancements.gui_category_title", player), "%tab%", FormatUtil.color(name)));
    }

    private void populate(Player player, List<AdvancementDefinitions.Definition> defs) {
        AdvancementManager manager = plugin.getAdvancementManager();
        Map<String, Long> completedAt = AdvancementStore.completedAt(player.getUniqueId());
        int first = page * CONTENT_SLOTS;
        for (int slot = 0; slot < CONTENT_SLOTS && first + slot < defs.size(); slot++) {
            AdvancementDefinitions.Definition def = defs.get(first + slot);
            boolean done = manager.isCompleted(player, def.id());
            getInventory().setItem(slot, done || !AdvancementRules.isConcealed(def, false)
                    ? entry(player, manager, def, done, completedAt.get(def.id()))
                    : AdvancementGui.item(Material.GRAY_STAINED_GLASS_PANE, message(player, "advancements.gui_hidden_name"),
                            List.of(message(player, "advancements.gui_hidden_lore"))));
        }
        if (page > 0) {
            getInventory().setItem(PREVIOUS_SLOT, AdvancementGui.item(Material.ARROW, message(player, "advancements.gui_previous_page"), List.of()));
        }
        getInventory().setItem(PAGE_SLOT, AdvancementGui.item(Material.PAPER, message(player, "advancements.gui_page",
                "%page%", String.valueOf(page + 1), "%max_page%", String.valueOf(maxPage + 1)), List.of()));
        if (page < maxPage) {
            getInventory().setItem(NEXT_SLOT, AdvancementGui.item(Material.ARROW, message(player, "advancements.gui_next_page"), List.of()));
        }
    }

    private ItemStack entry(Player player, AdvancementManager manager, AdvancementDefinitions.Definition def,
                            boolean done, Long completedAt) {
        List<Component> lore = new ArrayList<>();
        lore.add(FormatUtil.component(FormatUtil.color(def.description())));
        if (done) {
            lore.add(message(player, "advancements.gui_completed_lore", "%date%",
                    completedAt != null ? DATE.format(Instant.ofEpochMilli(completedAt)) : "-"));
        } else {
            lore.add(message(player, "advancements.gui_not_completed_lore"));
            long goal = AdvancementRules.goal(def);
            if (goal > 1) {
                long progress = Math.min(goal, manager.progress(player, def));
                lore.add(message(player, "advancements.gui_progress_lore",
                        "%progress%", RankingFormat.value("", progress), "%goal%", RankingFormat.value("", goal)));
            }
        }
        lore.add(message(player, "advancements.gui_reward_lore", "%amount%",
                plugin.getEconomyManager().formatExact(manager.rewardFor(def))));
        Material icon = done ? Material.matchMaterial(def.icon()) : Material.GRAY_DYE;
        ItemStack item = AdvancementGui.item(icon != null ? icon : Material.BOOK,
                FormatUtil.component(FormatUtil.color(def.title())), lore);
        if (done) {
            ItemMeta meta = item.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
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
        if (event.getRawSlot() == PREVIOUS_SLOT && page > 0) {
            new AdvancementCategoryGui(plugin, player, parent, tabId, page - 1).open(player);
        } else if (event.getRawSlot() == NEXT_SLOT && page < maxPage) {
            new AdvancementCategoryGui(plugin, player, parent, tabId, page + 1).open(player);
        }
    }

    private Component message(Player player, String path, String... replacements) {
        String value = plugin.getConfigManager().getMessage(path, player);
        for (int i = 0; i < replacements.length; i += 2) {
            value = FormatUtil.replace(value, replacements[i], replacements[i + 1]);
        }
        return FormatUtil.component(value);
    }
}
