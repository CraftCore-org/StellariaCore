package org.craftcore.stellaria;

import org.bukkit.plugin.java.JavaPlugin;
import org.craftcore.stellaria.commands.tpa.TpaCore;
import org.craftcore.stellaria.managers.PluginManager;
import org.craftcore.stellaria.listeners.PlayerListener;
import org.bukkit.Bukkit;


import org.craftcore.stellaria.utils.Console;
import org.craftcore.stellaria.utils.Database;


public class StellariaCore extends JavaPlugin {
    
    @Override
    public void onEnable() {
        
        Database.connect(this, "database.db");
        Database.createTableIfNotExists("players",
            "uuid TEXT PRIMARY KEY",
            "name TEXT",
            "coins INTEGER DEFAULT 0"
        );

        // Initialize managers
        // PluginManager.getInstance().initialize();
        
        // Register listeners
        // getServer().getPluginManager().registerEvents(new PlayerListener(), this);
        TpaCore tpaCore = new TpaCore();

        getCommand("tpa").setExecutor(tpaCore);
        getCommand("tpaccept").setExecutor(tpaCore);
        getCommand("tpdeny").setExecutor(tpaCore);
        getCommand("tphere").setExecutor(tpaCore);
        getCommand("tphaccept").setExecutor(tpaCore);
        getCommand("tphdeny").setExecutor(tpaCore);
        
        Console.printLogo(getPluginMeta().getVersion());
    }

    @Override
    public void onDisable() {
        Database.disconnect();
        Console.printDisabledMessage();
    }
    
}
