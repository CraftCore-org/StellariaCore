package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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

/** /headshop admin で開く、headshop_poolへのヘッド登録・削除画面。 */
public final class HeadshopAdminGui extends Gui {

    private static final int CONTENT_SLOTS = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int HINT_SLOT = 47;
    private static final int PAGE_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final int BACK_BUTTON_SLOT = 48;

    private final StellariaCore plugin;
    private final @Nullable Gui parent;
    private final List<HeadshopManager.PoolHead> pool;
    private final int page;
    private final int maxPage;

    public HeadshopAdminGui(StellariaCore plugin) {
        this(plugin, null, 0);
    }

    private HeadshopAdminGui(StellariaCore plugin, @Nullable Gui parent, int page) {
        super(54, title(plugin), parent, BACK_BUTTON_SLOT);
        this.plugin = plugin;
        this.parent = parent;
        this.pool = new ArrayList<>(plugin.getHeadshopManager().listPool());
        // pool.size()がちょうど45の倍数でも登録用の空きページが必ず1つ残るよう切り上げしない除算にする
        this.maxPage = Math.max(0, pool.size() / CONTENT_SLOTS);
        this.page = Math.clamp(page, 0, maxPage);
        populate();
    }

    private static Component title(StellariaCore plugin) {
        return ColorUtil.component(plugin.getConfigManager().getMessage("headshop.admin-title", null));
    }

    private void populate() {
        int first = page * CONTENT_SLOTS;
        for (int slot = 0; slot < CONTENT_SLOTS && first + slot < pool.size(); slot++) {
            HeadshopManager.PoolHead head = pool.get(first + slot);
            if (head != null) {
                getInventory().setItem(slot, poolEntryItem(head));
            }
        }

        if (page > 0) {
            getInventory().setItem(PREVIOUS_SLOT, message(Material.ARROW, "headshop.gui-previous-page"));
        }
        getInventory().setItem(PAGE_SLOT, pageIndicator());
        if (page < maxPage) {
            getInventory().setItem(NEXT_SLOT, message(Material.ARROW, "headshop.gui-next-page"));
        }
        getInventory().setItem(HINT_SLOT, hintItem());
    }

    private ItemStack poolEntryItem(HeadshopManager.PoolHead head) {
        ItemStack item = plugin.getHeadshopManager().createHeadItem(head);
        ItemMeta meta = item.getItemMeta();
        meta.lore(List.of(ColorUtil.component(plugin.getConfigManager().getMessage("headshop.admin.remove-hint", null))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack pageIndicator() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        String text = plugin.getConfigManager().getMessage("headshop.gui-page", null);
        text = FormatUtil.replace(text, "%page%", String.valueOf(page + 1));
        text = FormatUtil.replace(text, "%max_page%", String.valueOf(maxPage + 1));
        meta.displayName(ColorUtil.component(text));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack message(Material material, String path) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ColorUtil.component(plugin.getConfigManager().getMessage(path, null)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack hintItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ColorUtil.component(plugin.getConfigManager().getMessage("headshop.hint-title", null)));
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfigManager().getMessageList("headshop.admin.hint")) {
            lore.add(ColorUtil.component(line));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= getInventory().getSize()) {
            return;
        }
        event.setCancelled(true);
        if (handleBackButton(event, player)) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot == PREVIOUS_SLOT && page > 0) {
            new HeadshopAdminGui(plugin, parent, page - 1).open(player);
            return;
        }
        if (slot == NEXT_SLOT && page < maxPage) {
            new HeadshopAdminGui(plugin, parent, page + 1).open(player);
            return;
        }
        if (slot >= CONTENT_SLOTS) {
            return;
        }

        int index = page * CONTENT_SLOTS + slot;
        if (index < pool.size() && pool.get(index) != null) {
            if (event.isShiftClick() || !event.getCursor().getType().isAir()) {
                return;
            }
            HeadshopManager.PoolHead head = pool.get(index);
            plugin.getHeadshopManager().removeFromPool(head.id());
            player.setItemOnCursor(plugin.getHeadshopManager().createHeadItem(head));
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("headshop.admin.removed", player),
                    "%item%", head.displayName()));
            pool.set(index, null);
            getInventory().setItem(slot, null);
            return;
        }

        if (event.isShiftClick()) {
            registerFromCursor(player, event.getCursor(), slot, index);
        }
    }

    private void registerFromCursor(Player player, @Nullable ItemStack cursor, int slot, int index) {
        if (cursor == null || cursor.getType() != Material.PLAYER_HEAD || !(cursor.getItemMeta() instanceof SkullMeta skullMeta)) {
            player.sendMessage(plugin.getConfigManager().getMessage("headshop.admin.invalid_head", player));
            return;
        }

        String texture = HeadshopManager.extractTexture(skullMeta);
        if (texture == null) {
            player.sendMessage(plugin.getConfigManager().getMessage("headshop.admin.invalid_head", player));
            return;
        }
        if (plugin.getHeadshopManager().textureExists(texture)) {
            player.sendMessage(plugin.getConfigManager().getMessage("headshop.admin.duplicate", player));
            return;
        }

        String displayName = skullMeta.hasDisplayName()
                ? PlainTextComponentSerializer.plainText().serialize(skullMeta.displayName())
                : plugin.getConfigManager().getMessage("headshop.admin.unnamed-head", player);
        HeadshopManager.PoolHead added = plugin.getHeadshopManager().addToPool(displayName, texture, player.getUniqueId());
        if (added == null) {
            player.sendMessage(plugin.getConfigManager().getMessage("headshop.admin.save-failed", player));
            return;
        }
        player.sendMessage(FormatUtil.replace(
                plugin.getConfigManager().getMessage("headshop.admin.added", player),
                "%item%", displayName));
        while (pool.size() <= index) {
            pool.add(null);
        }
        pool.set(index, added);
        getInventory().setItem(slot, poolEntryItem(added));
    }
}
