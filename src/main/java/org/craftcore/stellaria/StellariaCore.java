package org.craftcore.stellaria;

import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.craftcore.stellaria.commands.ReloadCommand;
import org.craftcore.stellaria.commands.tpa.TpaCore;
import org.craftcore.stellaria.managers.ConfigManager;
import org.craftcore.stellaria.managers.EconomyManager;
import org.craftcore.stellaria.listeners.PlayerJoinListener;
import org.craftcore.stellaria.listeners.PlayerListener;
import org.craftcore.stellaria.listeners.PlayerQuitListener;


import org.craftcore.stellaria.utils.ConsoleUtil;
import org.craftcore.stellaria.managers.DatabaseManager;

import net.milkbowl.vault.economy.Economy;


public class StellariaCore extends JavaPlugin {

    private EconomyManager economyManager;
    private ConfigManager configManager;

    @Override
    public void onEnable() {

        saveDefaultConfig();

        // 0. config.yml の読み込み管理
        this.configManager = new ConfigManager(this);

        // 1. データベースの接続とテーブル作成
        DatabaseManager.connect(this, "database.db");
        DatabaseManager.createTableIfNotExists("players",
            "uuid TEXT PRIMARY KEY",
            "name TEXT",
            "coins INTEGER DEFAULT 0"
        );

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
        getServer().getPluginManager().registerEvents(new PlayerListener(), this);

        TpaCore tpaCore = new TpaCore(this);

        getCommand("tpa").setExecutor(tpaCore);
        getCommand("tpaccept").setExecutor(tpaCore);
        getCommand("tpdeny").setExecutor(tpaCore);
        getCommand("tphere").setExecutor(tpaCore);
        getCommand("tphaccept").setExecutor(tpaCore);
        getCommand("tphdeny").setExecutor(tpaCore);

        getCommand("stellariareload").setExecutor(new ReloadCommand(this));

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
}
