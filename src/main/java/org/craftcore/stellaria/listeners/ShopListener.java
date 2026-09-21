package org.craftcore.stellaria.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.gui.*;
import org.craftcore.stellaria.managers.*;
import org.craftcore.stellaria.utils.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * ショップ作成の設定状態とチェスト操作を扱う。
 */
public final class ShopListener implements Listener {
    public record CreateDraft(Block block, ShopManager.Mode mode, long price, ItemStack item) {
    }

    private enum Input {PRICE, FUNDS_DEPOSIT, FUNDS_WITHDRAW}

    private record Pending(Input input, ContainerLock.BlockKey shopKey, long expiresAt) {
    }

    private final StellariaCore plugin;
    private final Map<UUID, CreateDraft> drafts = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Set<UUID> awaitingChest = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> awaitingItem = new ConcurrentHashMap<>();

    public ShopListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    public void beginCreate(Player p) {
        awaitingChest.add(p.getUniqueId());
        msg(p, "shop.create_look_at_chest");
    }

    public CreateDraft draft(Player p) {
        return drafts.get(p.getUniqueId());
    }

    public void toggleMode(Player p) {
        CreateDraft d = draft(p);
        if (d != null)
            drafts.put(p.getUniqueId(), new CreateDraft(d.block(), d.mode() == ShopManager.Mode.BUY ? ShopManager.Mode.SELL : ShopManager.Mode.BUY, d.price(), d.item()));
    }

    public void requestPrice(Player p) {
        if (draft(p) != null) {
            Pending state = new Pending(Input.PRICE, null, expiresAt());
            pending.put(p.getUniqueId(), state);
            schedulePendingTimeout(p, state);
            p.closeInventory();
            msg(p, "shop.enter_price");
        }
    }

    public void requestItem(Player p) {
        if (draft(p) != null) {
            long expiry = expiresAt();
            awaitingItem.put(p.getUniqueId(), expiry);
            scheduleItemTimeout(p, expiry);
            p.closeInventory();
            msg(p, "shop.set_item_look_at_chest");
        }
    }

    public void requestShopPrice(Player p, ShopManager.Shop shop) {
        Pending state = new Pending(Input.PRICE, shop.key(), expiresAt());
        pending.put(p.getUniqueId(), state);
        schedulePendingTimeout(p, state);
        p.closeInventory();
        msg(p, "shop.enter_price");
    }

    public void toggleShopMode(Player p, ShopManager.Shop shop) {
        ShopManager.Mode mode = shop.mode() == ShopManager.Mode.BUY ? ShopManager.Mode.SELL : ShopManager.Mode.BUY;
        if (plugin.getShopManager().updateSettings(shop, mode, shop.price())) msg(p, "shop.settings_updated");
        else msg(p, "shop.settings_failed");
    }

