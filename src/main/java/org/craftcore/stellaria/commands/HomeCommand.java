package org.craftcore.stellaria.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.gui.HomeSelectGui;
import org.craftcore.stellaria.managers.HomeManager;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.ParticleUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.craftcore.stellaria.utils.TeleportSafetyUtil;
import org.craftcore.stellaria.utils.WorldBlacklistUtil;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /sethome・/home・/delhome・/homes の4コマンドをまとめて処理するExecutor。
 * TpaCoreと同じく command.getName() でディスパッチする。
 */
public class HomeCommand implements CommandExecutor, TabCompleter {

    // /home実行時に着地点が不安全だった場合の確認待ち状態（プレイヤー1人につき1件）
    private static final Map<UUID, TeleportSafetyUtil.PendingConfirm> PENDING_CONFIRM = new HashMap<>();

    private final StellariaCore plugin;

    public HomeCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("home.must_be_player", null));
            return true;
        }
        if (!player.hasPermission("stellaria.home")) {
            player.sendMessage(plugin.getConfigManager().getMessage("home.no_permission", player));
            return true;
        }
        if (WorldBlacklistUtil.isBlacklisted(plugin.getConfigManager().getStringList("home.disabled-worlds", true), player.getWorld().getName())) {
            player.sendMessage(plugin.getConfigManager().getMessage("home.world_disabled", player));
            return true;
        }

        switch (command.getName()) {
            case "sethome" -> handleSetHome(player, args);
            case "home" -> handleHome(player, args);
            case "delhome" -> handleDelHome(player, args);
            case "homes" -> handleHomes(player, args);
            default -> { }
        }
        return true;
    }

    private void handleSetHome(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("home.usage_sethome", player));
            return;
        }
        String name = args[0];
        HomeManager.SetResult result = plugin.getHomeManager().set(player, name, player.getLocation());
        switch (result) {
            case SUCCESS -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("home.created", player), "%name%", name));
            case LIMIT_REACHED -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("home.limit_reached", player),
                    "%max%", String.valueOf(plugin.getConfigManager().getInt("home.max-per-player", 5))));
            case NAME_TAKEN -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("home.name_taken", player), "%name%", name));
            case INSUFFICIENT_FUNDS -> player.sendMessage(
                    plugin.getConfigManager().getMessage("home.insufficient_funds", player));
            case DATABASE_ERROR -> player.sendMessage(
                    plugin.getConfigManager().getMessage("home.save_failed", player));
        }
    }

    private void handleHome(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("home.usage_home", player));
            return;
        }
        teleportToHome(player, args[0]);
    }

    /**
     * 名前付きホームへ移動する。/home とGUI選択のどちらからも呼ばれ、危険地点の確認状態も共有する。
     */
    public void teleportToHome(Player player, String name) {
        Location destination = plugin.getHomeManager().get(player.getUniqueId(), name);
        if (destination == null) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("home.not_found", player), "%name%", name));
            return;
        }
        if (WorldBlacklistUtil.isBlacklisted(plugin.getConfigManager().getStringList("home.disabled-worlds", true), destination.getWorld().getName())) {
            player.sendMessage(plugin.getConfigManager().getMessage("home.world_disabled", player));
            return;
        }
        TeleportSafetyUtil.Result result = TeleportSafetyUtil.attempt(player, destination, PENDING_CONFIRM);
        if (result == TeleportSafetyUtil.Result.TELEPORTED) {
            playTeleportEffect(destination);
        } else if (result == TeleportSafetyUtil.Result.WARNED) {
            player.sendMessage(plugin.getConfigManager().getMessage("home.unsafe_warning", player));
        } else {
            player.sendMessage(plugin.getConfigManager().getMessage("home.teleport_failed", player));
        }
    }

    /**
     * テレポート成功時、着地点に config.yml の home.teleport-effect.* で指定した円パーティクルを出す。
     * TpaCore#playTeleportEffect と同じロジックを home 専用の色（デフォルトはオレンジ）で使う。
     */
    private void playTeleportEffect(Location location) {
        if (!plugin.getConfigManager().getBoolean("home.teleport-effect.enabled", true)) return;

        Particle particle = ParticleUtil.resolveParticle(plugin.getConfigManager().getString("home.teleport-effect.particle", "DUST"), plugin.getLogger()::warning);
        double radius = plugin.getConfigManager().getDouble("home.teleport-effect.radius", 1.0);
        int points = plugin.getConfigManager().getInt("home.teleport-effect.points", 30);

        if (particle == Particle.DUST) {
            Color color = ParticleUtil.parseColorOrFallback(
                    plugin.getConfigManager().getString("home.teleport-effect.color", "#FFAA00"), Color.fromRGB(0xFFAA00),
                    plugin.getLogger()::warning);
            float size = (float) plugin.getConfigManager().getDouble("home.teleport-effect.size", 1.0);
            ParticleUtil.spawnCircle(location, radius, points, color, size);
        } else {
            ParticleUtil.spawnCircle(location, radius, points, particle);
        }
    }

    public static void clearPendingConfirm(UUID uuid) {
        PENDING_CONFIRM.remove(uuid);
    }

    private void handleDelHome(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("home.usage_delhome", player));
            return;
        }
        String name = args[0];
        boolean deleted = plugin.getHomeManager().delete(player.getUniqueId(), name);
        String key = deleted ? "home.deleted" : "home.not_found";
        player.sendMessage(FormatUtil.replace(plugin.getConfigManager().getMessage(key, player), "%name%", name));
    }

    private void handleHomes(Player player, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("gui")) {
            new HomeSelectGui(plugin, this, player).open(player);
            return;
        }

        List<String> names = plugin.getHomeManager().listNames(player.getUniqueId());
        if (names.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().getMessage("home.list_empty", player));
            return;
        }
        player.sendMessage(plugin.getConfigManager().getMessage("home.list_header", player));
        player.sendMessage(ColorUtil.component(plugin.getConfigManager().getMessage("home.list_gui_hint", player))
                .clickEvent(ClickEvent.runCommand("/homes gui")));
        for (String name : names) {
            Component entry = ColorUtil.component(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("home.list_entry", player), "%name%", name));
            player.sendMessage(entry.clickEvent(ClickEvent.runCommand("/home " + name)));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (command.getName().equals("homes")) {
            return args.length == 1 ? TabCompleteUtil.filterStartsWith(List.of("gui"), args[0]) : List.of();
        }
        if (args.length != 1 || !(sender instanceof Player player) || command.getName().equals("sethome")) {
            return List.of();
        }
        return TabCompleteUtil.filterStartsWith(plugin.getHomeManager().listNames(player.getUniqueId()), args[0]);
    }
}
