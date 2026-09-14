package org.craftcore.stellaria.managers;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * SQLiteとのやり取りをまとめて管理するクラス
 *
 * 生のJDBCを直接書くと定型処理(接続開閉・例外処理・PreparedStatementの組み立て)が
 * 毎回同じように増えていくので、よく使う操作(SELECT/INSERT/UPDATE/DELETE/テーブル作成)を
 * シンプルなメソッド1発で呼べるようにしたラッパー。
 *
 * 使い方
 * {@code
 * // onEnable()で1回だけ呼ぶ
 * DatabaseManager.connect(this, "database.db");
 *
 * // テーブル作成(初回のみ実行される)
 * DatabaseManager.createTableIfNotExists("players",
 *     "uuid TEXT PRIMARY KEY",
 *     "name TEXT",
 *     "coins INTEGER DEFAULT 0"
 * );
 *
 * // データ挿入
 * DatabaseManager.insert("players", Map.of(
 *     "uuid", player.getUniqueId().toString(),
 *     "name", player.getName(),
 *     "coins", 100
 * ));
 *
 * // 1行取得
 * DatabaseManager.queryOneAsync(
 *     "SELECT coins FROM players WHERE uuid = ?",
 *     rs -> rs.getInt("coins"),
 *     row -> player.sendMessage("コインは " + row + " です"),
 *     player.getUniqueId().toString()
 * );
 *
 * // onDisable()で1回だけ呼ぶ
 * DatabaseManager.disconnect();
 * }
 */
public final class DatabaseManager {

    private static JavaPlugin plugin;
    private static Connection connection;
    private static String dbPath;

    private DatabaseManager() {
    }

    // ------------------------------------------------------------------
    // 接続管理
    // ------------------------------------------------------------------