    public void confirmCreate(Player p) {
        CreateDraft d = draft(p);
        if (d == null || d.price() < 1 || d.item() == null || d.item().getType().isAir()) {
            msg(p, "shop.create_incomplete");
            p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
            return;
        }
        if (!valid(d.block(), p)) return;
        long cost = plugin.getConfigManager().getInt("shop.creation-cost", 500);
        if (!plugin.getEconomyManager().has(p, cost)) {
            msg(p, "shop.insufficient_creation_funds");
            p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
            return;
        }
        if (plugin.getContainerLockManager().create(p, Set.of(ContainerLock.BlockKey.of(d.block()))) != ContainerLockManager.CreateResult.SUCCESS) {
            msg(p, "shop.lock_failed");
            p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
            return;
        }
        ShopManager.Shop s = plugin.getShopManager().create(p, d.block(), d.mode(), d.item(), d.price());
        if (s == null) {
            plugin.getContainerLockManager().find(d.block()).ifPresent(plugin.getContainerLockManager()::unlock);
            msg(p, "shop.create_failed");
            p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
            return;
        }
        if (!plugin.getEconomyManager().withdrawPlayer(p, cost).transactionSuccess()) {
            plugin.getShopManager().remove(s);
            msg(p, "shop.insufficient_creation_funds");
            p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
            return;
        }
        drafts.remove(p.getUniqueId());
        p.closeInventory();
        msg(p, "shop.created", "%mode%", d.mode().name(), "%price%", plugin.getEconomyManager().formatExact(d.price()));
        p.playSound(p, Sound.BLOCK_ANVIL_USE,1,2);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void interact(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null)
            return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        UUID id = p.getUniqueId();
        if (awaitingChest.remove(id)) {
            e.setCancelled(true);
            if (!plugin.getLandManager().canBuild(b.getLocation(),p)) {
                msg(p,"shop.land_protected");
                return;
            }
            if (!valid(b, p)) return;
            drafts.put(id, new CreateDraft(b, ShopManager.Mode.BUY, 0, null));
            new ShopCreateGui(plugin, this).open(p);
            return;
        }
        Long itemExpiry = awaitingItem.remove(id);
        if (itemExpiry != null) {
            e.setCancelled(true);
            if (expired(itemExpiry)) {
                msg(p, "shop.input_timeout");
                return;
            }
            CreateDraft d = draft(p);
            if (d == null || !same(d.block(), b)) {
                msg(p, "shop.set_item_wrong_chest");
                return;
            }
            ItemStack item = p.getInventory().getItemInMainHand();
            if (item.getType().isAir()) {
                msg(p, "shop.set_item_empty_hand");
                new ShopCreateGui(plugin, this).open(p);
                return;
            }
            drafts.put(id, new CreateDraft(d.block(), d.mode(), d.price(), item.clone()));
            new ShopCreateGui(plugin, this).open(p);
            return;
        }
        ShopManager.Shop s = plugin.getShopManager().find(b);
        if (s == null) return;
        e.setCancelled(true);
        if (s.owner().equals(id) && !p.isSneaking()) {
            new ShopManageGui(plugin, s, this).open(p);
            p.playSound(p, Sound.BLOCK_CHEST_OPEN,1,0.75f);
        }
        else if (!s.owner().equals(id) && !p.isSneaking()) {
            new ShopTransactionGui(plugin, s).open(p);
            p.playSound(p, Sound.BLOCK_CHEST_OPEN,1,0.75f);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void chat(AsyncChatEvent e) {
        Pending state = pending.get(e.getPlayer().getUniqueId());
        if (state == null) return;
        e.setCancelled(true);
        String v = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        e.getPlayer().getScheduler().run(plugin, t -> accept(e.getPlayer(), state, v), null);
    }

    public void removePending(Player player){
        pending.remove(player.getUniqueId());
    }

    public void cancelCreate(Player player) {
        UUID id = player.getUniqueId();

        drafts.remove(id);
        pending.remove(id);
        awaitingChest.remove(id);
        awaitingItem.remove(id);

        msg(player, "shop.create_cancel");
    }

    public boolean isCreateInputPending(Player player) {
        UUID id = player.getUniqueId();
        return pending.containsKey(id) || awaitingItem.containsKey(id);
    }

    private void accept(Player p, Pending s, String v) {
        if (pending.get(p.getUniqueId()) != s) return;
        if (expired(s.expiresAt())) {
            pending.remove(p.getUniqueId());
            msg(p, "shop.input_timeout");
            return;
        }
        if (v.equals("!")){
            pending.remove(p.getUniqueId());
            msg(p,"shop.cancel");
            return;
        }
        long n;
        try {
            n = Long.parseLong(v);
        } catch (NumberFormatException e) {
            msg(p, "shop.invalid_amount");
            return;
        }
        if (n < 1) {
            msg(p, "shop.invalid_amount");
            return;
        }
        pending.remove(p.getUniqueId());
        if (s.input() == Input.PRICE) {
            if (s.shopKey() != null) {
                // チャット入力待ちの間に他プレイヤーが取引していても、価格変更は常に最新のShopを基準に適用する。
                ShopManager.Shop latest = plugin.getShopManager().find(s.shopKey());
                if (latest == null) {
                    msg(p, "shop.settings_failed");
                    return;
                }
                int maxPrice = plugin.getConfigManager().getInt("shop.max-price", 10000000);

                if (n > maxPrice) {
                    p.sendMessage(
                            FormatUtil.replace(
                                    plugin.getConfigManager().getMessage("shop.high_price", p),
                                    "%price%",
                                    String.valueOf(maxPrice)
                            )
                    );
                    return;
                }
                if (plugin.getShopManager().updateSettings(latest, latest.mode(), n))
                    msg(p, "shop.settings_updated");
                else msg(p, "shop.settings_failed");
                return;
            }
            CreateDraft d = draft(p);
            if (d != null) {
                drafts.put(p.getUniqueId(), new CreateDraft(d.block(), d.mode(), n, d.item()));
                new ShopCreateGui(plugin, this).open(p);
            }
            return;
        }
        // 資金の入出金は必ず最新のShopを取り直してから増減させる。
        // pendingに古いShopのfunds/stockを保存していると、入力待ちの間に別プレイヤーが
        // 取引した分がここで巻き戻ってしまう(資金の増殖/消失バグの原因だった)。
        ShopManager.Shop latest = plugin.getShopManager().find(s.shopKey());
        if (latest == null) {
            msg(p, "shop.settings_failed");
            return;
        }
        if (s.input() == Input.FUNDS_DEPOSIT) {
            if (!plugin.getEconomyManager().withdrawPlayer(p, n).transactionSuccess()) {
                msg(p, "shop.insufficient_funds");
                return;
            }

            if (!plugin.getShopManager().addFunds(latest, n)) {
                // ショップ資金の更新に失敗したので返金
                plugin.getEconomyManager().depositPlayer(p, n);

                msg(p, "shop.settings_failed");
                return;
            }

            msg(
                    p,
                    "shop.funds_deposited",
                    "%amount%",
                    plugin.getEconomyManager().formatExact(n)
            );

            return;
        }
        if (n > latest.funds()) {
            msg(p, "shop.insufficient_pool");
            return;
        }
        if (!plugin.getShopManager().addFunds(latest, -n)) {
            msg(p, "shop.settings_failed");
            return;
        }

        if (!plugin.getEconomyManager().depositPlayer(p, n).transactionSuccess()) {
            // プレイヤーへの入金に失敗したのでショップへ戻す
            ShopManager.Shop afterWithdraw = plugin.getShopManager().find(s.shopKey());
            if (afterWithdraw != null) plugin.getShopManager().addFunds(afterWithdraw, n);

            msg(p, "shop.settings_failed");
            return;
        }

        msg(
                p,
                "shop.funds_withdrawn",
                "%amount%",
                plugin.getEconomyManager().formatExact(n)
        );
    }

    public void requestFunds(Player p, ShopManager.Shop s, boolean deposit) {
        Pending state = new Pending(deposit ? Input.FUNDS_DEPOSIT : Input.FUNDS_WITHDRAW, s.key(), expiresAt());
        pending.put(p.getUniqueId(), state);
        schedulePendingTimeout(p, state);
        p.closeInventory();
        msg(p, deposit ? "shop.enter_deposit" : "shop.enter_withdraw");
    }

    private boolean valid(Block b, Player p) {
        if (b.getType() != Material.CHEST || !(b.getBlockData() instanceof org.bukkit.block.data.type.Chest c) || c.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
            msg(p, "shop.not_single_chest");
            return false;
        }
        if (plugin.getContainerLockManager().isLocked(b)) {
            msg(p, "shop.already_locked");
            return false;
        }
        if (plugin.getShopManager().count(p.getUniqueId()) >= plugin.getConfigManager().getInt("shop.max-per-player", 10)) {
            msg(p, "shop.limit_reached");
            return false;
        }
        return true;
    }

    private static boolean same(Block a, Block b) {
        return a.getWorld().equals(b.getWorld()) && a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
    }

    private long expiresAt() {
        return System.currentTimeMillis() + plugin.getConfigManager().getInt("shop.input-timeout-seconds", 60) * 1000L;
    }

    private static boolean expired(long time) {
        return System.currentTimeMillis() > time;
    }

    private void schedulePendingTimeout(Player p, Pending state) {
        p.getScheduler().runDelayed(plugin, t -> {
            if (pending.remove(p.getUniqueId(), state)) msg(p, "shop.input_timeout");
        }, null, Math.max(1, plugin.getConfigManager().getInt("shop.input-timeout-seconds", 60) * 20L));
    }

    private void scheduleItemTimeout(Player p, long expiry) {
        p.getScheduler().runDelayed(plugin, t -> {
            if (awaitingItem.remove(p.getUniqueId(), expiry)) msg(p, "shop.input_timeout");
        }, null, Math.max(1, plugin.getConfigManager().getInt("shop.input-timeout-seconds", 60) * 20L));
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        // オフライン中に削除されたショップの残り在庫を、ログイン時にまとめて渡す。
        plugin.getShopManager().deliverPendingReturns(e.getPlayer());
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        UUID i = e.getPlayer().getUniqueId();
        drafts.remove(i);
        pending.remove(i);
        awaitingChest.remove(i);
        awaitingItem.remove(i);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventDoubleChest(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.CHEST) return;
        for (Block n : new Block[]{e.getBlockPlaced().getRelative(1, 0, 0), e.getBlockPlaced().getRelative(-1, 0, 0), e.getBlockPlaced().getRelative(0, 0, 1), e.getBlockPlaced().getRelative(0, 0, -1)})
            if (plugin.getShopManager().find(n) != null) {
                e.setCancelled(true);
                msg(e.getPlayer(), "shop.double_chest_denied");
                return;
            }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void preventNonOwnerShopBreak(BlockBreakEvent event) {
        ShopManager.Shop shop = plugin.getShopManager().find(event.getBlock());

        if (shop == null) {
            return;
        }

        Player player = event.getPlayer();

        if (shop.owner().equals(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);

        player.sendMessage(
                ColorUtil.component(
                        plugin.getConfigManager().getMessage(
                                "shop.break_not_owner",
                                player
                        )
                )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void destroyed(BlockBreakEvent e) {
        ShopManager.Shop shop = plugin.getShopManager().find(e.getBlock());

        if (shop == null) {
            return;
        }

        // ショップ所有者以外が壊した場合はここでは削除しない
        if (!shop.owner().equals(e.getPlayer().getUniqueId())) {
            return;
        }

        plugin.getServer()
                .getGlobalRegionScheduler()
                .run(plugin, task -> {
                    if (!plugin.getShopManager().remove(shop)) {
                        // DB削除に失敗すると幽霊ショップ(DB/Display/Lockが残存)になるため、
                        // チェスト本体はすでに壊れている旨と合わせてログへ残す。
                        plugin.getLogger().warning("ショップ(id=" + shop.id() + ")のDB削除に失敗しました。チェストは破壊済みのため、幽霊ショップが残っている可能性があります。");
                        msg(e.getPlayer(), "shop.remove_failed");
                    }
                });
    }

    private void msg(Player p, String k, String... r) {
        String s = plugin.getConfigManager().getMessage(k, p);
        for (int i = 0; i + 1 < r.length; i += 2) s = FormatUtil.replace(s, r[i], r[i + 1]);
        p.sendMessage(ColorUtil.component(s));
    }
}
