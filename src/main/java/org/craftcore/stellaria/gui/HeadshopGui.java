package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.HeadshopManager;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.FormatUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** /headshop で開くメインGUI（3行）。中央5マスに本日の日替わりヘッド、下段中央にプレイヤーヘッド一覧への導線。 */
public class HeadshopGui extends Gui {

    private static final int HINT_SLOT = 4;
    private static final int[] HEAD_SLOTS = {11, 12, 13, 14, 15};
    private static final int EMPTY_HINT_SLOT = 13;
    private static final int PLAYER_HEADS_BUTTON_SLOT = 22;

    private final StellariaCore plugin;
    private final Map<Integer, HeadshopManager.PoolHead> headsBySlot = new HashMap<>();

    public HeadshopGui(StellariaCore plugin, Player player) {
        this(plugin, player, null);
    }

    /** メニュー画面から開く場合、戻るボタンを出すために親画面を渡す。 */
    public HeadshopGui(StellariaCore plugin, Player player, @Nullable Gui parent) {
        super(27, title(plugin, player), parent);
        this.plugin = plugin;
        populate(player);
    }

    private static Component title(StellariaCore plugin, Player player) {
        return ColorUtil.component(plugin.getConfigManager().getMessage("headshop.title", player));
    }

    private void populate(Player viewer) {
        int price = plugin.getConfigManager().getInt("headshop.normal-price", 500);
        List<HeadshopManager.PoolHead> heads = plugin.getHeadshopManager().getTodayHeads();
        if (heads.isEmpty()) {
            getInventory().setItem(EMPTY_HINT_SLOT, emptyHintItem(viewer));
        } else {
            for (int i = 0; i < heads.size() && i < HEAD_SLOTS.length; i++) {
                HeadshopManager.PoolHead head = heads.get(i);
                headsBySlot.put(HEAD_SLOTS[i], head);
                getInventory().setItem(HEAD_SLOTS[i], createDisplayItem(head, price));
            }
        }
        getInventory().setItem(HINT_SLOT, hintItem(viewer));
        getInventory().setItem(PLAYER_HEADS_BUTTON_SLOT, playerHeadsButtonItem(viewer));
    }

    private ItemStack createDisplayItem(HeadshopManager.PoolHead head, int price) {
        ItemStack item = plugin.getHeadshopManager().createHeadItem(head);
        ItemMeta meta = item.getItemMeta();
        meta.lore(List.of(ColorUtil.component("&%7価格: &%e" + plugin.getEconomyManager().format(price))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptyHintItem(Player viewer) {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ColorUtil.component(plugin.getConfigManager().getMessage("headshop.shop-empty", viewer)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack hintItem(Player viewer) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ColorUtil.component(plugin.getConfigManager().getMessage("headshop.hint-title", viewer)));
        String resetTime = plugin.getConfigManager().getString("headshop.reset-time", "12:00");
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfigManager().getMessageList("headshop.main-hint")) {
            lore.add(ColorUtil.component(FormatUtil.replace(line, "%reset_time%", resetTime)));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack playerHeadsButtonItem(Player viewer) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(viewer);
        meta.displayName(ColorUtil.component(plugin.getConfigManager().getMessage("headshop.player-heads-title", viewer)));
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() != getInventory() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (handleBackButton(event, player)) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot == PLAYER_HEADS_BUTTON_SLOT) {
            new HeadshopPlayerHeadsGui(plugin, this).open(player);
            return;
        }

        HeadshopManager.PoolHead head = headsBySlot.get(slot);
        if (head == null) {
            return;
        }
        purchase(player, head);
    }

    private void purchase(Player player, HeadshopManager.PoolHead head) {
        int price = plugin.getConfigManager().getInt("headshop.normal-price", 500);
        EconomyResponse response = plugin.getEconomyManager().withdrawPlayer(player, price);
        if (!response.transactionSuccess()) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("headshop.insufficient-funds", player),
                    "%price%", plugin.getEconomyManager().format(price)));
            return;
        }

        ItemStack item = plugin.getHeadshopManager().createHeadItem(head);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            plugin.getEconomyManager().depositPlayer(player, price);
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("headshop.inventory-full", player),
                    "%price%", plugin.getEconomyManager().format(price)));
            return;
        }

        Component purchasedMessage = ColorUtil.component(FormatUtil.replace(
                plugin.getConfigManager().getMessage("headshop.purchased", player),
                "%price%", plugin.getEconomyManager().format(price)))
                .replaceText(builder -> builder.matchLiteral("%item%")
                        .replacement(ColorUtil.component(head.displayName())));
        player.sendMessage(purchasedMessage);
    }
}
