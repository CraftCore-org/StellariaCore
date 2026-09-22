package org.craftcore.stellaria.commands;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.gui.RailDepartGui;
import org.craftcore.stellaria.gui.RailLineAdminGui;
import org.craftcore.stellaria.gui.RailStationAdminGui;
import org.craftcore.stellaria.rail.RailLineManager;
import org.craftcore.stellaria.rail.RailManager;
import org.craftcore.stellaria.rail.RailSpeedController;
import org.craftcore.stellaria.rail.RailStationManager;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.craftcore.stellaria.utils.WorldBlacklistUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /rail station add|remove|list（駅の管理）、/rail line create|remove|list（路線の管理。
 * どちらもstellaria.rail.admin）、/rail depart <目的の駅名>（高速モードの発車。stellaria.rail）を
 * まとめて処理するExecutor。駅は「向き」を持たず、発車方向はRailManagerがレールを実際にたどって
 * 目的駅への経路を探索して決める（駅から一定距離以内にいる必要はなく、同じ直線でつながっていれば
 * どこからでも発車できる）。一方通行路線の逆走はRailManager#checkDepartureで拒否される。
 * /rail station add は「コマンド実行→レールを右クリックして確定」の2段階（TeleportSafetyUtilの
 * PendingConfirmと同じ、コマンド種別ごとのstatic Map方式）。実際のクリック検知はRailListenerが行い、
 * このクラスのstaticメソッドに委譲する。
 */
public class RailCommand implements CommandExecutor, TabCompleter {

    private record PendingStationCreation(String name, long expiresAtMillis) {
    }

