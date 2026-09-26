package org.craftcore.stellaria.managers;

import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.MoneyFormat;

import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EconomyManager extends AbstractEconomy {

    private final StellariaCore plugin;
    private static final long BALANCE_CACHE_TTL_MILLIS = 2000;
    private final Map<UUID, long[]> balanceCache = new ConcurrentHashMap<>(); // [coins, cachedAtMillis]

    public EconomyManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** 同一トランザクション内で残高を更新した機能から呼ぶキャッシュ無効化用。 */
    /**
     * 稼いだお金（/ranking earned と独自進捗の economy.earned）に加算する。入金が確定した後に呼ぶこと。
     * 返金・ショップ資金の出し入れのような「自分のお金が戻っただけ」の入金では呼ばない。
     */
    public void recordEarning(UUID playerId, double amount) {
        AdvancementManager advancements = plugin.getAdvancementManager();
        if (advancements != null) {
            advancements.addToCounter(playerId, EarningsStore.KEY, (long) Math.floor(amount));
        }
    }

    public void invalidateBalance(UUID playerId) {
        balanceCache.remove(playerId);
    }

    private static boolean isWholeNonNegativeAmount(double amount) {
        return Double.isFinite(amount) && amount >= 0 && amount <= Long.MAX_VALUE && amount == Math.rint(amount);
    }

    // -------------------------------------------------------------
    // Vaultの設定・基本情報
    // -------------------------------------------------------------

    @Override
    public boolean isEnabled() {
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public String getName() {
        return "StellariaEconomy";
    }

    @Override
    public boolean hasBankSupport() {
        return false; // 銀行機能を使わない場合
    }

    @Override
    public int fractionalDigits() {
        return 0; // 小数点以下桁数（整数なら0）
    }

    @Override
    public String format(double amount) {
        return MoneyFormat.format(amount) + "円";
    }

    /** 丸めずカンマ区切りの実数で表す。{@link #format(double)} は万/億/兆に丸めるため、
     * 残高確認や送金・管理者操作の通知など正確な金額を見せるべき箇所ではこちらを使う。 */
    public String formatExact(double amount) {
        return MoneyFormat.formatExact(amount) + "円";
    }

    @Override
    public String currencyNamePlural() {
        return "円";
    }

    @Override
    public String currencyNameSingular() {
        return "円";
    }

    // -------------------------------------------------------------
    // 残高確認（getBalance）
    // -------------------------------------------------------------

    @Override
    public double getBalance(OfflinePlayer player) {
        if (player == null) return 0;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long[] cached = balanceCache.get(uuid);
        if (cached != null && now - cached[1] < BALANCE_CACHE_TTL_MILLIS) {
            return cached[0];
        }
        Long coins = DatabaseManager.queryOne(
            "SELECT coins FROM players WHERE uuid = ?",
            rs -> rs.getLong("coins"),
            uuid.toString()
        );
        long value = (coins != null) ? coins : 0;
        balanceCache.put(uuid, new long[]{value, now});
        return value;
    }

    @Override
    public double getBalance(String playerName) {
        return getBalance(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return isWholeNonNegativeAmount(amount) && getBalance(player) >= amount;
    }

    @Override
    public boolean has(String playerName, double amount) {
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    // -------------------------------------------------------------
    // 引き落とし（withdrawPlayer）
    // -------------------------------------------------------------

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (player == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "プレイヤーが見つかりません");
        }
        if (!isWholeNonNegativeAmount(amount)) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "0以上の有限な整数を指定してください");
        }

        long amountLong = (long) amount;
        int affected = DatabaseManager.execute(
            "UPDATE players SET coins = coins - ? WHERE uuid = ? AND coins >= ?",
            amountLong, player.getUniqueId().toString(), amountLong
        );
        balanceCache.remove(player.getUniqueId());
        if (affected <= 0) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "残高が足りません");
        }

        return new EconomyResponse(amount, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdrawPlayer(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    // -------------------------------------------------------------
    // 預け入れ・加算（depositPlayer）
    // -------------------------------------------------------------

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (player == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "プレイヤーが見つかりません");
        }
        if (!isWholeNonNegativeAmount(amount)) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "0以上の有限な整数を指定してください");
        }

        long amountLong = (long) amount;
        int affected = DatabaseManager.execute(
            "UPDATE players SET coins = coins + ? WHERE uuid = ? AND coins <= ?",
            amountLong, player.getUniqueId().toString(), Long.MAX_VALUE - amountLong
        );
        if (affected <= 0) {
            // UUIDに対応する行がまだ存在しない（未ログインの投票者など）場合はここに来る。
            // 行を作ってから一度だけ再試行し、それでも失敗すればFAILUREを返す。
            ensurePlayerRecord(player);
            affected = DatabaseManager.execute(
                "UPDATE players SET coins = coins + ? WHERE uuid = ? AND coins <= ?",
                amountLong, player.getUniqueId().toString(), Long.MAX_VALUE - amountLong
            );
        }
        balanceCache.remove(player.getUniqueId());
        if (affected <= 0) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "プレイヤーの残高レコードを作成できませんでした");
        }

        return new EconomyResponse(amount, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
    }

    /**
     * 未ログインの投票者など、players テーブルにまだ行がないプレイヤーにも入金できるようにする。
     * 初期所持金を持つ行を先に作るため、初回ログイン時にも通常どおり初期残高を維持できる。
     */
    public void ensurePlayerRecord(OfflinePlayer player) {
        String name = player.getName();
        if (name == null || name.isBlank()) {
            name = player.getUniqueId().toString();
        }

        DatabaseManager.execute(
            "INSERT OR IGNORE INTO players (uuid, name, coins) VALUES (?, ?, ?)",
            player.getUniqueId().toString(),
            name,
            plugin.getConfigManager().getInt("economy.default-balance", 1000)
        );
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return depositPlayer(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    // -------------------------------------------------------------
    // 独自拡張（Vault標準APIに無い操作）
    // -------------------------------------------------------------

    /** 残高を指定額に設定する（Vaultの標準APIには無い操作）。マイナス指定は禁止。 */
    public boolean setBalance(OfflinePlayer player, double amount) {
        if (player == null || !isWholeNonNegativeAmount(amount)) {
            return false;
        }
        long newBalance = (long) amount;
        int affected = DatabaseManager.update("players", java.util.Map.of("coins", newBalance), "uuid = ?", player.getUniqueId().toString());
        if (affected <= 0) {
            ensurePlayerRecord(player);
            affected = DatabaseManager.update("players", java.util.Map.of("coins", newBalance), "uuid = ?", player.getUniqueId().toString());
        }
        balanceCache.remove(player.getUniqueId());
        return affected > 0;
    }

    /** 対象プレイヤーの所持金を他者から非公開にしているかを返す。 */
    public boolean isHideBalance(OfflinePlayer player) {
        if (player == null) {
            return false;
        }
        Integer hidden = DatabaseManager.queryOne(
                "SELECT hide_balance FROM players WHERE uuid = ?",
                rs -> rs.getInt("hide_balance"),
                player.getUniqueId().toString()
        );
        return hidden != null && hidden != 0;
    }

    /** プレイヤー自身の所持金公開設定を更新する。連打時の順序逆転を避けるため同期で書き込み、成否を返す。 */
    public boolean setHideBalance(Player player, boolean hidden) {
        int affected = DatabaseManager.update("players", Map.of("hide_balance", hidden ? 1 : 0), "uuid = ?", player.getUniqueId().toString());
        return affected > 0;
    }

    /**
     * from -> to へ amount を送金する。from の残高が不足していれば何もせず false を返す
     * （DatabaseManager.transaction() 内で条件付きUPDATEを使い、残高不足を例外でロールバックの
     * トリガーにすることでアトミック性を確保する）。
     */
    public boolean transfer(OfflinePlayer from, OfflinePlayer to, double amount) {
        if (from == null || to == null || !isWholeNonNegativeAmount(amount) || amount == 0) {
            return false;
        }
        long amountLong = (long) amount;
        return DatabaseManager.transaction(conn -> {
            int affected = DatabaseManager.execute(
                "UPDATE players SET coins = coins - ? WHERE uuid = ? AND coins >= ?",
                amountLong, from.getUniqueId().toString(), amountLong
            );
            balanceCache.remove(from.getUniqueId());
            if (affected <= 0) {
                throw new IllegalStateException("残高不足のため送金を中止");
            }
            int creditAffected = DatabaseManager.execute("UPDATE players SET coins = coins + ? WHERE uuid = ? AND coins <= ?",
                    amountLong, to.getUniqueId().toString(), Long.MAX_VALUE - amountLong);
            if (creditAffected <= 0) {
                // toのUUIDにまだplayers行が無い場合はここに来る。行を作ってから一度だけ再試行し、
                // それでも失敗すればロールバックさせて送金を無かったことにする（fromからの引き落としだけが
                // 残ってしまう事態を避ける）。
                ensurePlayerRecord(to);
                creditAffected = DatabaseManager.execute("UPDATE players SET coins = coins + ? WHERE uuid = ? AND coins <= ?",
                        amountLong, to.getUniqueId().toString(), Long.MAX_VALUE - amountLong);
            }
            balanceCache.remove(to.getUniqueId());
            if (creditAffected <= 0) {
                throw new IllegalStateException("送金先の残高レコードを作成できなかったため送金を中止");
            }
        });
    }

    /** /balance top のランキング1行分。 */
    public record BalanceEntry(UUID uuid, String name, long coins) {
    }

    /** 残高降順で limit 件、offset 件スキップして取得する（/balance top のページング用）。 */
    public List<BalanceEntry> getTopBalances(int limit, int offset) {
        return DatabaseManager.query(
            "SELECT uuid, name, coins FROM players ORDER BY coins DESC LIMIT ? OFFSET ?",
            rs -> new BalanceEntry(UUID.fromString(rs.getString("uuid")), rs.getString("name"), rs.getLong("coins")),
            limit, offset
        );
    }

    /** 非公開設定の残高を除外したランキングを、ページング前に取得する。 */
    public List<BalanceEntry> getPublicTopBalances(int limit, int offset) {
        return DatabaseManager.query(
            "SELECT uuid, name, coins FROM players WHERE hide_balance = 0 ORDER BY coins DESC LIMIT ? OFFSET ?",
            rs -> new BalanceEntry(UUID.fromString(rs.getString("uuid")), rs.getString("name"), rs.getLong("coins")),
            limit, offset
        );
    }

    /** players テーブルの総レコード数（/balance top のページ数計算用）。 */
    public int getPlayerCount() {
        Integer count = DatabaseManager.queryOne("SELECT COUNT(*) as cnt FROM players", rs -> rs.getInt("cnt"));
        return count != null ? count : 0;
    }

    /** 非公開設定でないプレイヤーだけの件数を返す。 */
    public int getPublicPlayerCount() {
        Integer count = DatabaseManager.queryOne("SELECT COUNT(*) as cnt FROM players WHERE hide_balance = 0", rs -> rs.getInt("cnt"));
        return count != null ? count : 0;
    }

    /** 自分より所持金が多い公開プレイヤーの数（/ranking money の自分の順位用）。 */
    public int countPublicAbove(double coins) {
        Integer count = DatabaseManager.queryOne(
            "SELECT COUNT(*) as cnt FROM players WHERE hide_balance = 0 AND coins > ?",
            rs -> rs.getInt("cnt"), coins);
        return count != null ? count : 0;
    }

    // -------------------------------------------------------------
    // アカウント作成・確認
    // -------------------------------------------------------------

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return player != null && DatabaseManager.exists("players", "uuid = ?", player.getUniqueId().toString());
    }

    @Override
    public boolean hasAccount(String playerName) {
        return hasAccount(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        if (player == null) {
            return false;
        }
        ensurePlayerRecord(player);
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return createPlayerAccount(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // -------------------------------------------------------------
    // 銀行機能（使わない場合はダミーを返して無効化）
    // -------------------------------------------------------------

    @Override
    public EconomyResponse createBank(String name, String player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "銀行機能はサポートされていません");
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "銀行機能はサポートされていません");
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "銀行機能はサポートされていません");
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "銀行機能はサポートされていません");
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "銀行機能はサポートされていません");
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "銀行機能はサポートされていません");
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "銀行機能はサポートされていません");
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "銀行機能はサポートされていません");
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "銀行機能はサポートされていません");
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "銀行機能はサポートされていません");
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "銀行機能はサポートされていません");
    }

    @Override
    public List<String> getBanks() {
        return Collections.emptyList();
    }
}
