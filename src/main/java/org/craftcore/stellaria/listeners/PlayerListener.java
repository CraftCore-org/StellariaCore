package org.craftcore.stellaria.listeners;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.commands.tpa.TpaCore;
import org.craftcore.stellaria.managers.ElevatorManager;

public class PlayerListener implements Listener {

    // タブリストのヘッダー/フッター更新は TabListManager が毎tick自動で行うようになったので、
    // Join時にここで手動更新する必要は無くなった（旧 TabList.updateAllPlayersTablist()）。

    private final StellariaCore plugin;
    private final ElevatorManager elevatorManager;

    public PlayerListener(StellariaCore plugin, ElevatorManager elevatorManager){
        this.plugin = plugin;
        this.elevatorManager = elevatorManager;
    }

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event){
        TpaCore.resetPlayerTeleportRequests(event.getPlayer());
    }

    @EventHandler
    public void onPlayerJump(PlayerJumpEvent event){
        elevatorManager.PlayerElevatorMoveUp(event.getPlayer());
    }

    @EventHandler
    public void onPlayerSneak(PlayerToggleSneakEvent event){
        if (event.isSneaking()){
            elevatorManager.PlayerElevatorMoveDown(event.getPlayer());
        }
    }
}