    private static final Map<UUID, PendingStationCreation> PENDING_STATION_CREATION = new ConcurrentHashMap<>();
    private static final long PENDING_STATION_WINDOW_MILLIS = 30_000L;

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
            case "line" -> handleLine(player, args);
            case "depart" -> handleDepart(player, args);
            case "particle" -> handleParticleToggle(player);
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
            case "gui" -> new RailStationAdminGui(plugin).open(player);
            default -> player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_station_add", player));
        }
    }

    /** 駅名の重複を先に軽くチェックしてから、右クリック待ちの保留状態に入る（実際の作成はrailを右クリックした時）。 */
    private void handleStationAdd(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_station_add", player));
            return;
        }
        String name = args[2];
        if (plugin.getRailStationManager().get(name) != null) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.station_name_taken", player), "%name%", name));
            return;
        }
        PENDING_STATION_CREATION.put(player.getUniqueId(),
                new PendingStationCreation(name, System.currentTimeMillis() + PENDING_STATION_WINDOW_MILLIS));
        player.sendMessage(FormatUtil.replace(
                plugin.getConfigManager().getMessage("rail.station_add_prompt", player), "%name%", name));
    }

    /**
     * RailListenerのPlayerInteractEvent(右クリック)から呼ぶ。保留中の駅名が無ければ何もせずfalseを返す
     * （＝このクリックは駅作成と無関係なので、イベントをキャンセルしない）。
     * 保留があれば、レールブロックかどうかに関わらずこのクリックを消費してtrueを返す
     * （手に持ったアイテムでの誤爆や、想定外のブロック操作を防ぐため）。
     */
    public static boolean handleStationRailClick(StellariaCore plugin, Player player, Block clickedBlock) {
        PendingStationCreation pending = PENDING_STATION_CREATION.get(player.getUniqueId());
        if (pending == null) {
            return false;
        }
        if (System.currentTimeMillis() > pending.expiresAtMillis()) {
            PENDING_STATION_CREATION.remove(player.getUniqueId());
            player.sendMessage(plugin.getConfigManager().getMessage("rail.station_add_expired", player));
            return true;
        }
        if (RailSpeedController.shapeAt(clickedBlock) == null) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.station_add_needs_rail", player));
            return true;
        }
        PENDING_STATION_CREATION.remove(player.getUniqueId());
        Location location = clickedBlock.getLocation().add(0.5, 0.0, 0.5);
        RailStationManager.CreateResult result = plugin.getRailStationManager().create(pending.name(), location);
        switch (result) {
            case SUCCESS -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.station_created", player), "%name%", RailStationManager.formatDisplayName(pending.name())));
            case NAME_TAKEN -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.station_name_taken", player), "%name%", RailStationManager.formatDisplayName(pending.name())));
            case DATABASE_ERROR -> player.sendMessage(
                    plugin.getConfigManager().getMessage("rail.database_error", player));
        }
        return true;
    }

    /** プレイヤー退出時にPlayerListenerから呼ぶ。保留中の駅作成を破棄する。 */
    public static void clearPendingStationCreation(UUID uuid) {
        PENDING_STATION_CREATION.remove(uuid);
    }

    private void handleStationRemove(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_station_remove", player));
            return;
        }
        String name = args[2];
        RailStationManager.RemoveResult result = plugin.getRailStationManager().remove(name);
        switch (result) {
            case SUCCESS -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.station_removed", player), "%name%", name));
            case NOT_FOUND -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.station_not_found", player), "%name%", name));
            case BELONGS_TO_LINE -> {
                RailLineManager.RailLine line = plugin.getRailLineManager().findLineForStation(name);
                String message = plugin.getConfigManager().getMessage("rail.station_remove_belongs_to_line", player);
                message = FormatUtil.replace(message, "%name%", name);
                message = FormatUtil.replace(message, "%line%", line != null ? line.name() : "?");
                player.sendMessage(message);
            }
            case DATABASE_ERROR -> player.sendMessage(
                    plugin.getConfigManager().getMessage("rail.database_error", player));
        }
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
            line = FormatUtil.replace(line, "%name%", station.displayName());
            String worldName = station.world();
            line = FormatUtil.replace(line, "%world%", worldName);
            player.sendMessage(line);
        }
    }

    private void handleLine(Player player, String[] args) {
        if (!player.hasPermission("stellaria.rail.admin")) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.no_permission", player));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_line_create", player));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "create" -> handleLineCreate(player, args);
            case "remove" -> handleLineRemove(player, args);
            case "list" -> handleLineList(player);
            case "gui" -> new RailLineAdminGui(plugin).open(player);
            default -> player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_line_create", player));
        }
    }

    private void handleLineCreate(Player player, String[] args) {
        if (args.length < 5) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_line_create", player));
            return;
        }
        String name = args[2];
        String modeArg = args[3].toLowerCase();
        if (!modeArg.equals("oneway") && !modeArg.equals("twoway")) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_line_create", player));
            return;
        }
        boolean oneWay = modeArg.equals("oneway");
        List<String> stationNames = new ArrayList<>();
        for (int i = 4; i < args.length; i++) {
            stationNames.add(args[i]);
        }
        RailLineManager.CreateResult result = plugin.getRailLineManager().create(name, oneWay, stationNames);
        switch (result) {
            case SUCCESS -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.line_created", player), "%name%", name));
            case NAME_TAKEN -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.line_name_taken", player), "%name%", name));
            case TOO_FEW_STATIONS -> player.sendMessage(
                    plugin.getConfigManager().getMessage("rail.line_too_few_stations", player));
            case STATION_NOT_FOUND -> player.sendMessage(
                    plugin.getConfigManager().getMessage("rail.line_station_not_found", player));
            case STATION_ALREADY_ON_LINE -> player.sendMessage(
                    plugin.getConfigManager().getMessage("rail.line_station_already_on_line", player));
            case DATABASE_ERROR -> player.sendMessage(
                    plugin.getConfigManager().getMessage("rail.database_error", player));
        }
    }

    private void handleLineRemove(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_line_remove", player));
            return;
        }
        String name = args[2];
        boolean removed = plugin.getRailLineManager().remove(name);
        String key = removed ? "rail.line_removed" : "rail.line_not_found";
        player.sendMessage(FormatUtil.replace(plugin.getConfigManager().getMessage(key, player), "%name%", name));
    }

    private void handleLineList(Player player) {
        List<RailLineManager.RailLine> lines = plugin.getRailLineManager().listAll();
        if (lines.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.line_list_empty", player));
            return;
        }
        player.sendMessage(plugin.getConfigManager().getMessage("rail.line_list_header", player));
        for (RailLineManager.RailLine line : lines) {
            String modeLabel = plugin.getConfigManager().getMessage(
                    line.oneWay() ? "rail.line_mode_oneway" : "rail.line_mode_twoway", player);
            String entry = plugin.getConfigManager().getMessage("rail.line_list_entry", player);
            entry = FormatUtil.replace(entry, "%name%", line.name());
            entry = FormatUtil.replace(entry, "%mode%", modeLabel);
            entry = FormatUtil.replace(entry, "%stations%", String.join(" → ", line.stationNamesInOrder()));
            player.sendMessage(entry);
        }
    }

    /** /rail particle。駅の青いパーティクル目印を自分だけON/OFFする（既定OFF、DBに永続化）。 */
    private void handleParticleToggle(Player player) {
        if (!player.hasPermission("stellaria.rail")) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.no_permission", player));
            return;
        }
        boolean enabled = plugin.getRailStationParticleManager().toggle(player);
        player.sendMessage(plugin.getConfigManager().getMessage(
                enabled ? "rail.particle_enabled" : "rail.particle_disabled", player));
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
        if (args[1].equalsIgnoreCase("gui")) {
            openDepartGui(player);
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
        departToStation(player, cart, target);
    }

    private void openDepartGui(Player player) {
        if (WorldBlacklistUtil.isBlacklisted(plugin.getRailConfig().getDisabledWorlds(), player.getWorld().getName())) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.world_disabled", player));
            return;
        }
        if (!(player.getVehicle() instanceof Minecart cart)) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.must_ride_minecart", player));
            return;
        }
        new RailDepartGui(plugin, this, cart).open(player);
    }

    /**
     * 発車の実行本体。/rail depart <駅名> とRailDepartGui（GUIでの選択）の両方から呼ばれる。
     * 距離・一方通行・経路の全チェックをここに集約し、呼び出し側で重複させない。
     */
    public void departToStation(Player player, Minecart cart, RailStationManager.Station target) {
        double arrivalRadius = plugin.getRailConfig().getStationArrivalRadius();
        Location targetLocation = target.resolveLocation();
        if (targetLocation != null && targetLocation.getWorld().equals(cart.getWorld())
                && targetLocation.distanceSquared(cart.getLocation()) <= arrivalRadius * arrivalRadius) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.already_at_station", player), "%name%", target.displayName()));
            return;
        }
        RailManager.DepartureCheck check = plugin.getRailManager().checkDeparture(cart, target);
        switch (check) {
            case ALREADY_RAIL_MODE -> {
                player.sendMessage(plugin.getConfigManager().getMessage("rail.already_rail_mode", player));
                return;
            }
            case NOT_ON_RAIL -> {
                player.sendMessage(plugin.getConfigManager().getMessage("rail.not_on_rail", player));
                return;
            }
            case WRONG_DIRECTION -> {
                player.sendMessage(FormatUtil.replace(
                        plugin.getConfigManager().getMessage("rail.wrong_direction", player), "%name%", target.displayName()));
                return;
            }
            case NO_ROUTE -> {
                player.sendMessage(FormatUtil.replace(
                        plugin.getConfigManager().getMessage("rail.no_route_to_station", player), "%name%", target.displayName()));
                return;
            }
            case OK -> {
                // 下のstartSessionへ進む
            }
        }
        if (!plugin.getRailManager().startSession(cart, target)) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.no_route_to_station", player), "%name%", target.displayName()));
            return;
        }
        player.sendMessage(FormatUtil.replace(
                plugin.getConfigManager().getMessage("rail.departed", player), "%name%", target.displayName()));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(List.of("station", "line", "depart", "particle"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("station")) {
            return TabCompleteUtil.filterStartsWith(List.of("add", "remove", "list", "gui"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("line")) {
            return TabCompleteUtil.filterStartsWith(List.of("create", "remove", "list", "gui"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("depart")) {
            List<String> options = new ArrayList<>(stationNames());
            options.add("gui");
            return TabCompleteUtil.filterStartsWith(options, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("station") && args[1].equalsIgnoreCase("remove")) {
            return TabCompleteUtil.filterStartsWith(stationNames(), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("line") && args[1].equalsIgnoreCase("remove")) {
            return TabCompleteUtil.filterStartsWith(lineNames(), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("line") && args[1].equalsIgnoreCase("create")) {
            return TabCompleteUtil.filterStartsWith(List.of("oneway", "twoway"), args[3]);
        }
        if (args.length >= 5 && args[0].equalsIgnoreCase("line") && args[1].equalsIgnoreCase("create")) {
            return TabCompleteUtil.filterStartsWith(stationNames(), args[args.length - 1]);
        }
        return List.of();
    }

    private List<String> stationNames() {
        return plugin.getRailStationManager().listAll().stream().map(RailStationManager.Station::name).toList();
    }

    private List<String> lineNames() {
        return plugin.getRailLineManager().listAll().stream().map(RailLineManager.RailLine::name).toList();
    }
}
