package org.craftcore.stellaria;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.craftcore.stellaria.commands.AfkCommand;
import org.craftcore.stellaria.commands.BroadcastCommand;
import org.craftcore.stellaria.commands.HealCommand;
import org.craftcore.stellaria.commands.MessageCommand;
import org.craftcore.stellaria.commands.BalanceCommand;
import org.craftcore.stellaria.commands.EcoCommand;
import org.craftcore.stellaria.commands.MuteCommand;
import org.craftcore.stellaria.commands.PayCommand;
import org.craftcore.stellaria.commands.ReloadCommand;
import org.craftcore.stellaria.commands.tpa.TpaCore;
import org.craftcore.stellaria.gui.GuiListener;
import org.craftcore.stellaria.managers.ActionBarManager;
import org.craftcore.stellaria.managers.AfkManager;
import org.craftcore.stellaria.managers.AutoBroadcastManager;
import org.craftcore.stellaria.managers.BelownameManager;
import org.craftcore.stellaria.managers.ConfigManager;
import org.craftcore.stellaria.managers.EconomyManager;
import org.craftcore.stellaria.managers.MentionService;
import org.craftcore.stellaria.managers.MuteManager;
import org.craftcore.stellaria.managers.PlaceholderManager;
import org.craftcore.stellaria.managers.PrivateMessageManager;
import org.craftcore.stellaria.managers.ScoreboardManager;
import org.craftcore.stellaria.managers.TabListManager;
import org.craftcore.stellaria.listeners.ChatListener;
import org.craftcore.stellaria.listeners.PlayerJoinListener;
import org.craftcore.stellaria.listeners.MuteCommandBlockListener;
import org.craftcore.stellaria.listeners.PlayerListener;
import org.craftcore.stellaria.listeners.PlayerQuitListener;


import org.craftcore.stellaria.utils.ConsoleUtil;
import org.craftcore.stellaria.managers.DatabaseManager;

import net.milkbowl.vault.economy.Economy;


public class StellariaCore extends JavaPlugin {

    private EconomyManager economyManager;
    private ConfigManager configManager;
    private PlaceholderManager placeholderManager;
    private ScoreboardManager scoreboardManager;
    private TabListManager tabListManager;
    private BelownameManager belownameManager;
    private MentionService mentionService;
    private AfkManager afkManager;
    private AutoBroadcastManager autoBroadcastManager;
    private MuteManager muteManager;
    private PrivateMessageManager privateMessageManager;
    private ActionBarManager actionBarManager;

