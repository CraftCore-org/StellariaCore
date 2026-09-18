package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.GuiItemUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** /report のカテゴリを選ぶ27スロットの画面。 */
public final class ReportCategoryGui extends Gui {

    private static final int SIZE = 27;
    private static final Material DEFAULT_MATERIAL = Material.PAPER;

    private final StellariaCore plugin;
    private final java.util.UUID targetUuid;
    private final Map<Integer, String> categoriesBySlot = new HashMap<>();

    public ReportCategoryGui(StellariaCore plugin, Player reporter, java.util.UUID targetUuid) {
        super(SIZE, title(plugin));
        this.plugin = plugin;
        this.targetUuid = targetUuid;
        populate(reporter);
    }

    private static Component title(StellariaCore plugin) {
        return FormatUtil.component(plugin.getConfigManager().getMessage("report.gui_title", null));
    }

    private void populate(Player reporter) {
        List<Map<?, ?>> categories = plugin.getConfigManager().getMapList("report.categories");
        if (categories.isEmpty()) {
            plugin.getLogger().warning("report.categories が空のため、報告カテゴリを表示できません。");
            return;
        }

        for (Map<?, ?> entry : categories) {
            Object nameValue = entry.get("name");
            if (!(nameValue instanceof String displayName) || displayName.isBlank()) {
                plugin.getLogger().warning("report.categories に無効なname指定があります: " + entry);
                continue;
            }

            Object slotValue = entry.get("slot");
            if (!(slotValue instanceof Number slotNumber)) {
                plugin.getLogger().warning("report.categories に無効なslot指定があります: " + entry);
                continue;
            }
            int slot = slotNumber.intValue();
            if (slot < 0 || slot >= SIZE) {
                plugin.getLogger().warning("report.categories に範囲外のslot指定があります (0-" + (SIZE - 1) + "): " + entry);
                continue;
            }

            Material material = DEFAULT_MATERIAL;
            Object materialValue = entry.get("material");
            if (materialValue instanceof String materialName) {
                Material matched = Material.matchMaterial(materialName);
                if (matched == null) {
                    plugin.getLogger().warning("report.categories に無効なmaterial指定があります: " + entry);
                    continue;
                }
                material = matched;
            }

            String storageCategory = storageCategory(entry, displayName);
            if (categoriesBySlot.putIfAbsent(slot, storageCategory) != null) {
                plugin.getLogger().warning("report.categories に重複したslot指定があります: " + slot);
                continue;
            }

            getInventory().setItem(slot, categoryItem(material, FormatUtil.text(reporter, displayName)));
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

    private static ItemStack categoryItem(Material material, String displayName) {
        ItemStack item = GuiItemUtil.cleanIcon(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(GuiItemUtil.text(displayName));
        item.setItemMeta(meta);
        return item;
    }

    /** DB/Discord用のプレーンカテゴリ名。idがあれば優先し、なければ表示名から色コードを除く。 */
    private static String storageCategory(Map<?, ?> entry, String displayName) {
        Object idValue = entry.get("id");
        if (idValue instanceof String id && !id.isBlank()) {
            return id.trim();
        }
        return PlainTextComponentSerializer.plainText().serialize(GuiItemUtil.text(displayName));
    }
}
