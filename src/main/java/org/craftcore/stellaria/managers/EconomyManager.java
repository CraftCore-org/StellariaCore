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

public class EconomyManager extends AbstractEconomy {

    private final StellariaCore plugin;

    public EconomyManager(StellariaCore plugin) {
        this.plugin = plugin;
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
        String uuid = player.getUniqueId().toString();
        Long coins = DatabaseManager.queryOne(
            "SELECT coins FROM players WHERE uuid = ?",
            rs -> rs.getLong("coins"),
            uuid
        );
        return (coins != null) ? coins : 0;
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
        return getBalance(player) >= amount;
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
        if (amount < 0) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "負の数値は指定できません");
        }

        double current = getBalance(player);
        if (current < amount) {
            return new EconomyResponse(0, current, EconomyResponse.ResponseType.FAILURE, "残高が足りません");
        }

        long newBalance = (long) (current - amount);
        DatabaseManager.updateAsync("players", java.util.Map.of("coins", newBalance), "uuid = ?", player.getUniqueId().toString());

        return new EconomyResponse(amount, newBalance, EconomyResponse.ResponseType.SUCCESS, null);
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
        if (amount < 0) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "負の数値は指定できません");
        }

        double current = getBalance(player);
        long newBalance = (long) (current + amount);
        DatabaseManager.updateAsync("players", java.util.Map.of("coins", newBalance), "uuid = ?", player.getUniqueId().toString());

        return new EconomyResponse(amount, newBalance, EconomyResponse.ResponseType.SUCCESS, null);
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
        if (player == null || amount < 0) {
            return false;
        }
        long newBalance = (long) amount;
        DatabaseManager.updateAsync("players", java.util.Map.of("coins", newBalance), "uuid = ?", player.getUniqueId().toString());
        return true;
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

    /** プレイヤー自身の所持金公開設定を非同期で更新する。 */
    public void setHideBalance(Player player, boolean hidden) {
        DatabaseManager.updateAsync("players", Map.of("hide_balance", hidden ? 1 : 0), "uuid = ?", player.getUniqueId().toString());
    }

    /**
     * from -> to へ amount を送金する。DatabaseManager.transaction() で2件のUPDATEを
     * 1トランザクションにまとめる。from の残高が不足していれば何もせず false を返す。
     */
    public boolean transfer(OfflinePlayer from, OfflinePlayer to, double amount) {
        if (from == null || to == null || amount <= 0) {
            return false;
        }
        double currentFrom = getBalance(from);
        if (currentFrom < amount) {
            return false;
        }
        long newFromBalance = (long) (currentFrom - amount);
        long newToBalance = (long) (getBalance(to) + amount);
        DatabaseManager.transaction(conn -> {
            DatabaseManager.execute("UPDATE players SET coins = ? WHERE uuid = ?", newFromBalance, from.getUniqueId().toString());
            DatabaseManager.execute("UPDATE players SET coins = ? WHERE uuid = ?", newToBalance, to.getUniqueId().toString());
        });
        return true;
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

    /** players テーブルの総レコード数（/balance top のページ数計算用）。 */
    public int getPlayerCount() {
        Integer count = DatabaseManager.queryOne("SELECT COUNT(*) as cnt FROM players", rs -> rs.getInt("cnt"));
        return count != null ? count : 0;
    }

    // -------------------------------------------------------------
    // アカウント作成・確認
    // -------------------------------------------------------------

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean hasAccount(String playerName) {
        return true;
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return true;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return true;
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
