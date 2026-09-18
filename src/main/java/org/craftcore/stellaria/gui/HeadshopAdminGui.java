package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
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

    @Override
    public boolean allowBottomShiftClick() {
        return true;
    }

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

        for (int slot = 45; slot < 54; slot++) {
            getInventory().setItem(slot, fillerItem());
        }

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
        meta.lore(List.of(org.craftcore.stellaria.utils.GuiItemUtil.text(plugin.getConfigManager().getMessage("headshop.admin.remove-hint", null))));
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
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfigManager().getMessageList("headshop.admin.hint")) {
            lore.add(org.craftcore.stellaria.utils.GuiItemUtil.text(line));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int rawSlot = event.getRawSlot();

        // 最下段のGUI操作エリア
        if (rawSlot >= 45 && rawSlot < 54) {
            event.setCancelled(true);

            if (handleBackButton(event, player)) {
                return;
            }

            if (rawSlot == PREVIOUS_SLOT && page > 0) {
                saveAndOpenPage(player, page - 1);
                return;
            }

            if (rawSlot == NEXT_SLOT && page < maxPage) {
                saveAndOpenPage(player, page + 1);
            }

            return;
        }

        // 上5段はバニラ操作を許可
        // 操作終了後の状態を次tickでDBへ同期
        player.getScheduler().run(
                plugin,
                task -> syncCurrentPage(player),
                null
        );
    }

    @Override
    public void onDrag(InventoryDragEvent event) {
        // 最下段に1スロットでも触れる場合は禁止
        boolean touchesBottomRow = event.getRawSlots().stream()
                .anyMatch(slot -> slot >= 45 && slot < 54);

        if (touchesBottomRow) {
            event.setCancelled(true);
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        player.getScheduler().run(
                plugin,
                task -> syncCurrentPage(player),
                null
        );
    }

    private void saveAndOpenPage(Player player, int newPage) {
        syncCurrentPage(player);

        player.getScheduler().run(
                plugin,
                task -> new HeadshopAdminGui(plugin, parent, newPage).open(player),
                null
        );
    }

    private void syncCurrentPage(Player player) {
        int first = page * CONTENT_SLOTS;

        /*
         * このページを開いた時点で登録されていたヘッド。
         * textureを基準に比較する。
         */
        List<HeadshopManager.PoolHead> originalHeads = new ArrayList<>();

        for (int slot = 0; slot < CONTENT_SLOTS; slot++) {
            int index = first + slot;

            if (index >= pool.size()) {
                continue;
            }

            HeadshopManager.PoolHead head = pool.get(index);

            if (head != null) {
                originalHeads.add(head);
            }
        }

        /*
         * 現在GUIに残っているPLAYER_HEADのtexture一覧。
         */
        List<String> currentTextures = new ArrayList<>();

        for (int slot = 0; slot < CONTENT_SLOTS; slot++) {
            ItemStack item = getInventory().getItem(slot);

            if (item == null || item.getType().isAir()) {
                continue;
            }

            /*
             * PLAYER_HEAD以外は管理GUIに置けない。
             * プレイヤーへ返してスロットを空にする。
             */
            if (item.getType() != Material.PLAYER_HEAD
                    || !(item.getItemMeta() instanceof SkullMeta skullMeta)) {

                getInventory().setItem(slot, null);

                var leftovers = player.getInventory().addItem(item);

                leftovers.values().forEach(leftover ->
                        player.getWorld().dropItemNaturally(
                                player.getLocation(),
                                leftover
                        )
                );

                player.sendMessage(
                        plugin.getConfigManager()
                                .getMessage("headshop.admin.invalid_head", player)
                );

                continue;
            }

            String texture = HeadshopManager.extractTexture(skullMeta);

            if (texture == null) {
                getInventory().setItem(slot, null);

                var leftovers = player.getInventory().addItem(item);

                leftovers.values().forEach(leftover ->
                        player.getWorld().dropItemNaturally(
                                player.getLocation(),
                                leftover
                        )
                );

                player.sendMessage(
                        plugin.getConfigManager()
                                .getMessage("headshop.admin.invalid_head", player)
                );

                continue;
            }

            currentTextures.add(texture);

            /*
             * DBに存在しない新しいヘッドなら登録。
             */
            if (!plugin.getHeadshopManager().textureExists(texture)) {
                String displayName = skullMeta.hasDisplayName()
                        ? PlainTextComponentSerializer.plainText()
                        .serialize(skullMeta.displayName())
                        : plugin.getConfigManager()
                        .getMessage("headshop.admin.unnamed-head", player);

                HeadshopManager.PoolHead added =
                        plugin.getHeadshopManager().addToPool(
                                displayName,
                                texture,
                                player.getUniqueId()
                        );

                if (added == null) {
                    player.sendMessage(
                            plugin.getConfigManager()
                                    .getMessage("headshop.admin.save-failed", player)
                    );

                    continue;
                }

                /*
                 * GUI側もショップ用の表示に差し替える。
                 */
                getInventory().setItem(slot, poolEntryItem(added));

                player.sendMessage(
                        FormatUtil.replace(
                                plugin.getConfigManager()
                                        .getMessage("headshop.admin.added", player),
                                "%item%",
                                displayName
                        )
                );
            }
        }

        /*
         * 元々このページにあったのに、
         * 現在GUIから無くなったヘッドはDBから削除。
         */
        for (HeadshopManager.PoolHead original : originalHeads) {
            if (!currentTextures.contains(original.texture())) {
                plugin.getHeadshopManager().removeFromPool(original.id());
            }
        }

        /*
         * DBの最新状態でローカルpoolを更新。
         */
        pool.clear();
        pool.addAll(plugin.getHeadshopManager().listPool());
    }

    private ItemStack fillerItem() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);

        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());

        item.setItemMeta(meta);

        return item;
    }
}
