package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.commands.HomeCommand;
import org.craftcore.stellaria.commands.WarpCommand;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.CustomHeadUtil;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.GuiItemUtil;
import org.craftcore.stellaria.utils.MenuItemUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * サーバー内の各機能への入口をまとめた総合メニュー（/menu）。
 * 行数・スロット・アイコン・表示名・アクションは {@code config.yml} の {@code menu} セクションで
 * 変更できる（{@link #loadEntries(StellariaCore)}参照）。それぞれのアクションは既存の
 * コマンド/GUIをそのまま呼び出すだけで、独自ロジックは持たない。
 */
public class MenuGui extends Gui {

    private static final int DEFAULT_ROWS = 5;

    private final StellariaCore plugin;
    private final Map<Integer, MenuEntry> entriesBySlot;

    public MenuGui(StellariaCore plugin, Player viewer) {
        super(inventorySize(plugin), messageComponent(plugin, "menu.title", viewer));
        viewer.playSound(viewer, Sound.BLOCK_CHEST_OPEN,0.5f,1);
        this.plugin = plugin;
        this.entriesBySlot = loadEntries(plugin);
        populate(viewer);
    }

    private static int inventorySize(StellariaCore plugin) {
        int rows = plugin.getConfigManager().getInt("menu.rows", DEFAULT_ROWS);
        return Math.max(1, Math.min(6, rows)) * 9;
    }

    private void populate(Player viewer) {
        for (Map.Entry<Integer, MenuEntry> entry : entriesBySlot.entrySet()) {
            int slot = entry.getKey();
            if (slot >= getInventory().getSize()) {
                continue;
            }
            MenuEntry menuEntry = entry.getValue();
            ItemStack item = menuEntry.customHeadId() == null ? null
                    : CustomHeadUtil.create(plugin, menuEntry.customHeadId());
            if (item == null) {
                item = new ItemStack(menuEntry.material());
            }
            GuiItemUtil.hideExtras(item);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(GuiItemUtil.text(menuEntry.name()));
            if (meta instanceof SkullMeta skullMeta && menuEntry.material() == Material.PLAYER_HEAD
                    && menuEntry.customHeadId() == null) {
                skullMeta.setOwningPlayer(viewer);
            }
            item.setItemMeta(meta);
            getInventory().setItem(slot, item);
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= getInventory().getSize()) {
            return;
        }

        MenuEntry entry = entriesBySlot.get(event.getRawSlot());
        if (entry == null) {
            return;
        }
        Boolean playDefaultSound = true;
        switch (entry.action()) {
            case "profile" -> new ProfileGui(plugin, player, this).open(player);
            case "discord" -> runCommand(player, "discord");
            case "vote" -> runCommand(player, "vote");
            case "world" -> new WorldSelectGui(plugin, this).open(player);
            case "weathervote" -> new WeatherVoteGui(plugin, player, this).open(player);
            case "timevote" -> new TimeVoteGui(plugin, player, this).open(player);
            case "enderchest" -> {
                playDefaultSound = false;
                player.playSound(player, Sound.BLOCK_ENDER_CHEST_OPEN,1,1);
                player.closeInventory();
                player.openInventory(player.getEnderChest());
            }
            case "features" -> new FeaturesGui(plugin, plugin.getFeatures(), player, this).open(player);
            case "map" -> runCommand(player, "map");
            case "homepage" -> runCommand(player, "homepage");
            case "tpa-help" -> sendHelpLines(player, "menu.tpa_help_lines");
            case "land-help" -> runCommand(player, "land help");
            case "warp" -> new WarpSelectGui(plugin, new WarpCommand(plugin), this).open(player);
            case "home" -> new HomeSelectGui(plugin, new HomeCommand(plugin), player, this).open(player);
            case "get-menu-item" -> MenuItemUtil.give(plugin, player);
            case "headshop" -> new HeadshopGui(plugin, player, this).open(player);
            case "links" -> {
                runCommand(player,"discord");
                runCommand(player, "homepage");
            }
            default -> plugin.getLogger().warning("menu.items に不明なactionがあります: " + entry.action());
        }
        if (playDefaultSound) {
            player.playSound(player,Sound.UI_BUTTON_CLICK,1,1);
        }
    }

    private void runCommand(Player player, String command) {
        player.closeInventory();
        player.performCommand(command);
    }

    private void sendHelpLines(Player player, String messageListKey) {
        player.closeInventory();
        for (String line : plugin.getConfigManager().getMessageList(messageListKey)) {
            player.sendMessage(FormatUtil.text(player, line));
        }
    }

    private static Map<Integer, MenuEntry> loadEntries(StellariaCore plugin) {
        Map<Integer, MenuEntry> entries = new HashMap<>();
        List<Map<?, ?>> configuredItems = plugin.getConfigManager().getMapList("menu.items");
        for (Map<?, ?> itemConfig : configuredItems) {
            Object slotValue = itemConfig.get("slot");
            Object materialValue = itemConfig.get("material");
            Object nameValue = itemConfig.get("name");
            Object actionValue = itemConfig.get("action");
            Object customHeadValue = itemConfig.get("custom-head");

            if (!(slotValue instanceof Number slotNumber)) {
                plugin.getLogger().warning("menu.items に無効なslot指定があります: " + itemConfig);
                continue;
            }
            int slot = slotNumber.intValue();
            int maxSlot = inventorySize(plugin) - 1;
            if (slot < 0 || slot > maxSlot) {
                plugin.getLogger().warning("menu.items に範囲外のslot指定があります (0-" + maxSlot + "): " + itemConfig);
                continue;
            }
            Material material = materialValue instanceof String materialName ? Material.matchMaterial(materialName) : null;
            if (material == null) {
                plugin.getLogger().warning("menu.items に無効なmaterial指定があります: " + itemConfig);
                continue;
            }
            String name = nameValue instanceof String ? (String) nameValue : "";
            String action = actionValue instanceof String ? (String) actionValue : "";
            String customHeadId = customHeadValue instanceof String id && !id.isBlank() ? id.trim() : null;

            if (entries.putIfAbsent(slot, new MenuEntry(material, name, action, customHeadId)) != null) {
                plugin.getLogger().warning("menu.items に重複したslot指定があります: " + slot);
            }
        }
        return entries;
    }

    private record MenuEntry(Material material, String name, String action, String customHeadId) {
    }

    private static Component messageComponent(StellariaCore plugin, String path, Player player) {
        return ColorUtil.component(plugin.getConfigManager().getMessage(path, player));
    }
}