    /**
     * SQLiteデータベースに接続する。プラグインの{@code onEnable()}内で1回だけ呼ぶこと。
     *
     * @param owner    データフォルダの取得元になるプラグインインスタンス
     * @param fileName データベースファイル名 (例: "database.db")
     */
    public static synchronized void connect(JavaPlugin owner, String fileName) {
        plugin = owner;
        File folder = owner.getDataFolder();
        if (!folder.exists()) {
            folder.mkdirs();
        }
        dbPath = new File(folder, fileName).getPath();

        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (PreparedStatement ps = connection.prepareStatement("PRAGMA foreign_keys = ON")) {
                ps.execute();
            }
            plugin.getLogger().info("データベースに接続: " + dbPath);
        } catch (ClassNotFoundException | SQLException e) {
            plugin.getLogger().severe("データベース接続に失敗: " + e.getMessage());
        }
    }

    /**
     * データベース接続を閉じる。プラグインの{@code onDisable()}内で1回だけ呼ぶこと。
     */
    public static synchronized void disconnect() {
        if (connection == null) return;
        try {
            connection.close();
            plugin.getLogger().info("データベース接続を閉じました");
        } catch (SQLException e) {
            plugin.getLogger().warning("データベースを閉じる際にエラーが出ました: " + e.getMessage());
        } finally {
            connection = null;
        }
    }

    /**
     * 接続済みの生の{@link Connection}が欲しい場合はこれを使う。
     * 複雑なクエリを自分で書きたい時の逃げ道として用意している。
     */
    public static Connection raw() {
        if (connection == null) {
            throw new IllegalStateException("DatabaseManager.connect() が呼ばれてないです");
        }
        return connection;
    }

    // ------------------------------------------------------------------
    // テーブル作成
    // ------------------------------------------------------------------

    /**
     * テーブルが存在しない場合のみ作成する。
     *
     * <pre>{@code
     * DatabaseManager.createTableIfNotExists("players",
     *     "uuid TEXT PRIMARY KEY",
     *     "name TEXT",
     *     "coins INTEGER DEFAULT 0"
     * );
     * }</pre>
     *
     * @param table       テーブル名
     * @param columnDefs  "カラム名 型 制約" の形式で1つずつ渡す
     */
    public static void createTableIfNotExists(String table, String... columnDefs) {
        String columns = String.join(", ", columnDefs);
        String sql = "CREATE TABLE IF NOT EXISTS " + table + " (" + columns + ")";
        try (PreparedStatement ps = raw().prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("テーブル作成に失敗した(" + table + "): " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 更新系 (INSERT / UPDATE / DELETE)
    // ------------------------------------------------------------------

    /**
     * INSERT/UPDATE/DELETEなど、結果セットを返さないSQLを実行する。
     *
     * @param sql    {@code ?} をプレースホルダに使ったSQL
     * @param params {@code ?} に埋め込む値(順番通り)
     * @return 影響を受けた行数
     */
    public static int execute(String sql, Object... params) {
        try (PreparedStatement ps = prepare(sql, params)) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("SQL実行に失敗: " + sql + " / " + e.getMessage());
            return -1;
        }
    }

    /** {@link #execute(String, Object...)} の非同期版。結果は使わない前提の処理向け。 */
    public static void executeAsync(String sql, Object... params) {
        runAsync(() -> execute(sql, params));
    }

    /**
     * カラム名と値のMapから、INSERT文を自動生成して実行する。
     * SQL文を自分で書きたくない時に一番手軽な方法。
     *
     * <pre>{@code
     * DatabaseManager.insert("players", Map.of(
     *     "uuid", uuid.toString(),
     *     "name", "Example",
     *     "coins", 100
     * ));
     * }</pre>
     */
    public static int insert(String table, Map<String, Object> values) {
        String columns = String.join(", ", values.keySet());
        String placeholders = String.join(", ", values.keySet().stream().map(k -> "?").toArray(String[]::new));
        String sql = "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")";
        return execute(sql, values.values().toArray());
    }

    /** {@link #insert(String, Map)} の非同期版。 */
    public static void insertAsync(String table, Map<String, Object> values) {
        runAsync(() -> insert(table, values));
    }

    /**
     * カラム名と値のMapから、UPDATE文を自動生成して実行する。
     *
     * <pre>{@code
     * DatabaseManager.update("players",
     *     Map.of("coins", 200),
     *     "uuid = ?",
     *     uuid.toString()
     * );
     * }</pre>
     *
     * @param table      テーブル名
     * @param values     更新したいカラムと値
     * @param whereClause "uuid = ?" のようなWHERE句 (プレースホルダ使用)
     * @param whereArgs  WHERE句のプレースホルダに入れる値
     */
    public static int update(String table, Map<String, Object> values, String whereClause, Object... whereArgs) {
        String setClause = String.join(", ", values.keySet().stream().map(k -> k + " = ?").toArray(String[]::new));
        String sql = "UPDATE " + table + " SET " + setClause + " WHERE " + whereClause;

        Object[] allParams = new Object[values.size() + whereArgs.length];
        System.arraycopy(values.values().toArray(), 0, allParams, 0, values.size());
        System.arraycopy(whereArgs, 0, allParams, values.size(), whereArgs.length);

        return execute(sql, allParams);
    }

    /** {@link #update(String, Map, String, Object...)} の非同期版。 */
    public static void updateAsync(String table, Map<String, Object> values, String whereClause, Object... whereArgs) {
        runAsync(() -> update(table, values, whereClause, whereArgs));
    }

    // ------------------------------------------------------------------
    // 検索系 (SELECT)
    // ------------------------------------------------------------------

    /**
     * SELECT文を実行し、各行を{@link RowMapper}で好きな型に変換したListで受け取る。
     *
     * <pre>{@code
     * List<String> names = DatabaseManager.query(
     *     "SELECT name FROM players WHERE coins > ?",
     *     rs -> rs.getString("name"),
     *     100
     * );
     * }</pre>
     */
    public static <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        List<T> results = new ArrayList<>();
        try (PreparedStatement ps = prepare(sql, params);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapper.map(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("SQL検索に失敗: " + sql + " / " + e.getMessage());
        }
        return results;
    }

    /**
     * {@link #query(String, RowMapper, Object...)} の非同期版。
     * 結果は非同期スレッドから戻ってくるので、Bukkit APIを直接触るならメインスレッドに戻すこと。
     *
     * <pre>{@code
     * DatabaseManager.queryAsync(
     *     "SELECT name FROM players",
     *     rs -> rs.getString("name"),
     *     names -> {
     *         Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
     *             player.sendMessage("人数: " + names.size());
     *         });
     *     }
     * );
     * }</pre>
     */
    public static <T> void queryAsync(String sql, RowMapper<T> mapper, Consumer<List<T>> onResult, Object... params) {
        runAsync(() -> {
            List<T> result = query(sql, mapper, params);
            onResult.accept(result);
        });
    }

    /**
     * 1行だけ欲しい場合の検索。該当行が無ければ{@code null}を返す。
     *
     * <pre>{@code
     * Integer coins = DatabaseManager.queryOne(
     *     "SELECT coins FROM players WHERE uuid = ?",
     *     rs -> rs.getInt("coins"),
     *     uuid.toString()
     * );
     * }</pre>
     */
    public static <T> T queryOne(String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement ps = prepare(sql, params);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return mapper.map(rs);
            }
            return null;
        } catch (SQLException e) {
            plugin.getLogger().severe("SQL検索に失敗: " + sql + " / " + e.getMessage());
            return null;
        }
    }

    /** {@link #queryOne(String, RowMapper, Object...)} の非同期版。 */
    public static <T> void queryOneAsync(String sql, RowMapper<T> mapper, Consumer<T> onResult, Object... params) {
        runAsync(() -> {
            T result = queryOne(sql, mapper, params);
            onResult.accept(result);
        });
    }

    /**
     * 指定した条件のレコードが存在するかだけを確認する。
     *
     * <pre>{@code
     * boolean exists = DatabaseManager.exists("players", "uuid = ?", uuid.toString());
     * }</pre>
     */
    public static boolean exists(String table, String whereClause, Object... params) {
        String sql = "SELECT 1 FROM " + table + " WHERE " + whereClause + " LIMIT 1";
        Boolean result = queryOne(sql, rs -> true, params);
        return result != null;
    }

    // ------------------------------------------------------------------
    // トランザクション (複数の更新を1つのまとまりとして安全に実行する)
    // ------------------------------------------------------------------

    /**
     * 複数のSQL処理を1つの「まとまり」として実行する。
     * 途中で例外が起きた場合は自動的に全部取り消される(ロールバック)。
     *
     * <pre>{@code
     * DatabaseManager.transaction(conn -> {
     *     DatabaseManager.execute("UPDATE players SET coins = coins - ? WHERE uuid = ?", 100, fromUuid);
     *     DatabaseManager.execute("UPDATE players SET coins = coins + ? WHERE uuid = ?", 100, toUuid);
     * });
     * }</pre>
     */
    public static void transaction(Consumer<Connection> action) {
        Connection conn = raw();
        try {
            conn.setAutoCommit(false);
            action.accept(conn);
            conn.commit();
        } catch (Exception e) {
            try {
                conn.rollback();
                plugin.getLogger().warning("トランザクションを取り消し: " + e.getMessage());
            } catch (SQLException rollbackError) {
                plugin.getLogger().severe("ロールバックにも失敗: " + rollbackError.getMessage());
            }
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部ヘルパー
    // ------------------------------------------------------------------

    private static PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement ps = raw().prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
        return ps;
    }

    private static void runAsync(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
    }

    /**
     * {@link ResultSet}の1行を、任意の型{@code T}に変換するための関数型インタフェース。
     * ラムダ式で {@code rs -> rs.getString("name")} のように使う。
     */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }
}
