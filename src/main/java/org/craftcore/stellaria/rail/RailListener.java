package org.craftcore.stellaria.rail;

import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.craftcore.stellaria.StellariaCore;

/**
 * 高速鉄道セッションの毎tick更新とライフサイクル終了のきっかけとなるBukkitイベントを拾う。
 * 対象外のMinecart（RailManagerにセッションが無いもの）への影響はRailManager側の
 * sessions.containsKey()チェックに一任し、ここでは種別だけを見て素通しする。
 */
public class RailListener implements Listener {

    private final StellariaCore plugin;

    public RailListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (event.getVehicle() instanceof Minecart cart) {
            plugin.getRailManager().tickMovement(cart);
        }
    }

    @EventHandler
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (event.getVehicle() instanceof Minecart cart) {
            plugin.getRailManager().endSession(cart, RailManager.EndReason.DESTROYED);
        }
    }
}
