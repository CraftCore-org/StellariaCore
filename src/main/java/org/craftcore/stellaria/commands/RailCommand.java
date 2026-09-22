package org.craftcore.stellaria.commands;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.rail.RailSpeedController;
import org.craftcore.stellaria.rail.RailStationManager;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.craftcore.stellaria.utils.WorldBlacklistUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /rail station add|remove|list（駅の管理。stellaria.rail.admin）と
 * /rail depart <目的の駅名>（高速モードの発車。stellaria.rail）をまとめて処理するExecutor。
 * 駅は「向き」を持たず、発車方向はRailManagerがレールを実際にたどって目的駅への経路を
 * 探索して決める（駅から一定距離以内にいる必要はなく、同じ直線でつながっていればどこからでも発車できる）。
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
        RailStationManager.CreateResult result = plugin.getRailStationManager().create(name, player.getLocation());
        switch (result) {
            case SUCCESS -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.station_created", player), "%name%", name));
            case NAME_TAKEN -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.station_name_taken", player), "%name%", name));
            case DATABASE_ERROR -> player.sendMessage(
                    plugin.getConfigManager().getMessage("rail.database_error", player));
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
            String worldName = station.world();
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
        RailStationManager.Station target = plugin.getRailStationManager().get(stationName);
        if (target == null) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.station_not_found", player), "%name%", stationName));
            return;
        }
        if (RailSpeedController.shapeAt(cart.getLocation().getBlock()) == null) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.not_on_rail", player));
            return;
        }
        double arrivalRadius = plugin.getRailConfig().getStationArrivalRadius();
        Location targetLocation = target.resolveLocation();
        if (targetLocation != null && targetLocation.getWorld().equals(cart.getWorld())
                && targetLocation.distanceSquared(cart.getLocation()) <= arrivalRadius * arrivalRadius) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.already_at_station", player), "%name%", stationName));
            return;
        }
        if (plugin.getRailManager().isRailMode(cart.getUniqueId())) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.already_rail_mode", player));
            return;
        }
        if (!plugin.getRailManager().startSession(cart, target)) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.no_route_to_station", player), "%name%", stationName));
            return;
        }
        player.sendMessage(FormatUtil.replace(
                plugin.getConfigManager().getMessage("rail.departed", player), "%name%", stationName));
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
