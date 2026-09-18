package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /report のカテゴリを選ぶ27スロットの画面。 */
public final class ReportCategoryGui extends Gui {

    private static final int SIZE = 27;

    private final StellariaCore plugin;
    private final UUID targetUuid;
    private final Map<Integer, String> categoriesBySlot = new HashMap<>();

    public ReportCategoryGui(StellariaCore plugin, Player reporter, UUID targetUuid) {
        super(SIZE, title(plugin));
        this.plugin = plugin;
        this.targetUuid = targetUuid;
        populate(reporter);
    }

    private static Component title(StellariaCore plugin) {
        return FormatUtil.component(plugin.getConfigManager().getMessage("report.gui_title", null));
    }

    private void populate(Player reporter) {
        List<String> categories = plugin.getConfigManager().getStringList("report.categories");
        if (categories.isEmpty()) {
            plugin.getLogger().warning("report.categories が空のため、報告カテゴリを表示できません。");
            return;
        }

        for (int slot = 0; slot < Math.min(SIZE, categories.size()); slot++) {
            String category = categories.get(slot);
            categoriesBySlot.put(slot, category);
            getInventory().setItem(slot, categoryItem(FormatUtil.text(reporter, category)));
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        String category = categoriesBySlot.get(event.getRawSlot());
        if (category == null || !(event.getWhoClicked() instanceof Player reporter)) {
            return;
        }
        plugin.getReportManager().beginReport(reporter, targetUuid, category);
        reporter.closeInventory();
        reporter.sendMessage(plugin.getConfigManager().getMessage("report.detail_prompt", reporter));
    }

    private static ItemStack categoryItem(String category) {
        ItemStack item = ItemStack.of(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(FormatUtil.component(category));
        item.setItemMeta(meta);
        return item;
    }
}
