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
import org.craftcore.stellaria.gui.WarpSelectGui;
import org.craftcore.stellaria.managers.WarpManager;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.ParticleUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.craftcore.stellaria.utils.TeleportSafetyUtil;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /setwarp・/warp・/delwarp・/warps の4コマンドをまとめて処理するExecutor。
 * TpaCore・HomeCommandと同じく command.getName() でディスパッチする。
 */
public class WarpCommand implements CommandExecutor, TabCompleter {

    // /warp実行時に着地点が不安全だった場合の確認待ち状態（プレイヤー1人につき1件、homeとは別マップ）
    private static final Map<UUID, TeleportSafetyUtil.PendingConfirm> PENDING_CONFIRM = new HashMap<>();

    private final StellariaCore plugin;

    public WarpCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("warp.must_be_player", null));
            return true;
        }
        if (!player.hasPermission("stellaria.warp")) {
            player.sendMessage(plugin.getConfigManager().getMessage("warp.no_permission", player));
            return true;
        }

        switch (command.getName()) {
            case "setwarp" -> handleSetWarp(player, args);
            case "warp" -> handleWarp(player, args);
            case "delwarp" -> handleDelWarp(player, args);
            case "warps" -> handleWarps(player, args);
            default -> { }
        }
        return true;
    }

    private void handleSetWarp(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(plugin.getConfigManager().getMessage("warp.usage_setwarp", player));
            return;
        }
        String name = args[0];
        WarpManager.SetResult result = plugin.getWarpManager().set(player, name, player.getLocation());
        switch (result) {
            case SUCCESS -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("warp.created", player), "%name%", name));
            case LIMIT_REACHED -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("warp.limit_reached", player),
                    "%max%", String.valueOf(plugin.getConfigManager().getInt("warp.max-per-player", 5))));
            case NAME_TAKEN -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("warp.name_taken", player), "%name%", name));
            case INSUFFICIENT_FUNDS -> player.sendMessage(
                    plugin.getConfigManager().getMessage("warp.insufficient_funds", player));
        }
    }

    private void handleWarp(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(plugin.getConfigManager().getMessage("warp.usage_warp", player));
            return;
        }
        teleportToWarp(player, args[0]);
    }

    /**
     * 名前付きワープへ移動する。/warp とGUI選択のどちらからも呼ばれ、危険地点の確認状態も共有する。
     */
    public void teleportToWarp(Player player, String name) {
        Location destination = plugin.getWarpManager().get(name);
        if (destination == null) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("warp.not_found", player), "%name%", name));
            return;
        }
        TeleportSafetyUtil.Result result = TeleportSafetyUtil.attempt(player, destination, PENDING_CONFIRM);
        if (result == TeleportSafetyUtil.Result.TELEPORTED) {
            playTeleportEffect(destination);
        } else {
            player.sendMessage(plugin.getConfigManager().getMessage("warp.unsafe_warning", player));
        }
    }

    /**
     * テレポート成功時、着地点に config.yml の warp.teleport-effect.* で指定した円パーティクルを出す。
     * TpaCore#playTeleportEffect と同じロジックを warp 専用の色（デフォルトはグリーン）で使う。
     */
    private void playTeleportEffect(Location location) {
        if (!plugin.getConfigManager().getBoolean("warp.teleport-effect.enabled", true)) return;

        Particle particle = Particle.valueOf(plugin.getConfigManager().getString("warp.teleport-effect.particle", "DUST"));
        double radius = plugin.getConfigManager().getDouble("warp.teleport-effect.radius", 1.0);
        int points = plugin.getConfigManager().getInt("warp.teleport-effect.points", 30);

        if (particle == Particle.DUST) {
            Color color = ParticleUtil.parseColor(plugin.getConfigManager().getString("warp.teleport-effect.color", "#55FF55"));
            float size = (float) plugin.getConfigManager().getDouble("warp.teleport-effect.size", 1.0);
            ParticleUtil.spawnCircle(location, radius, points, color, size);
        } else {
            ParticleUtil.spawnCircle(location, radius, points, particle);
        }
    }

    private void handleDelWarp(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(plugin.getConfigManager().getMessage("warp.usage_delwarp", player));
            return;
        }
        String name = args[0];
        UUID owner = plugin.getWarpManager().getOwner(name);
        if (owner == null) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("warp.not_found", player), "%name%", name));
            return;
        }
        boolean isOwner = owner.equals(player.getUniqueId());
        if (!isOwner && !player.hasPermission("stellaria.warp.delete.others")) {
            player.sendMessage(plugin.getConfigManager().getMessage("warp.delete_no_permission_others", player));
            return;
        }
        plugin.getWarpManager().delete(name);
        player.sendMessage(FormatUtil.replace(
                plugin.getConfigManager().getMessage("warp.deleted", player), "%name%", name));
    }

    private void handleWarps(Player player, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("gui")) {
            new WarpSelectGui(plugin, this).open(player);
            return;
        }

        List<WarpManager.WarpEntry> warps = plugin.getWarpManager().listAll();
        if (warps.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().getMessage("warp.list_empty", player));
            return;
        }
        player.sendMessage(plugin.getConfigManager().getMessage("warp.list_header", player));
        player.sendMessage(ColorUtil.component(plugin.getConfigManager().getMessage("warp.list_gui_hint", player))
                .clickEvent(ClickEvent.runCommand("/warps gui")));
        for (WarpManager.WarpEntry warp : warps) {
            String ownerName = warp.ownerName() != null ? warp.ownerName() : "?";
            String line = plugin.getConfigManager().getMessage("warp.list_entry", player);
            line = FormatUtil.replace(line, "%name%", warp.name());
            line = FormatUtil.replace(line, "%owner%", ownerName);
            Component entry = ColorUtil.component(line);
            player.sendMessage(entry.clickEvent(ClickEvent.runCommand("/warp " + warp.name())));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (command.getName().equals("warps")) {
            return args.length == 1 ? TabCompleteUtil.filterStartsWith(List.of("gui"), args[0]) : List.of();
        }
        if (args.length != 1 || command.getName().equals("setwarp")) {
            return List.of();
        }
        List<String> names = plugin.getWarpManager().listAll().stream()
                .map(WarpManager.WarpEntry::name)
                .toList();
        return TabCompleteUtil.filterStartsWith(names, args[0]);
    }
}
