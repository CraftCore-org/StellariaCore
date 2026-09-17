package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.GuiItemUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** /adminshop で開く、運営物資を1個ずつ販売するGUI。 */
public class AdminShopGui extends Gui {

    private static final int MAX_SLOTS = 54;

    private final StellariaCore plugin;
    private final Map<Integer, ShopItem> itemsBySlot = new HashMap<>();

    public AdminShopGui(StellariaCore plugin, Player player) {
        this(plugin, player, null, loadItems(plugin));
    }

    /** メニュー画面から開く場合、戻るボタンを出すために親画面を渡す。 */
    public AdminShopGui(StellariaCore plugin, Player player, @Nullable Gui parent) {
        this(plugin, player, parent, loadItems(plugin));
    }

    private AdminShopGui(StellariaCore plugin, Player player, @Nullable Gui parent, ShopLayout layout) {
        super(layout.inventorySize(), ColorUtil.component(plugin.getConfigManager().getMessage("adminshop.title", player)), parent);
        this.plugin = plugin;

        for (Map.Entry<Integer, ShopItem> entry : layout.itemsBySlot().entrySet()) {
            int slot = entry.getKey();
            ShopItem shopItem = entry.getValue();
            itemsBySlot.put(slot, shopItem);
            getInventory().setItem(slot, createDisplayItem(shopItem));
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);

        if (event.getClickedInventory() != getInventory()) {
            return;
        }
        if (event.getWhoClicked() instanceof Player p && handleBackButton(event, p)) {
            return;
        }

        ShopItem shopItem = itemsBySlot.get(event.getRawSlot());
        if (shopItem == null || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack itemStack = new ItemStack(shopItem.material());
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(itemStack.clone());
        if (!leftover.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().getMessage("adminshop.inventory_full", player));
            return;
        }

        EconomyResponse response = plugin.getEconomyManager().withdrawPlayer(player, shopItem.price());
        if (!response.transactionSuccess()) {
            player.getInventory().removeItem(itemStack);
            player.sendMessage(plugin.getConfigManager().getMessage("adminshop.insufficient_funds", player)
                    .replace("%price%", plugin.getEconomyManager().format(shopItem.price())));
            return;
        }

        Component purchasedMessage = ColorUtil.component(plugin.getConfigManager().getMessage("adminshop.purchased", player)
                .replace("%price%", plugin.getEconomyManager().format(shopItem.price())))
                .replaceText(builder -> builder.matchLiteral("%item%")
                        .replacement(Component.translatable(itemStack.getType().translationKey())));
        player.sendMessage(purchasedMessage);
    }

    private ItemStack createDisplayItem(ShopItem shopItem) {
        ItemStack itemStack = GuiItemUtil.cleanIcon(shopItem.material());
        ItemMeta meta = itemStack.getItemMeta();
        meta.lore(List.of(ColorUtil.component("&%7価格: &%e" + plugin.getEconomyManager().format(shopItem.price()))));
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private static ShopLayout loadItems(StellariaCore plugin) {
        List<ShopItem> shopItems = new ArrayList<>();
        for (Map<?, ?> itemConfig : plugin.getConfigManager().getMapList("adminshop.items")) {
            Object materialValue = itemConfig.get("material");
            Object priceValue = itemConfig.get("price");
            Object slotValue = itemConfig.get("slot");
            Material material = materialValue instanceof String materialName ? Material.matchMaterial(materialName) : null;

            if (material == null || !(priceValue instanceof Number number) || number.intValue() < 0) {
                plugin.getLogger().warning("adminshop.items に無効な商品設定があります: " + itemConfig);
                continue;
            }

            if (slotValue != null && (!(slotValue instanceof Number) || ((Number) slotValue).intValue() < 0)) {
                plugin.getLogger().warning("adminshop.items の slot は0以上の整数で指定してください: " + itemConfig);
                continue;
            }

            shopItems.add(new ShopItem(material, number.intValue(), slotValue instanceof Number slot ? slot.intValue() : null));
        }

        int configuredRows = plugin.getConfigManager().getInt("adminshop.rows", -1, true);
        boolean hasFixedRows = configuredRows != -1;
        if (hasFixedRows && (configuredRows < 1 || configuredRows > 6)) {
            plugin.getLogger().warning("adminshop.rows は1から6で指定してください。自動行数を使用します: " + configuredRows);
            hasFixedRows = false;
        }

        int highestRequestedSlot = shopItems.stream()
                .map(ShopItem::slot)
                .filter(slot -> slot != null)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(-1);
        int rows = hasFixedRows
                ? configuredRows
                : Math.max(1, Math.min(6, Math.max((shopItems.size() + 8) / 9, (highestRequestedSlot + 9) / 9)));
        int inventorySize = rows * 9;
        Map<Integer, ShopItem> itemsBySlot = new LinkedHashMap<>();

        for (ShopItem shopItem : shopItems) {
            if (shopItem.slot() == null) {
                continue;
            }
            if (shopItem.slot() >= inventorySize) {
                plugin.getLogger().warning("adminshop.items の slot がショップの範囲外です: " + shopItem.slot());
                continue;
            }
            if (itemsBySlot.putIfAbsent(shopItem.slot(), shopItem) != null) {
                plugin.getLogger().warning("adminshop.items に重複した slot 指定があります: " + shopItem.slot());
            }
        }

        for (ShopItem shopItem : shopItems) {
            if (shopItem.slot() != null) {
                continue;
            }
            for (int slot = 0; slot < inventorySize; slot++) {
                if (!itemsBySlot.containsKey(slot)) {
                    itemsBySlot.put(slot, shopItem);
                    break;
                }
            }
            if (itemsBySlot.size() == inventorySize) {
                break;
            }
        }

        return new ShopLayout(inventorySize, itemsBySlot);
    }

    private record ShopItem(Material material, int price, @Nullable Integer slot) {
    }

    private record ShopLayout(int inventorySize, Map<Integer, ShopItem> itemsBySlot) {
    }
}
