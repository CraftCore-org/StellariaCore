package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
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
import java.util.List;
import java.util.Map;

/** /headshop のメインGUIから開く、実在プレイヤーの頭を割増価格で購入できる一覧画面。 */
public final class HeadshopPlayerHeadsGui extends Gui {

    private static final int CONTENT_SLOTS = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int HINT_SLOT = 47;
    private static final int PAGE_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final int BACK_BUTTON_SLOT = 48;

    private final StellariaCore plugin;
    private final @Nullable Gui parent;
    private final List<HeadshopManager.RecentPlayer> players;
    private final int page;
    private final int maxPage;

    public HeadshopPlayerHeadsGui(StellariaCore plugin) {
        this(plugin, null);
    }

    public HeadshopPlayerHeadsGui(StellariaCore plugin, @Nullable Gui parent) {
        this(plugin, parent, plugin.getHeadshopManager().listRecentPlayers(), 0);
    }

    private HeadshopPlayerHeadsGui(StellariaCore plugin, @Nullable Gui parent, List<HeadshopManager.RecentPlayer> players, int page) {
        super(54, title(plugin), parent, BACK_BUTTON_SLOT);
        this.plugin = plugin;
        this.parent = parent;
        this.players = players;
        this.maxPage = Math.max(0, (players.size() - 1) / CONTENT_SLOTS);
        this.page = Math.clamp(page, 0, maxPage);
        populate();
    }

    private static Component title(StellariaCore plugin) {
        return ColorUtil.component(plugin.getConfigManager().getMessage("headshop.player-heads-title", null));
    }

    private void populate() {
        int price = plugin.getConfigManager().getInt("headshop.normal-price", 500)
                + plugin.getConfigManager().getInt("headshop.player-head-markup", 300);
        int first = page * CONTENT_SLOTS;
        for (int slot = 0; slot < CONTENT_SLOTS && first + slot < players.size(); slot++) {
            getInventory().setItem(slot, createDisplayItem(players.get(first + slot), price));
        }

        if (players.isEmpty()) {
            getInventory().setItem(PAGE_SLOT, message(Material.BARRIER, "headshop.player-heads-empty"));
        } else {
            if (page > 0) {
                getInventory().setItem(PREVIOUS_SLOT, message(Material.ARROW, "headshop.gui-previous-page"));
            }
            getInventory().setItem(PAGE_SLOT, pageIndicator());
            if (page < maxPage) {
                getInventory().setItem(NEXT_SLOT, message(Material.ARROW, "headshop.gui-next-page"));
            }
        }

        getInventory().setItem(HINT_SLOT, hintItem());
    }

    private ItemStack createDisplayItem(HeadshopManager.RecentPlayer recentPlayer, int price) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(recentPlayer.uuid()));
        meta.displayName(org.craftcore.stellaria.utils.GuiItemUtil.text(Component.text(recentPlayer.name(), NamedTextColor.WHITE)));
        String priceLore = plugin.getConfigManager()
                .getMessage("headshop.price-lore", null);

        priceLore = FormatUtil.replace(
                priceLore,
                "%price%",
                plugin.getEconomyManager().format(price)
        );

        meta.lore(List.of(
                org.craftcore.stellaria.utils.GuiItemUtil.text(priceLore)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack pageIndicator() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        String text = plugin.getConfigManager().getMessage("headshop.gui-page", null);
        text = FormatUtil.replace(text, "%page%", String.valueOf(page + 1));
        text = FormatUtil.replace(text, "%max_page%", String.valueOf(maxPage + 1));
        meta.displayName(org.craftcore.stellaria.utils.GuiItemUtil.text(text));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack message(Material material, String path) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(org.craftcore.stellaria.utils.GuiItemUtil.text(plugin.getConfigManager().getMessage(path, null)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack hintItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(org.craftcore.stellaria.utils.GuiItemUtil.text(plugin.getConfigManager().getMessage("headshop.hint-title", null)));
        String resetTime = plugin.getConfigManager().getString("headshop.reset-time", "12:00");
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfigManager().getMessageList("headshop.player-heads-hint")) {
            lore.add(org.craftcore.stellaria.utils.GuiItemUtil.text(FormatUtil.replace(line, "%reset_time%", resetTime)));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
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
            new HeadshopPlayerHeadsGui(plugin, parent, players, page - 1).open(player);
            return;
        }
        if (slot == NEXT_SLOT && page < maxPage) {
            new HeadshopPlayerHeadsGui(plugin, parent, players, page + 1).open(player);
            return;
        }
        if (slot >= CONTENT_SLOTS) {
            return;
        }

        int index = page * CONTENT_SLOTS + slot;
        if (index >= players.size()) {
            return;
        }
        openPurchaseConfirm(
                player,
                players.get(index)
        );
    }

    private void openPurchaseConfirm(
            Player player,
            HeadshopManager.RecentPlayer recentPlayer
    ) {
        int price = plugin.getConfigManager()
                .getInt(
                        "headshop.normal-price",
                        500
                )
                + plugin.getConfigManager()
                .getInt(
                        "headshop.player-head-markup",
                        300
                );

        Component title = ColorUtil.component(
                plugin.getConfigManager()
                        .getMessage("headshop.confirm-title", player)
        );

        String descriptionText = plugin.getConfigManager()
                .getMessage(
                        "headshop.confirm-player-head-description",
                        player
                );

        descriptionText = FormatUtil.replace(
                descriptionText,
                "%player%",
                recentPlayer.name()
        );

        descriptionText = FormatUtil.replace(
                descriptionText,
                "%price%",
                plugin.getEconomyManager().format(price)
        );

        Component description = ColorUtil.component(descriptionText);

        Component confirmText = ColorUtil.component(
                plugin.getConfigManager()
                        .getMessage("gui.confirm", player)
        );

        Component cancelText = ColorUtil.component(
                plugin.getConfigManager()
                        .getMessage("gui.cancel", player)
        );

        new ConfirmGui(
                title,
                description,
                confirmText,
                cancelText,
                () -> purchase(
                        player,
                        recentPlayer
                ),
                () -> this.open(player)
        ).open(player);
    }

    private void purchase(Player player, HeadshopManager.RecentPlayer recentPlayer) {
        int price = plugin.getConfigManager().getInt("headshop.normal-price", 500)
                + plugin.getConfigManager().getInt("headshop.player-head-markup", 300);
        EconomyResponse response = plugin.getEconomyManager().withdrawPlayer(player, price);
        if (!response.transactionSuccess()) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("headshop.insufficient-funds", player),
                    "%price%", plugin.getEconomyManager().format(price)));
            return;
        }

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(recentPlayer.uuid()));
        item.setItemMeta(meta);

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
                        .replacement(Component.text(recentPlayer.name(), NamedTextColor.WHITE)));
        player.sendMessage(purchasedMessage);
    }
}
