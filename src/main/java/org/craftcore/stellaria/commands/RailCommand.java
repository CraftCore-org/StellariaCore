package org.craftcore.stellaria.commands;

import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.rail.RailStationManager;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.craftcore.stellaria.utils.WorldBlacklistUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /rail station add|remove|list（駅の管理。stellaria.rail.admin）と
 * /rail depart <駅名>（高速モードの発車。stellaria.rail）をまとめて処理するExecutor。
 * 駅または専用の発車地点から開始したトロッコだけを高速化するという元設計書の方針に沿い、
 * 「駅の登録地点から一定距離以内で、既に何かのトロッコに乗っている」ことを起動条件にする
 * （サイン/ボタン等の将来的なトリガーは元設計書でも初期実装の対象外とされている）。
 */
public class RailCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public RailCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("rail.must_be_player", null));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_depart", player));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "station" -> handleStation(player, args);
            case "depart" -> handleDepart(player, args);
            default -> player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_depart", player));
        }
        return true;
    }

    private void handleStation(Player player, String[] args) {
        if (!player.hasPermission("stellaria.rail.admin")) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.no_permission", player));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_station_add", player));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "add" -> handleStationAdd(player, args);
            case "remove" -> handleStationRemove(player, args);
            case "list" -> handleStationList(player);
            default -> player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_station_add", player));
        }
    }

    private void handleStationAdd(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_station_add", player));
            return;
        }
        String name = args[2];
        BlockFace direction = yawToBlockFace(player.getLocation().getYaw());
        RailStationManager.CreateResult result = plugin.getRailStationManager().create(name, player.getLocation(), direction);
        switch (result) {
            case SUCCESS -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.station_created", player), "%name%", name));
            case NAME_TAKEN -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.station_name_taken", player), "%name%", name));
            case DATABASE_ERROR -> player.sendMessage(
                    plugin.getConfigManager().getMessage("rail.station_not_found", player));
        }
    }

    private void handleStationRemove(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_station_remove", player));
            return;
        }
        String name = args[2];
        boolean removed = plugin.getRailStationManager().remove(name);
        String key = removed ? "rail.station_removed" : "rail.station_not_found";
        player.sendMessage(FormatUtil.replace(plugin.getConfigManager().getMessage(key, player), "%name%", name));
    }

    private void handleStationList(Player player) {
        List<RailStationManager.Station> stations = plugin.getRailStationManager().listAll();
        if (stations.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.station_list_empty", player));
            return;
        }
        player.sendMessage(plugin.getConfigManager().getMessage("rail.station_list_header", player));
        for (RailStationManager.Station station : stations) {
            String line = plugin.getConfigManager().getMessage("rail.station_list_entry", player);
            line = FormatUtil.replace(line, "%name%", station.name());
            String worldName = station.location().getWorld() != null ? station.location().getWorld().getName() : "?";
            line = FormatUtil.replace(line, "%world%", worldName);
            player.sendMessage(line);
        }
    }

    private void handleDepart(Player player, String[] args) {
        if (!player.hasPermission("stellaria.rail")) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.no_permission", player));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_depart", player));
            return;
        }
        if (WorldBlacklistUtil.isBlacklisted(plugin.getRailConfig().getDisabledWorlds(), player.getWorld().getName())) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.world_disabled", player));
            return;
        }
        if (!(player.getVehicle() instanceof Minecart cart)) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.must_ride_minecart", player));
            return;
        }
        String stationName = args[1];
        RailStationManager.Station station = plugin.getRailStationManager().get(stationName);
        if (station == null) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.station_not_found", player), "%name%", stationName));
            return;
        }
        double activationRadius = plugin.getRailConfig().getStationActivationRadius();
        if (!station.location().getWorld().equals(cart.getWorld())
                || station.location().distanceSquared(cart.getLocation()) > activationRadius * activationRadius) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.too_far_from_station", player), "%name%", stationName));
            return;
        }
        if (plugin.getRailManager().isRailMode(cart.getUniqueId())) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.already_rail_mode", player));
            return;
        }
        if (!plugin.getRailManager().startSession(cart, station)) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.not_on_rail", player));
            return;
        }
        player.sendMessage(plugin.getConfigManager().getMessage("rail.departed", player));
    }

    /** プレイヤーの向き(yaw)を東西南北4方向にスナップする。Bukkit標準のLocation#getFacing()と同じ境界。 */
    private static BlockFace yawToBlockFace(float yaw) {
        float normalized = (yaw % 360 + 360) % 360;
        if (normalized >= 45 && normalized < 135) return BlockFace.WEST;
        if (normalized >= 135 && normalized < 225) return BlockFace.NORTH;
        if (normalized >= 225 && normalized < 315) return BlockFace.EAST;
        return BlockFace.SOUTH;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(List.of("station", "depart"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("station")) {
            return TabCompleteUtil.filterStartsWith(List.of("add", "remove", "list"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("depart")) {
            return TabCompleteUtil.filterStartsWith(stationNames(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("station") && args[1].equalsIgnoreCase("remove")) {
            return TabCompleteUtil.filterStartsWith(stationNames(), args[2]);
        }
        return List.of();
    }

    private List<String> stationNames() {
        return plugin.getRailStationManager().listAll().stream().map(RailStationManager.Station::name).toList();
    }
}