    @Override
    public void onEnable() {

        // 0. 設定ファイルの読み込み管理（config.yml はサーバー設定、messages.yml はメッセージ）
        this.configManager = new ConfigManager(this);
        this.configManager.register("config.yml");
        this.configManager.register("messages.yml");

        // 1. データベースの接続とテーブル作成
        DatabaseManager.connect(this, "database.db");
        DatabaseManager.createTableIfNotExists("players",
            "uuid TEXT PRIMARY KEY",
            "name TEXT",
            "coins INTEGER DEFAULT 0"
        );
        DatabaseManager.createTableIfNotExists("mutes",
            "uuid TEXT PRIMARY KEY",
            "level INTEGER",
            "expires_at INTEGER",
            "reason TEXT",
            "muted_by TEXT",
            "muted_at INTEGER"
        );

        this.afkManager = new AfkManager(this);

        this.muteManager = new MuteManager(this);
        muteManager.loadAll();
        this.privateMessageManager = new PrivateMessageManager(this);

        this.actionBarManager = new ActionBarManager(this);
        if (configManager.getBoolean("action-bar.enabled", true)) {
            long actionBarInterval = configManager.getInt("action-bar.update-interval-ticks", 5);
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> actionBarManager.tick(), actionBarInterval, actionBarInterval);

            if (configManager.getBoolean("action-bar.persistent.enabled", false)) {
                Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
                    String template = configManager.getString("action-bar.persistent.template", "");
                    for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                        String resolved = placeholderManager.resolve(template, online);
                        actionBarManager.setChannel(online, "persistent", org.craftcore.stellaria.utils.ColorUtil.component(resolved));
                    }
                }, actionBarInterval, actionBarInterval);
            }
        }

        // 2. EconomyManager のインスタンス化
        this.economyManager = new EconomyManager(this);

        // 3. Vaultがサーバーにあるか確認し、登録する処理
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            // Vaultのマネージャー（ServicesManager）に「うちのお金システムを使ってね」と登録する
            getServer().getServicesManager().register(
                Economy.class,
                this.economyManager,
                this,
                ServicePriority.Normal
            );
            getLogger().info("Vault への経済システムの登録に成功");
        } else {
            getLogger().warning("Vault が見つかりませんでした。Vault連携機能は無効化されます。");
        }

        // 4. イベントリスナー登録
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(this), this);

        // 5. 起動ロゴ表示
        // Initialize managers
        // PluginManager.getInstance().initialize();

        // Register listeners
        // getServer().getPluginManager().registerEvents(new PlayerListener(), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // 6. Scoreboard/Tablist/Belowname のインスタンス化とtick開始
        this.placeholderManager = new PlaceholderManager(this);

        this.scoreboardManager = new ScoreboardManager(
            placeholderManager,
            configManager.getString("scoreboard.title", ""),
            configManager.getStringList("scoreboard.lines"),
            configManager.getBoolean("scoreboard.hide-numbers", true)
        );
        this.tabListManager = new TabListManager(
            placeholderManager,
            configManager.getString("tablist.header", ""),
            configManager.getString("tablist.footer", ""),
            configManager.getString("tablist.value", "")
        );
        this.belownameManager = new BelownameManager(
            placeholderManager,
            configManager.getString("belowname.title", ""),
            configManager.getString("belowname.value", "%health%")
        );

        long scoreboardInterval = configManager.getInt("scoreboard.update-interval-ticks", 20);
        long tabListInterval = configManager.getInt("tablist.update-interval-ticks", 20);
        long belownameInterval = configManager.getInt("belowname.update-interval-ticks", 20);

        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> scoreboardManager.tick(), scoreboardInterval, scoreboardInterval);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> tabListManager.tick(), tabListInterval, tabListInterval);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> belownameManager.tick(), belownameInterval, belownameInterval);

        if (configManager.getBoolean("afk.enabled", true)) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> afkManager.tick(), 200L, 200L);
        }

        // 7. チャットフォーマット・メンション
        this.mentionService = new MentionService(this);
        if (configManager.getBoolean("chat.enabled", true)) {
            getServer().getPluginManager().registerEvents(new ChatListener(this, mentionService), this);
        }

        TpaCore tpaCore = new TpaCore(this);
        getServer().getPluginManager().registerEvents(tpaCore, this);

        getCommand("tpa").setExecutor(tpaCore);
        getCommand("tpaccept").setExecutor(tpaCore);
        getCommand("tpdeny").setExecutor(tpaCore);
        getCommand("tphere").setExecutor(tpaCore);
        getCommand("tphaccept").setExecutor(tpaCore);
        getCommand("tphdeny").setExecutor(tpaCore);

        getCommand("stellariareload").setExecutor(new ReloadCommand(this));

        getCommand("afk").setExecutor(new AfkCommand(this));
        getCommand("heal").setExecutor(new HealCommand(this));
        getCommand("broadcast").setExecutor(new BroadcastCommand(this));

        MuteCommand muteCommand = new MuteCommand(this);
        getCommand("mute").setExecutor(muteCommand);
        getCommand("unmute").setExecutor(muteCommand);
        getServer().getPluginManager().registerEvents(new MuteCommandBlockListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(), this);

        MessageCommand messageCommand = new MessageCommand(this);
        getCommand("msg").setExecutor(messageCommand);
        getCommand("reply").setExecutor(messageCommand);

        getCommand("pay").setExecutor(new PayCommand(this));
        getCommand("eco").setExecutor(new EcoCommand(this));
        getCommand("balance").setExecutor(new BalanceCommand(this));

        this.autoBroadcastManager = new AutoBroadcastManager(this);
        autoBroadcastManager.start();

        ConsoleUtil.printLogo(getPluginMeta().getVersion());
    }

    @Override
    public void onDisable() {
        // プラグイン停止時は Vault から自動解除されるため、DB切断だけでOK
        DatabaseManager.disconnect();
        ConsoleUtil.printDisabledMessage();
    }
    
    public EconomyManager getEconomyManager() {
        return this.economyManager;
    }

    public ConfigManager getConfigManager() {
        return this.configManager;
    }

    public PlaceholderManager getPlaceholderManager() {
        return this.placeholderManager;
    }

    public ScoreboardManager getScoreboardManager() {
        return this.scoreboardManager;
    }

    public TabListManager getTabListManager() {
        return this.tabListManager;
    }

    public BelownameManager getBelownameManager() {
        return this.belownameManager;
    }

    public MentionService getMentionService() {
        return this.mentionService;
    }

    public AfkManager getAfkManager() {
        return this.afkManager;
    }

    public MuteManager getMuteManager() {
        return this.muteManager;
    }

    public PrivateMessageManager getPrivateMessageManager() {
        return this.privateMessageManager;
    }

    public ActionBarManager getActionBarManager() {
        return this.actionBarManager;
    }

    /**
     * config.yml の scoreboard/tablist/belowname 設定を読み直して各Managerに反映する。
     * ConfigManager#reload() で config.yml 自体を読み直した後に呼ぶ想定（ReloadCommand参照）。
     */
    public void reloadFeatureManagers() {
        scoreboardManager.updateSettings(
            configManager.getString("scoreboard.title", ""),
            configManager.getStringList("scoreboard.lines"),
            configManager.getBoolean("scoreboard.hide-numbers", true)
        );
        tabListManager.updateSettings(
            configManager.getString("tablist.header", ""),
            configManager.getString("tablist.footer", ""),
            configManager.getString("tablist.value", "")
        );
        belownameManager.updateSettings(
            configManager.getString("belowname.title", ""),
            configManager.getString("belowname.value", "%health%")
        );
        autoBroadcastManager.restart();
    }
}
