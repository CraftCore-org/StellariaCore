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

import java.util.HashMap;
import java.util.Map;
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
    private final Map<Integer, Integer> originalIdsBySlot = new HashMap<>();

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
            // parentあり生成時はBase Guiがslot 48に戻るボタンを既に置いているため、
            // ここでfillerItemに上書きすると見た目はfillerなのにクリック判定は
            // handleBackButtonのまま残る「見えない戻るボタン」になる。上書きしない。
            if (parent != null && slot == BACK_BUTTON_SLOT) {
                continue;
            }
            getInventory().setItem(slot, fillerItem());
        }

        for (int slot = 0; slot < CONTENT_SLOTS && first + slot < pool.size(); slot++) {
            HeadshopManager.PoolHead head = pool.get(first + slot);
            if (head != null) {
                getInventory().setItem(slot, poolEntryItem(head));
                originalIdsBySlot.put(slot, head.id());
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
        return plugin.getHeadshopManager()
                .createHeadItem(head);
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
        for (int slot = 0; slot < CONTENT_SLOTS; slot++) {

            ItemStack currentItem = getInventory().getItem(slot);
            Integer originalId = originalIdsBySlot.get(slot);

            // ---------------------------
            // 元々商品があったスロット
            // ---------------------------
            if (originalId != null) {

                // 今は空になっている
                // = GUIから取り出された
                if (currentItem == null || currentItem.getType().isAir()) {

                    HeadshopManager.PoolHead original = pool.stream()
                            .filter(head -> head.id() == originalId)
                            .findFirst()
                            .orElse(null);

                    plugin.getHeadshopManager()
                            .removeFromPool(originalId);

                    originalIdsBySlot.remove(slot);

                    if (original != null) {
                        player.sendMessage(
                                FormatUtil.replace(
                                        plugin.getConfigManager()
                                                .getMessage(
                                                        "headshop.admin.removed",
                                                        player
                                                ),
                                        "%item%",
                                        original.displayName()
                                )
                        );
                    }

                    continue;
                }

                /*
                 * 元の商品がまだ同じ場所にあるなら何もしない。
                 *
                 * itemDataから元ItemStackを復元して比較する。
                 */
                HeadshopManager.PoolHead original = pool.stream()
                        .filter(head -> head.id() == originalId)
                        .findFirst()
                        .orElse(null);

                if (original != null) {
                    ItemStack originalItem =
                            plugin.getHeadshopManager()
                                    .createHeadItem(original);

                    if (currentItem.isSimilar(originalItem)) {
                        continue;
                    }
                }

                /*
                 * 商品が別アイテムに置き換えられた。
                 * 古い商品を消して、下で新商品として登録する。
                 */
                plugin.getHeadshopManager()
                        .removeFromPool(originalId);

                originalIdsBySlot.remove(slot);
            }

            // ---------------------------
            // 空スロット
            // ---------------------------

            if (currentItem == null || currentItem.getType().isAir()) {
                continue;
            }

            /*
             * PLAYER_HEAD以外は登録不可。
             */
            if (currentItem.getType() != Material.PLAYER_HEAD
                    || !(currentItem.getItemMeta() instanceof SkullMeta)) {

                getInventory().setItem(slot, null);

                var leftovers =
                        player.getInventory().addItem(currentItem);

                leftovers.values().forEach(leftover ->
                        player.getWorld().dropItemNaturally(
                                player.getLocation(),
                                leftover
                        )
                );

                player.sendMessage(
                        plugin.getConfigManager()
                                .getMessage(
                                        "headshop.admin.invalid_head",
                                        player
                                )
                );

                continue;
            }

            /*
             * 同じtextureかどうかは見ない。
             *
             * このスロットに新しく置かれたheadは
             * 毎回1つの独立した商品としてDB登録する。
             */
            ItemStack oneItem = currentItem.clone();
            oneItem.setAmount(1);

            HeadshopManager.PoolHead added =
                    plugin.getHeadshopManager()
                            .addToPool(
                                    oneItem,
                                    player.getUniqueId()
                            );

            if (added == null) {
                player.sendMessage(
                        plugin.getConfigManager()
                                .getMessage(
                                        "headshop.admin.save-failed",
                                        player
                                )
                );

                continue;
            }

            originalIdsBySlot.put(slot, added.id());

            /*
             * amountが2以上なら、
             * 1個を商品登録して残りを元に戻す。
             *
             * これで1スタックが1商品扱いになるのを防ぐ。
             */
            if (currentItem.getAmount() > 1) {
                ItemStack remaining = currentItem.clone();
                remaining.setAmount(currentItem.getAmount() - 1);

                var leftovers =
                        player.getInventory().addItem(remaining);

                leftovers.values().forEach(leftover ->
                        player.getWorld().dropItemNaturally(
                                player.getLocation(),
                                leftover
                        )
                );
            }

            getInventory().setItem(
                    slot,
                    plugin.getHeadshopManager()
                            .createHeadItem(added)
            );

            player.sendMessage(
                    FormatUtil.replace(
                            plugin.getConfigManager()
                                    .getMessage(
                                            "headshop.admin.added",
                                            player
                                    ),
                            "%item%",
                            added.displayName()
                    )
            );
        }

        pool.clear();
        pool.addAll(
                plugin.getHeadshopManager().listPool()
        );
    }

    private ItemStack fillerItem() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);

        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());

        item.setItemMeta(meta);

        return item;
    }
}
