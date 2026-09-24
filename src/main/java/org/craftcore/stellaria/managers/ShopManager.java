package org.craftcore.stellaria.managers;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * チェストショップの永続化、キャッシュ、取引を管理する。
 */
public final class ShopManager {
    public enum Mode {BUY, SELL}

    public enum TradeResult {SUCCESS, NOT_ENOUGH_STOCK, NOT_ENOUGH_FUNDS, NOT_ENOUGH_MONEY, NOT_ENOUGH_ITEMS, INVENTORY_FULL, FAILED}

    public record Shop(int id, UUID owner, ContainerLock.BlockKey key, Mode mode, ItemStack item, long price, int stock,
                       long funds, UUID itemDisplay, UUID textDisplay) {
    }

    private final StellariaCore plugin;
    private final Map<ContainerLock.BlockKey, Shop> shops = new ConcurrentHashMap<>();

    public ShopManager(StellariaCore plugin) {
        this.plugin = plugin;
        loadAll();
    }

    private void loadAll() {
        for (Shop row : DatabaseManager.query("SELECT * FROM shops", rs -> {
            try {
                return new Shop(
                        rs.getInt("id"), UUID.fromString(rs.getString("owner_uuid")),
                        new ContainerLock.BlockKey(rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z")),
                        Mode.valueOf(rs.getString("mode")), deserialize(rs.getString("item_data")), rs.getLong("price"),
                        rs.getInt("stock"), rs.getLong("funds"), uuid(rs.getString("display_item_uuid")), uuid(rs.getString("display_text_uuid")));
            } catch (Exception e) {
                plugin.getLogger().warning("ショップデータ(id=" + safeId(rs) + ")の読み込みに失敗したためスキップします: " + e.getMessage());
                return null;
            }
        })) {
            if (row != null) shops.put(row.key(), row);
        }
    }

    private static int safeId(java.sql.ResultSet rs) {
        try {
            return rs.getInt("id");
        } catch (Exception e) {
            return -1;
        }
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    public Shop find(org.bukkit.block.Block block) {
        return shops.get(ContainerLock.BlockKey.of(block));
    }

    public Shop find(ContainerLock.BlockKey key) {
        return shops.get(key);
    }

    public List<Shop> list(UUID owner) {
        return shops.values().stream().filter(s -> s.owner().equals(owner)).sorted(Comparator.comparingInt(Shop::id)).toList();
    }

    public int count(UUID owner) {
        return (int) shops.values().stream().filter(s -> s.owner().equals(owner)).count();
    }

    public Shop create(Player owner, org.bukkit.block.Block block, Mode mode, ItemStack item, long price) {
        ContainerLock.BlockKey key = ContainerLock.BlockKey.of(block);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("owner_uuid", owner.getUniqueId().toString());
        values.put("world", key.world());
        values.put("x", key.x());
        values.put("y", key.y());
        values.put("z", key.z());
        values.put("mode", mode.name());
        values.put("item_data", serialize(one(item)));
        values.put("price", price);
        values.put("stock", 0);
        values.put("funds", 0);
        values.put("created_at", System.currentTimeMillis());
        OptionalInt id = DatabaseManager.insertAndGetId("shops", values);
        if (id.isEmpty()) return null;
        Shop shop = new Shop(id.getAsInt(), owner.getUniqueId(), key, mode, one(item), price, 0, 0, null, null);
        shops.put(key, shop);
        return createDisplays(shop);
    }

    public boolean addStock(Shop shop, int amount) {
        return update(shop, shop.stock() + amount, shop.funds());
    }

    public boolean addFunds(Shop shop, long amount) {
        return update(shop, shop.stock(), shop.funds() + amount);
    }

    /**
     * 種別または単価を変更し、キャッシュと浮遊表示を同期する。
     */
    public boolean updateSettings(Shop old, Mode mode, long price) {
        if (price < 1 || DatabaseManager.update("shops", Map.of("mode", mode.name(), "price", price), "id = ?", old.id()) != 1)
            return false;
        Shop updated = new Shop(old.id(), old.owner(), old.key(), mode, old.item(), price, old.stock(), old.funds(), old.itemDisplay(), old.textDisplay());
        shops.put(old.key(), updated);
        updateDisplay(updated);
        return true;
    }

    private boolean update(Shop old, int stock, long funds) {
        if (stock < 0 || funds < 0 || DatabaseManager.update("shops", Map.of("stock", stock, "funds", funds), "id = ?", old.id()) != 1)
            return false;
        Shop updated = new Shop(old.id(), old.owner(), old.key(), old.mode(), old.item(), old.price(), stock, funds, old.itemDisplay(), old.textDisplay());
        shops.put(old.key(), updated);
        updateDisplay(updated);
        return true;
    }

    public TradeResult trade(Player player, Shop old, int quantity) {
        if (old == null) return TradeResult.FAILED;
        if (quantity < 1 || quantity > old.item().getMaxStackSize() * 16) {
            return TradeResult.FAILED;
        }
        if (old.mode() == Mode.BUY) {
            if (old.stock() < quantity) {
                return TradeResult.NOT_ENOUGH_STOCK;
            }

            int tradeQuantity = quantity;

            long total;
            try {
                total = Math.multiplyExact(old.price(), tradeQuantity);
            } catch (ArithmeticException e) {
                return TradeResult.FAILED;
            }
            if (!plugin.getEconomyManager().has(player, total)) {
                return TradeResult.NOT_ENOUGH_MONEY;
            }
            if (!canFit(player, old.item(), tradeQuantity)) {
                return TradeResult.INVENTORY_FULL;
            }
            boolean done = DatabaseManager.transaction(c -> {
                require(DatabaseManager.execute(
                        "UPDATE shops SET stock = stock - ? WHERE id = ? AND stock >= ?",
                        tradeQuantity, old.id(), tradeQuantity
                ));
                require(DatabaseManager.execute(
                        "UPDATE players SET coins = coins - ? WHERE uuid = ? AND coins >= ?",
                        total, player.getUniqueId().toString(), total
                ));
                require(DatabaseManager.execute(
                        "UPDATE players SET coins = coins + ? WHERE uuid = ?",
                        total, old.owner().toString()
                ));
            });
            if (!done) return TradeResult.FAILED;
            give(player, old.item(), tradeQuantity);
            plugin.getEconomyManager().invalidateBalance(player.getUniqueId());
            plugin.getEconomyManager().invalidateBalance(old.owner());
            updateCached(
                    old,
                    old.stock() - tradeQuantity,
                    old.funds()
            );
            notifyOwner(old, player, tradeQuantity, total, old.stock() - tradeQuantity, old.funds());
            return TradeResult.SUCCESS;
        }
        // SELL
        long total;
        try {
            total = Math.multiplyExact(old.price(), quantity);
        } catch (ArithmeticException e) {
            return TradeResult.FAILED;
        }
        if (old.funds() < total) {
            return TradeResult.NOT_ENOUGH_FUNDS;
        }
        if (countSimilar(player, old.item()) < quantity) {
            return TradeResult.NOT_ENOUGH_ITEMS;
        }
        boolean done = DatabaseManager.transaction(c -> {
            require(DatabaseManager.execute(
                    "UPDATE shops SET stock = stock + ?, funds = funds - ? WHERE id = ? AND funds >= ?",
                    quantity, total, old.id(), total
            ));
            require(DatabaseManager.execute(
                    "UPDATE players SET coins = coins + ? WHERE uuid = ?",
                    total, player.getUniqueId().toString()
            ));
        });
        if (!done) return TradeResult.FAILED;
        take(player, old.item(), quantity);
        plugin.getEconomyManager().invalidateBalance(player.getUniqueId());
        updateCached(
                old,
                old.stock() + quantity,
                old.funds() - total
        );
        notifyOwner(old, player, quantity, total, old.stock() + quantity, old.funds() - total);
        return TradeResult.SUCCESS;
    }

    private static void require(int affected) {
        if (affected != 1) throw new IllegalStateException("ショップ更新に失敗");
    }

    private void updateCached(Shop old, int stock, long funds) {
        Shop now = new Shop(old.id(), old.owner(), old.key(), old.mode(), old.item(), old.price(), stock, funds, old.itemDisplay(), old.textDisplay());
        shops.put(now.key(), now);
        updateDisplay(now);
    }

    public boolean remove(Shop shop) {
        Player owner = Bukkit.getPlayer(shop.owner());
        boolean queueOfflineStock = owner == null && shop.stock() > 0;

        // shops削除と、オフライン在庫の返却キューへのINSERTを1トランザクションにまとめる。
        // INSERTが失敗した場合はDELETEもロールバックされ、ショップが残るので在庫は消えない
        // (呼び出し元は削除失敗として扱い、再試行できる)。
        boolean ok = DatabaseManager.transaction(conn -> {
            if (DatabaseManager.execute("DELETE FROM shops WHERE id = ?", shop.id()) != 1) {
                throw new IllegalStateException("shops の削除に失敗しました (id=" + shop.id() + ")");
            }
            if (queueOfflineStock) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("owner_uuid", shop.owner().toString());
                values.put("item_data", serialize(shop.item()));
                values.put("amount", shop.stock());
                values.put("created_at", System.currentTimeMillis());
                if (DatabaseManager.insert("shop_pending_returns", values) != 1) {
                    throw new IllegalStateException("shop_pending_returns へのINSERTに失敗しました (owner=" + shop.owner() + ")");
                }
            }
        });

        if (!ok) {
            plugin.getLogger().severe("ショップ(id=" + shop.id() + ")の削除に失敗したため処理を中断しました。ショップは残存しています。");
            return false;
        }

        shops.remove(shop.key());
        removeDisplays(shop);

        plugin.getContainerLockManager()
                .find(shop.key())
                .ifPresent(plugin.getContainerLockManager()::unlock);

        if (owner != null) {
            plugin.getShopListener().removePending(owner);

            giveOrDrop(owner, shop.item(), shop.stock());

            if (shop.funds() > 0) {
                plugin.getEconomyManager().depositPlayer(owner, shop.funds());
            }
        } else {
            // オーナーがオフラインでも資金だけはUUIDで返金できるようにする
            if (shop.funds() > 0) {
                plugin.getEconomyManager().depositPlayer(
                        Bukkit.getOfflinePlayer(shop.owner()),
                        shop.funds()
                );
            }
            // 在庫は上のtransactionでshop_pending_returnsへ積み済み
        }

        return true;
    }

    /**
     * オフライン中に削除されたショップの残り在庫を、ログイン時にまとめて渡す。
     * 重複付与を避けるため、DELETEが成功したことを確認してからアイテムを渡す
     * (DELETEに失敗した分は次回ログイン時に再試行される)。
     */
    public void deliverPendingReturns(Player player) {
        for (var row : DatabaseManager.query(
                "SELECT id, item_data, amount FROM shop_pending_returns WHERE owner_uuid = ?",
                rs -> new Object[]{rs.getInt("id"), deserialize(rs.getString("item_data")), rs.getInt("amount")},
                player.getUniqueId().toString())) {
            int id = (int) row[0];
            ItemStack item = (ItemStack) row[1];
            int amount = (int) row[2];

            if (DatabaseManager.execute("DELETE FROM shop_pending_returns WHERE id = ?", id) != 1) {
                plugin.getLogger().warning("shop_pending_returns(id=" + id + ")の削除に失敗したため、今回は付与をスキップします(次回ログイン時に再試行されます)。");
                continue;
            }

            giveOrDrop(player, item, amount);
        }
    }

    /**
     * 取引成立をオーナーへ知らせる。オンラインなら即座にチャットで、オフラインならshop_sale_notificationsへ積み、
     * 次回ログイン時にdeliverSaleNotificationsでまとめて表示する。
     * 取引自体は既に確定しているため、ここでの失敗（INSERT失敗など）は取引を巻き戻さず、ログに残すだけにする。
     */
    private void notifyOwner(Shop shop, Player customer, int amount, long total, int stockAfter, long fundsAfter) {
        if (shop.owner().equals(customer.getUniqueId())) {
            return;
        }
        Player owner = Bukkit.getPlayer(shop.owner());
        if (owner != null) {
            String path = shop.mode() == Mode.BUY ? "shop.notify.sold" : "shop.notify.bought";
            owner.sendMessage(saleMessage(path, owner, shop.item(), Map.of(
                    "%customer%", customer.getName(),
                    "%amount%", Integer.toString(amount),
                    "%total%", plugin.getEconomyManager().format(total),
                    "%stock%", Integer.toString(stockAfter),
                    "%funds%", plugin.getEconomyManager().format(fundsAfter))));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("owner_uuid", shop.owner().toString());
        values.put("shop_id", shop.id());
        values.put("mode", shop.mode().name());
        values.put("item_data", serialize(shop.item()));
        values.put("amount", amount);
        values.put("total", total);
        values.put("created_at", System.currentTimeMillis());
        DatabaseManager.insertAsync("shop_sale_notifications", values);
    }

    private record SaleSummary(Mode mode, ItemStack item, long amount, long total, int maxId) {
    }

    /**
     * オフライン中に成立した取引を、ショップ×アイテムごとに個数・金額を合算して表示する。
     * ログイン直後のメッセージに埋もれないよう少し遅らせて送る。表示前に読んだ分（id <= 読んだ最大id）だけを
     * 削除するので、この処理の最中に成立した取引の通知は消えずに次回へ持ち越される。
     * 削除に失敗した場合は、次回ログイン時の二重表示を避けるため今回は表示しない。
     */
    public void deliverSaleNotifications(Player player) {
        String uuid = player.getUniqueId().toString();
        List<SaleSummary> rows = DatabaseManager.query(
                "SELECT mode, item_data, SUM(amount) AS amount, SUM(total) AS total, MAX(id) AS max_id "
                        + "FROM shop_sale_notifications WHERE owner_uuid = ? "
                        + "GROUP BY shop_id, mode, item_data ORDER BY MIN(id)",
                rs -> {
                    ItemStack item;
                    try {
                        item = deserialize(rs.getString("item_data"));
                    } catch (IllegalStateException e) {
                        item = null;
                    }
                    return new SaleSummary(Mode.valueOf(rs.getString("mode")), item,
                            rs.getLong("amount"), rs.getLong("total"), rs.getInt("max_id"));
                },
                uuid);
        if (rows.isEmpty()) {
            return;
        }
        int maxId = rows.stream().mapToInt(SaleSummary::maxId).max().orElseThrow();
        if (DatabaseManager.execute("DELETE FROM shop_sale_notifications WHERE owner_uuid = ? AND id <= ?", uuid, maxId) < 1) {
            plugin.getLogger().warning("shop_sale_notifications(owner=" + uuid + ")の削除に失敗したため、今回は通知をスキップします(次回ログイン時に再試行されます)。");
            return;
        }

        List<Component> lines = new ArrayList<>();
        lines.add(ColorUtil.component(plugin.getConfigManager().getMessage("shop.notify.offline_header", player)));
        long net = 0;
        for (SaleSummary row : rows) {
            boolean sold = row.mode() == Mode.BUY;
            net += sold ? row.total() : -row.total();
            lines.add(saleMessage(sold ? "shop.notify.offline_line_sold" : "shop.notify.offline_line_bought", player, row.item(), Map.of(
                    "%amount%", Long.toString(row.amount()),
                    "%total%", plugin.getEconomyManager().format(row.total()))));
        }
        String netText = (net >= 0 ? "+" : "-") + plugin.getEconomyManager().format(Math.abs(net));
        lines.add(ColorUtil.component(plugin.getConfigManager().getMessage("shop.notify.offline_total", player).replace("%net%", netText)));

        player.getScheduler().runDelayed(plugin, task -> lines.forEach(player::sendMessage), null, 40L);
    }

    /**
     * messages.ymlの文言に文字列プレースホルダーを埋めた上で、%item%をアイテム名のComponentに差し替える。
     * アイテム名はクライアントの言語で表示されるよう翻訳キーのまま渡す（名前付きアイテムならその名前）。
     */
    private Component saleMessage(String path, Player viewer, ItemStack item, Map<String, String> replacements) {
        String text = plugin.getConfigManager().getMessage(path, viewer);
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        Component itemName = item != null ? item.effectiveName() : Component.text("?");
        return ColorUtil.component(text).replaceText(b -> b.matchLiteral("%item%").replacement(itemName));
    }

    private Shop createDisplays(Shop shop) {
        org.bukkit.World world = Bukkit.getWorld(shop.key().world());
        if (world == null) return shop;
        Location base = new Location(world, shop.key().x() + .5, shop.key().y() + plugin.getConfigManager().getDouble("shop.display.y-offset", 1.2), shop.key().z() + .5);
        ItemDisplay item = world.spawn(base, ItemDisplay.class, e -> {
            e.setItemStack(shop.item());
            e.setBillboard(Display.Billboard.VERTICAL);
            e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(.5f, .5f, .5f), new AxisAngle4f()));
            e.setViewRange((float) plugin.getConfigManager().getDouble("shop.display.view-range", 16));
        });
        TextDisplay text = world.spawn(base.clone().add(0, plugin.getConfigManager().getDouble("shop.display.text-offset", .45), 0), TextDisplay.class, e -> {
            e.text(org.craftcore.stellaria.utils.ColorUtil.component(label(shop)));
            e.setBillboard(Display.Billboard.CENTER);
            e.setViewRange((float) plugin.getConfigManager().getDouble("shop.display.view-range", 16));
        });
        int affected = DatabaseManager.update("shops", Map.of("display_item_uuid", item.getUniqueId().toString(), "display_text_uuid", text.getUniqueId().toString()), "id = ?", shop.id());
        if (affected <= 0) {
            plugin.getLogger().warning("ショップ(id=" + shop.id() + ")のdisplay UUID保存に失敗したため、生成したdisplayを破棄します。");
            item.remove();
            text.remove();
            return shop;
        }
        Shop updated = new Shop(shop.id(), shop.owner(), shop.key(), shop.mode(), shop.item(), shop.price(), shop.stock(), shop.funds(), item.getUniqueId(), text.getUniqueId());
        shops.put(updated.key(), updated);
        return updated;
    }

