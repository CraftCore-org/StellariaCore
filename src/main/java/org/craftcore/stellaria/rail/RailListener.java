package org.craftcore.stellaria.rail;

import org.bukkit.block.Block;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.commands.RailCommand;
import org.craftcore.stellaria.gui.RailDepartGui;
import org.craftcore.stellaria.utils.WorldBlacklistUtil;

import java.util.List;

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

    /** 高速モード中のトロッコが別のトロッコと衝突したら、バニラの押し合いに任せず即座に高速モードを解除する。 */
    @EventHandler
    public void onVehicleCollide(VehicleEntityCollisionEvent event) {
        if (event.getVehicle() instanceof Minecart cart && event.getEntity() instanceof Minecart) {
            plugin.getRailManager().endSession(cart, RailManager.EndReason.COLLISION);
        }
    }

    /**
     * 高速モード中に途中下車したら即座にセッションを終了する。VehicleExitEventの時点で
     * Bukkit側は既にexitしたプレイヤーをcart.getPassengers()から外しているため、
     * RailManager#endSessionのnotifyEndループ（現在の乗客だけを見る）ではこのプレイヤーの
     * アクションバーを消せない。ここで個別にクリアする。
     */
    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart) || !plugin.getRailManager().isRailMode(cart.getUniqueId())) {
            return;
        }
        if (event.getExited() instanceof Player player) {
            plugin.getActionBarManager().clearChannel(player, "rail");
        }
        plugin.getRailManager().endSession(cart, RailManager.EndReason.DISMOUNTED);
    }

    /**
     * /rail station add 実行後、右クリックでレールを選択して駅の位置を確定するためのフック。
     * それに該当しなければ、トロッコ乗車中にレールブロックそのものを右クリックした時を行き先GUI
     * 起動として扱う（素手でも動作する — クリック対象が実在のブロックなら、持っているアイテムに
     * 関わらずバニラは必ずパケットを送ってくる）。
     * 「空気」を右クリックした場合は検知できない（調査の結果、素手＋ノーターゲットの右クリックは
     * 乗り物に乗っているかを問わずバニラのクライアントが一切パケットを送らないことが判明した。
     * しゃがみでの代替も試したが、トロッコ乗車中のしゃがみはバニラの降車キーそのものなので使えない）。
     * そのため「乗った瞬間に自動でGUIを開く」方式は使わず、代わりに乗車直後にアクションバーで
     * 「レールを右クリック」の案内だけ出し、実際の起動は確実に動くレール右クリックに一本化する。
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block clickedBlock = event.getClickedBlock();
        if (RailCommand.handleStationRailClick(plugin, event.getPlayer(), clickedBlock)) {
            event.setCancelled(true);
            return;
        }
        if (RailSpeedController.shapeAt(clickedBlock) != null && tryOpenDepartGui(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * トロッコに乗った瞬間、GUIそのものは開かずヒントだけ出す。乗車直後数tickはバニラの
     * 「シフトで降車」ヒントがアクションバーを占有し続けるため、アクションバーだと表示してもすぐ
     * 上書きされて消えてしまう。チャットに送る（1tick遅らせるのは、乗車確定前の一部Paperバージョンで
     * getVehicle()がまだ古い値を返すことがあるための保険）。
     */
    @EventHandler(ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart) || !(event.getEntered() instanceof Player player)) {
            return;
        }
        player.getScheduler().runDelayed(plugin, task -> {
            if (!cart.isValid() || player.getVehicle() != cart || plugin.getRailManager().isRailMode(cart.getUniqueId())) {
                return;
            }
            if (!canUseRail(player, cart) || plugin.getRailManager().findReachableStations(cart).isEmpty()) {
                return;
            }
            player.sendMessage(plugin.getConfigManager().getMessage("rail.ride_hint_chat", player));
        }, null, 1L);
    }

    private boolean tryOpenDepartGui(Player player) {
        if (!(player.getVehicle() instanceof Minecart cart) || plugin.getRailManager().isRailMode(cart.getUniqueId())) {
            return false;
        }
        if (!canUseRail(player, cart)) {
            return false;
        }
        List<RailManager.ReachableStation> reachable = plugin.getRailManager().findReachableStations(cart);
        if (reachable.isEmpty()) {
            return false;
        }
        plugin.getActionBarManager().clearChannel(player, "rail-hint");
        new RailDepartGui(plugin, plugin.getRailCommand(), cart).open(player);
        return true;
    }

    private boolean canUseRail(Player player, Minecart cart) {
        return plugin.getRailConfig().isOpenGuiOnInteractEnabled()
                && player.hasPermission("stellaria.rail")
                && !WorldBlacklistUtil.isBlacklisted(plugin.getRailConfig().getDisabledWorlds(), cart.getWorld().getName());
    }
}
