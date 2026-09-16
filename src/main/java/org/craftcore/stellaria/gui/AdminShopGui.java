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

    private AdminShopGui(StellariaCore plugin, Player player, @Nullable Gui parent, List<ShopItem> shopItems) {
        super(inventorySize(shopItems.size()), ColorUtil.component(plugin.getConfigManager().getMessage("adminshop.title", player)), parent);
        this.plugin = plugin;

        for (int slot = 0; slot < shopItems.size() && slot < MAX_SLOTS; slot++) {
            ShopItem shopItem = shopItems.get(slot);
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

        EconomyResponse response = plugin.getEconomyManager().withdrawPlayer(player, shopItem.price());
        if (response.transactionSuccess()) {
            player.getInventory().addItem(new ItemStack(shopItem.material()));
            player.sendMessage(plugin.getConfigManager().getMessage("adminshop.purchased", player)
                    .replace("%item%", shopItem.material().name())
                    .replace("%price%", plugin.getEconomyManager().format(shopItem.price())));
            return;
        }

        player.sendMessage(plugin.getConfigManager().getMessage("adminshop.insufficient_funds", player)
                .replace("%price%", plugin.getEconomyManager().format(shopItem.price())));
    }

    private ItemStack createDisplayItem(ShopItem shopItem) {
        ItemStack itemStack = GuiItemUtil.cleanIcon(shopItem.material());
        ItemMeta meta = itemStack.getItemMeta();
        meta.lore(List.of(ColorUtil.component("&%7価格: &%e" + plugin.getEconomyManager().format(shopItem.price()))));
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private static List<ShopItem> loadItems(StellariaCore plugin) {
        List<ShopItem> shopItems = new ArrayList<>();
        for (Map<?, ?> itemConfig : plugin.getConfigManager().getMapList("adminshop.items")) {
            Object materialValue = itemConfig.get("material");
            Object priceValue = itemConfig.get("price");
            Material material = materialValue instanceof String materialName ? Material.matchMaterial(materialName) : null;

            if (material == null || !(priceValue instanceof Number number) || number.intValue() < 0) {
                plugin.getLogger().warning("adminshop.items に無効な商品設定があります: " + itemConfig);
                continue;
            }

            shopItems.add(new ShopItem(material, number.intValue()));
        }
        return shopItems;
    }

    private static int inventorySize(int itemCount) {
        int rows = Math.max(1, Math.min(6, (itemCount + 8) / 9));
        return rows * 9;
    }

    private record ShopItem(Material material, int price) {
    }
}