    private void updateDisplay(Shop shop) {
        if (shop.textDisplay() != null) {
            var entity = Bukkit.getEntity(shop.textDisplay());
            if (entity instanceof TextDisplay text)
                text.text(org.craftcore.stellaria.utils.ColorUtil.component(label(shop)));
        }
    }

    private String label(Shop shop) {
        String modeLabel = shop.mode() == Mode.BUY ? "販売" : "買取";
        return plugin.getConfigManager().getString("shop.display." + shop.mode().name().toLowerCase() + "-format", "&%e%mode% &%f%price%円 &%7在庫:%stock%")
                .replace("%mode%", modeLabel)
                .replace("%price%", plugin.getEconomyManager().formatExact(shop.price())).replace("%stock%", Integer.toString(shop.stock()));
    }

    private void removeDisplays(Shop shop) {
        if (shop.itemDisplay() != null) {
            var e = Bukkit.getEntity(shop.itemDisplay());
            if (e != null) e.remove();
        }
        if (shop.textDisplay() != null) {
            var e = Bukkit.getEntity(shop.textDisplay());
            if (e != null) e.remove();
        }
    }

    private static ItemStack one(ItemStack item) {
        ItemStack copy = item.clone();
        copy.setAmount(1);
        return copy;
    }

    private static String serialize(ItemStack item) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); BukkitObjectOutputStream data = new BukkitObjectOutputStream(out)) {
            data.writeObject(item);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("アイテムを保存できません", e);
        }
    }

    private static ItemStack deserialize(String text) {
        try (BukkitObjectInputStream data = new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(text)))) {
            return (ItemStack) data.readObject();
        } catch (Exception e) {
            throw new IllegalStateException("ショップアイテムを読み込めません", e);
        }
    }

    private static int countSimilar(Player p, ItemStack sample) {
        int n = 0;
        for (ItemStack i : p.getInventory().getContents()) if (i != null && i.isSimilar(sample)) n += i.getAmount();
        return n;
    }

    private static boolean canFit(Player p, ItemStack sample, int amount) {
        int capacity = 0;
        for (ItemStack item : p.getInventory().getStorageContents()) {
            if (item == null || item.getType().isAir()) capacity += sample.getMaxStackSize();
            else if (item.isSimilar(sample)) capacity += item.getMaxStackSize() - item.getAmount();
            if (capacity >= amount) return true;
        }
        return false;
    }

    private static void take(Player p, ItemStack sample, int amount) {
        for (int slot = 0; slot < p.getInventory().getSize() && amount > 0; slot++) {
            ItemStack i = p.getInventory().getItem(slot);
            if (i == null || !i.isSimilar(sample)) continue;
            int use = Math.min(amount, i.getAmount());
            i.setAmount(i.getAmount() - use);
            if (i.getAmount() == 0) p.getInventory().setItem(slot, null);
            amount -= use;
        }
    }

    private static void give(Player p, ItemStack sample, int amount) {
        while (amount > 0) {
            ItemStack i = sample.clone();
            int n = Math.min(amount, i.getMaxStackSize());
            i.setAmount(n);
            p.getInventory().addItem(i).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
            amount -= n;
        }
    }

    private static void giveOrDrop(Player p, ItemStack sample, int amount) {
        give(p, sample, amount);
    }
}
