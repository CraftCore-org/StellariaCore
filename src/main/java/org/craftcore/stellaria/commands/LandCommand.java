package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.LandManager;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.ParticleUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * /land コマンド。claim/unclaim/info/trust/untrust/trustlist/pvp/list/helpの9サブコマンドを
 * command.getName()ではなくargs[0]でディスパッチする（コマンド自体は"land"の1つだけのため）。
 */
public class LandCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "claim", "unclaim", "info", "trust", "untrust", "trustlist", "pvp", "list", "help");

    private final StellariaCore plugin;

    public LandCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("land.must_be_player", null));
            return true;
        }
        if (!player.hasPermission("stellaria.land")) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.no_permission", player));
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "claim" -> handleClaim(player);
            case "unclaim" -> handleUnclaim(player);
            case "info" -> handleInfo(player);
            case "trust" -> handleTrust(player, args);
            case "untrust" -> handleUntrust(player, args);
            case "trustlist" -> handleTrustList(player);
            case "pvp" -> handlePvp(player, args);
            case "list" -> handleList(player);
            case "help" -> handleHelp(player);
            default -> player.sendMessage(plugin.getConfigManager().getMessage("land.usage", player));
        }
        return true;
    }

    private void handleClaim(Player player) {
        LandManager.ChunkKey key = LandManager.ChunkKey.of(player.getLocation());
        LandManager.ClaimResult result = plugin.getLandManager().claim(player);
        switch (result) {
            case SUCCESS -> {
                player.sendMessage(FormatUtil.replace(
                        plugin.getConfigManager().getMessage("land.claimed", player), "%cost%", costText()));
                showClaimBorder(player, key);
            }
            case ALREADY_CLAIMED -> {
                UUID owner = plugin.getLandManager().ownerOf(player.getLocation());
                player.sendMessage(FormatUtil.replace(
                        plugin.getConfigManager().getMessage("land.already-claimed", player),
                        "%owner%", ownerName(owner)));
            }
            case LIMIT_REACHED -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("land.limit-reached", player),
                    "%max%", String.valueOf(plugin.getConfigManager().getInt("land.max-chunks-per-player", 20))));
            case INSUFFICIENT_FUNDS -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("land.insufficient-funds", player), "%cost%", costText()));
            case WORLD_DISABLED -> player.sendMessage(
                    plugin.getConfigManager().getMessage("land.world-disabled", player));
        }
    }

    private void handleUnclaim(Player player) {
        boolean adminOverride = player.hasPermission("stellaria.land.admin");
        LandManager.ActionResult result = plugin.getLandManager().unclaim(player, adminOverride);
        switch (result) {
            case SUCCESS -> {
                boolean refunded = plugin.getConfigManager().getBoolean("land.refund-on-unclaim", true);
                String key = refunded ? "land.unclaimed" : "land.unclaimed-no-refund";
                String message = plugin.getConfigManager().getMessage(key, player);
                if (refunded) {
                    message = FormatUtil.replace(message, "%refund%", costText());
                }
                player.sendMessage(message);
            }
            case NOT_CLAIMED -> player.sendMessage(plugin.getConfigManager().getMessage("land.not-claimed", player));
            case NOT_OWNER -> player.sendMessage(plugin.getConfigManager().getMessage("land.not-your-claim", player));
        }
    }

    private void handleInfo(Player player) {
        UUID owner = plugin.getLandManager().ownerOf(player.getLocation());
        if (owner == null) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.info-unclaimed", player));
            return;
        }
        boolean pvpAllowed = plugin.getLandManager().isPvpAllowed(player.getLocation());
        String pvpState = plugin.getConfigManager().getMessage(
                pvpAllowed ? "land.pvp-state-on" : "land.pvp-state-off", player);
        String message = plugin.getConfigManager().getMessage("land.info-owner", player);
        message = FormatUtil.replace(message, "%owner%", ownerName(owner));
        message = FormatUtil.replace(message, "%pvp_state%", pvpState);
        player.sendMessage(message);
    }

    private void handleTrust(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.trust-usage", player));
            return;
        }
        UUID target = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
        LandManager.ActionResult result = plugin.getLandManager().trust(player, target);
        sendTrustResult(player, result, "land.trust-added", args[1]);
    }

    private void handleUntrust(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.untrust-usage", player));
            return;
        }
        UUID target = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
        LandManager.ActionResult result = plugin.getLandManager().untrust(player, target);
        sendTrustResult(player, result, "land.trust-removed", args[1]);
    }

    private void sendTrustResult(Player player, LandManager.ActionResult result, String successKey, String targetName) {
        switch (result) {
            case SUCCESS -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage(successKey, player), "%target%", targetName));
            case NOT_CLAIMED -> player.sendMessage(plugin.getConfigManager().getMessage("land.not-claimed", player));
            case NOT_OWNER -> player.sendMessage(plugin.getConfigManager().getMessage("land.not-owner-trust", player));
        }
    }

    private void handleTrustList(Player player) {
        UUID owner = plugin.getLandManager().ownerOf(player.getLocation());
        if (owner == null) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.info-unclaimed", player));
            return;
        }
        Set<UUID> trusted = plugin.getLandManager().trustedPlayers(player.getLocation());
        if (trusted.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.trustlist-empty", player));
            return;
        }
        player.sendMessage(plugin.getConfigManager().getMessage("land.trustlist-header", player));
        for (UUID uuid : trusted) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("land.trustlist-entry", player), "%name%", ownerName(uuid)));
        }
    }

    private void handlePvp(Player player, String[] args) {
        if (args.length < 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.pvp-usage", player));
            return;
        }
        boolean enable = args[1].equalsIgnoreCase("on");
        LandManager.ActionResult result = plugin.getLandManager().setPvpEnabled(player, enable);
        switch (result) {
            case SUCCESS -> player.sendMessage(plugin.getConfigManager().getMessage(
                    enable ? "land.pvp-enabled" : "land.pvp-disabled", player));
            case NOT_CLAIMED -> player.sendMessage(plugin.getConfigManager().getMessage("land.not-claimed", player));
            case NOT_OWNER -> player.sendMessage(plugin.getConfigManager().getMessage("land.not-owner-trust", player));
        }
    }

    private void handleList(Player player) {
        int chunks = plugin.getLandManager().countClaims(player.getUniqueId());
        int territories = plugin.getLandManager().territoryCountFor(player.getUniqueId());
        int max = plugin.getConfigManager().getInt("land.max-chunks-per-player", 20);
        String message = plugin.getConfigManager().getMessage("land.list", player);
        message = FormatUtil.replace(message, "%chunks%", String.valueOf(chunks));
        message = FormatUtil.replace(message, "%max%", String.valueOf(max));
        message = FormatUtil.replace(message, "%territories%", String.valueOf(territories));
        player.sendMessage(message);
    }

    private void handleHelp(Player player) {
        for (String line : plugin.getConfigManager().getMessageList("land.help")) {
            player.sendMessage(FormatUtil.text(player, line));
        }
    }

    private String costText() {
        return plugin.getEconomyManager().format(plugin.getConfigManager().getDouble("land.cost-per-chunk", 500));
    }

    private String ownerName(UUID uuid) {
        if (uuid == null) {
            return "?";
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        return name != null ? name : uuid.toString();
    }

    /**
     * claim直後、そのチャンクの4辺の境界にパーティクルを一定時間（land.border-particle.duration-seconds）
     * 表示する。0.5秒間隔で描き直すことで持続しているように見せる（TpaCore/HomeCommandのテレポート演出と
     * 同じくParticleUtilを使うが、円ではなく矩形の4辺をspawnLineで描く点が異なる）。
     */
    private void showClaimBorder(Player player, LandManager.ChunkKey key) {
        if (!plugin.getConfigManager().getBoolean("land.border-particle.enabled", true)) {
            return;
        }
        World world = player.getWorld();
        double minX = key.chunkX() * 16.0;
        double minZ = key.chunkZ() * 16.0;
        double maxX = minX + 16.0;
        double maxZ = minZ + 16.0;
        double y = player.getLocation().getY();

        Location nw = new Location(world, minX, y, minZ);
        Location ne = new Location(world, maxX, y, minZ);
        Location se = new Location(world, maxX, y, maxZ);
        Location sw = new Location(world, minX, y, maxZ);

        Particle particle = resolveBorderParticle();
        int durationSeconds = plugin.getConfigManager().getInt("land.border-particle.duration-seconds", 3);
        int totalRuns = Math.max(1, durationSeconds * 2);
        int[] runsLeft = {totalRuns};

        if (particle == Particle.DUST) {
            Color color = resolveBorderColor();
            float size = (float) plugin.getConfigManager().getDouble("land.border-particle.size", 1.0);
            Bukkit.getRegionScheduler().runAtFixedRate(plugin, nw, task -> {
                ParticleUtil.spawnLine(nw, ne, 0.5, color, size);
                ParticleUtil.spawnLine(ne, se, 0.5, color, size);
                ParticleUtil.spawnLine(se, sw, 0.5, color, size);
                ParticleUtil.spawnLine(sw, nw, 0.5, color, size);
                if (--runsLeft[0] <= 0) {
                    task.cancel();
                }
            }, 1L, 10L);
        } else {
            Bukkit.getRegionScheduler().runAtFixedRate(plugin, nw, task -> {
                ParticleUtil.spawnLine(nw, ne, 0.5, particle);
                ParticleUtil.spawnLine(ne, se, 0.5, particle);
                ParticleUtil.spawnLine(se, sw, 0.5, particle);
                ParticleUtil.spawnLine(sw, nw, 0.5, particle);
                if (--runsLeft[0] <= 0) {
                    task.cancel();
                }
            }, 1L, 10L);
        }
    }

    /** 設定ファイルのパーティクル種別を解決する。不正な値ならDUSTにフォールバックし、警告をログへ出す。 */
    private Particle resolveBorderParticle() {
        try {
            return Particle.valueOf(plugin.getConfigManager().getString("land.border-particle.particle", "DUST"));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("land.border-particle.particle の値が不正なため、DUSTにフォールバックします: " + e.getMessage());
            return Particle.DUST;
        }
    }

    /** 設定ファイルの境界パーティクル色を解決する。不正な値ならデフォルト色にフォールバックし、警告をログへ出す。 */
    private Color resolveBorderColor() {
        try {
            return ParticleUtil.parseColor(plugin.getConfigManager().getString("land.border-particle.color", "#55FF55"));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("land.border-particle.color の値が不正なため、デフォルト色にフォールバックします: " + e.getMessage());
            return ParticleUtil.parseColor("#55FF55");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust"))) {
            return TabCompleteUtil.onlinePlayerNames(args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pvp")) {
            return TabCompleteUtil.filterStartsWith(List.of("on", "off"), args[1]);
        }
        return List.of();
    }
}
