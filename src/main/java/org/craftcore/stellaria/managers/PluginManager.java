package org.craftcore.stellaria.managers;

import lombok.Getter;

public class PluginManager {
    @Getter private static final PluginManager instance = new PluginManager();
    
    private PluginManager() {}
    
    

    public void initialize() {
        // Initialize your managers here
    }
}
