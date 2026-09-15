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
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.ParticleUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * /land コマンド。claim/unclaim/info/list/help/bypassはargs[0]で直接ディスパッチする個人操作、
 * trust/untrust/trustlist/pvp/explosions/doors/chestsは/land area <sub>としてまとめてある
 * エリア（隣接claimの集合）単位の操作 — こちらを触った時だけエリア全体に変更が及ぶことを
 * コマンド名で明示するため、あえて独立したサブコマンドにせず"area"の下にネストしている。
 */
public class LandCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "claim", "unclaim", "info", "list", "help", "area", "bypass");
    private static final List<String> AREA_SUBCOMMANDS = List.of(
            "trust", "untrust", "trustlist", "pvp", "explosions", "doors", "chests");
    private static final List<String> AREA_FLAG_SUBCOMMANDS = List.of("pvp", "explosions", "doors", "chests");
    private static final List<String> ON_OFF = List.of("on", "off");

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
            case "list" -> handleList(player);
            case "help" -> handleHelp(player);
            case "area" -> handleArea(player, args);
            case "bypass" -> handleBypass(player);
            default -> player.sendMessage(plugin.getConfigManager().getMessage("land.usage", player));
        }
        return true;
    }

    private void handleClaim(Player player) {
        LandManager.ChunkKey key = LandManager.ChunkKey.of(player.getLocation());
        LandManager.ClaimOutcome outcome = plugin.getLandManager().claim(player);
        switch (outcome.result()) {
            case SUCCESS -> {
                player.sendMessage(FormatUtil.replace(
                        plugin.getConfigManager().getMessage("land.claimed", player), "%cost%", costText()));
                if (outcome.merged()) {
                    String pvpState = plugin.getConfigManager().getMessage(
                            outcome.pvpEnabled() ? "land.pvp_state_on" : "land.pvp_state_off", player);
                    player.sendMessage(FormatUtil.replace(
                            plugin.getConfigManager().getMessage("land.area_merged", player),
                            "%pvp_state%", pvpState));
                }
                showClaimBorder(player, key);
            }
            case ALREADY_CLAIMED -> {
                UUID owner = plugin.getLandManager().ownerOf(player.getLocation());
                player.sendMessage(FormatUtil.replace(
                        plugin.getConfigManager().getMessage("land.already_claimed", player),
                        "%owner%", ownerName(owner)));
            }
            case LIMIT_REACHED -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("land.limit_reached", player),
                    "%max%", String.valueOf(plugin.getConfigManager().getInt("land.max-chunks-per-player", 20))));
            case INSUFFICIENT_FUNDS -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("land.insufficient_funds", player), "%cost%", costText()));
            case WORLD_DISABLED -> player.sendMessage(
                    plugin.getConfigManager().getMessage("land.world_disabled", player));
        }
    }

    private void handleUnclaim(Player player) {
        UUID originalOwner = plugin.getLandManager().ownerOf(player.getLocation());
        boolean adminOverride = player.hasPermission("stellaria.land.admin");
        LandManager.ActionResult result = plugin.getLandManager().unclaim(player, adminOverride);
        switch (result) {
            case SUCCESS -> {
                // 管理者が他人のclaimを解除した場合は、誰の土地だったかが分かる専用メッセージも出す
                // （オーナー本人には見えない操作なので、実行者側に何をしたか明示するため）。
                if (adminOverride && originalOwner != null && !originalOwner.equals(player.getUniqueId())) {
                    player.sendMessage(FormatUtil.replace(
                            plugin.getConfigManager().getMessage("land.unclaimed_admin_override", player),
                            "%owner%", ownerName(originalOwner)));
                }
                boolean refunded = plugin.getConfigManager().getBoolean("land.refund-on-unclaim", true);
                String key = refunded ? "land.unclaimed" : "land.unclaimed_no_refund";
                String message = plugin.getConfigManager().getMessage(key, player);
                if (refunded) {
                    message = FormatUtil.replace(message, "%refund%", costText());
                }
                player.sendMessage(message);
            }
            case NOT_CLAIMED -> player.sendMessage(plugin.getConfigManager().getMessage("land.not_claimed", player));
            case NOT_OWNER -> player.sendMessage(plugin.getConfigManager().getMessage("land.not_your_claim", player));
            case SELF_TARGET -> { }
        }
    }

    private void handleInfo(Player player) {
        Location location = player.getLocation();
        UUID owner = plugin.getLandManager().ownerOf(location);
        if (owner == null) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.info_unclaimed", player));
            return;
        }
        String message = plugin.getConfigManager().getMessage("land.info_owner", player);
        message = FormatUtil.replace(message, "%owner%", ownerName(owner));
        message = FormatUtil.replace(message, "%pvp_state%", onOffText(player, plugin.getLandManager().isPvpAllowed(location)));
        message = FormatUtil.replace(message, "%explosions_state%", onOffText(player, plugin.getLandManager().explosionsAllowed(location)));
        message = FormatUtil.replace(message, "%doors_state%", onOffText(player, plugin.getLandManager().doorsOpenToOthers(location)));
        message = FormatUtil.replace(message, "%chests_state%", onOffText(player, plugin.getLandManager().chestsOpenToOthers(location)));
        player.sendMessage(message);
    }

    /** land.pvp_state_on/offは元々PvP専用の文言だが「有効」「無効」の一般語なので他3フラグの表示にも流用する。 */
    private String onOffText(Player player, boolean on) {
        return plugin.getConfigManager().getMessage(on ? "land.pvp_state_on" : "land.pvp_state_off", player);
    }

    private void handleList(Player player) {
        int chunks = plugin.getLandManager().countClaims(player.getUniqueId());
        int areaCount = plugin.getLandManager().areaCountFor(player.getUniqueId());
        int max = plugin.getConfigManager().getInt("land.max-chunks-per-player", 20);
        String message = plugin.getConfigManager().getMessage("land.list", player);
        message = FormatUtil.replace(message, "%chunks%", String.valueOf(chunks));
        message = FormatUtil.replace(message, "%max%", String.valueOf(max));
        message = FormatUtil.replace(message, "%areas%", String.valueOf(areaCount));
        player.sendMessage(message);
    }

    private void handleHelp(Player player) {
        for (String line : plugin.getConfigManager().getMessageList("land.help")) {
            player.sendMessage(FormatUtil.text(player, line));
        }
    }

    private static final String BYPASS_ACTIONBAR_CHANNEL = "land_bypass";

    /**
     * ON中は常設アクションバー表示（ActionBarManager#setChannel、期限なし）でbypass中であることを
     * 可視化する。トグルし忘れて気付かず保護を無視し続ける、という元々の苦情の再発を防ぐため。
     */
    private void handleBypass(Player player) {
        if (!player.hasPermission("stellaria.land.admin")) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.no_permission", player));
            return;
        }
        boolean enabled = plugin.getLandManager().toggleBypass(player);
        player.sendMessage(plugin.getConfigManager().getMessage(
                enabled ? "land.bypass_enabled" : "land.bypass_disabled", player));
        if (enabled) {
            plugin.getActionBarManager().setChannel(player, BYPASS_ACTIONBAR_CHANNEL,
                    ColorUtil.component(plugin.getConfigManager().getMessage("land.bypass_indicator", player)));
        } else {
            plugin.getActionBarManager().clearChannel(player, BYPASS_ACTIONBAR_CHANNEL);
        }
    }

    // ------------------------------------------------------------------
    // /land area <sub>
    // ------------------------------------------------------------------

    private void handleArea(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.area_usage", player));
            return;
        }
        String areaSub = args[1].toLowerCase();
        switch (areaSub) {
            case "trust" -> handleAreaTrust(player, args);
            case "untrust" -> handleAreaUntrust(player, args);
            case "trustlist" -> handleAreaTrustList(player);
            case "pvp" -> handleAreaFlag(player, args, LandManager.AreaFlag.PVP,
                    "land.pvp_usage", "land.pvp_enabled", "land.pvp_disabled");
            case "explosions" -> handleAreaFlag(player, args, LandManager.AreaFlag.EXPLOSIONS,
                    "land.explosions_usage", "land.explosions_enabled", "land.explosions_disabled");
            case "doors" -> handleAreaFlag(player, args, LandManager.AreaFlag.DOORS,
                    "land.doors_usage", "land.doors_enabled", "land.doors_disabled");
            case "chests" -> handleAreaFlag(player, args, LandManager.AreaFlag.CHESTS,
                    "land.chests_usage", "land.chests_enabled", "land.chests_disabled");
            default -> player.sendMessage(plugin.getConfigManager().getMessage("land.area_usage", player));
        }
    }

    private void handleAreaTrust(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.trust_usage", player));
            return;
        }
        String targetName = args[2];
        UUID target = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        LandManager.ActionResult result = plugin.getLandManager().trust(player, target);
        sendTrustResult(player, result, "land.trust_added", targetName);
    }

    private void handleAreaUntrust(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.untrust_usage", player));
            return;
        }
        String targetName = args[2];
        UUID target = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        LandManager.ActionResult result = plugin.getLandManager().untrust(player, target);
        sendTrustResult(player, result, "land.trust_removed", targetName);
    }

    private void sendTrustResult(Player player, LandManager.ActionResult result, String successKey, String targetName) {
        switch (result) {
            case SUCCESS -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage(successKey, player), "%target%", targetName));
            case NOT_CLAIMED -> player.sendMessage(plugin.getConfigManager().getMessage("land.info_unclaimed", player));
            case NOT_OWNER -> player.sendMessage(plugin.getConfigManager().getMessage("land.not_owner_trust", player));
            case SELF_TARGET -> player.sendMessage(plugin.getConfigManager().getMessage("land.trust_self", player));
        }
    }

    private void handleAreaTrustList(Player player) {
        UUID owner = plugin.getLandManager().ownerOf(player.getLocation());
        if (owner == null) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.info_unclaimed", player));
            return;
        }
        Set<UUID> trusted = plugin.getLandManager().trustedPlayers(player.getLocation());
        if (trusted.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.trustlist_empty", player));
            return;
        }
        player.sendMessage(plugin.getConfigManager().getMessage("land.trustlist_header", player));
        for (UUID uuid : trusted) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("land.trustlist_entry", player), "%name%", ownerName(uuid)));
        }
    }

    private void handleAreaFlag(Player player, String[] args, LandManager.AreaFlag flag,
                                 String usageKey, String enabledKey, String disabledKey) {
        if (args.length < 3 || !(args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off"))) {
            player.sendMessage(plugin.getConfigManager().getMessage(usageKey, player));
            return;
        }
        boolean enable = args[2].equalsIgnoreCase("on");
        LandManager.ActionResult result = plugin.getLandManager().setAreaFlag(player, flag, enable);
        switch (result) {
            case SUCCESS -> player.sendMessage(plugin.getConfigManager().getMessage(
                    enable ? enabledKey : disabledKey, player));
            case NOT_CLAIMED -> player.sendMessage(plugin.getConfigManager().getMessage("land.info_unclaimed", player));
            case NOT_OWNER -> player.sendMessage(plugin.getConfigManager().getMessage("land.not_owner_trust", player));
            case SELF_TARGET -> { }
        }
    }

    // ------------------------------------------------------------------
    // 補助
    // ------------------------------------------------------------------

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
            List<String> visible = sender.hasPermission("stellaria.land.admin")
                    ? SUBCOMMANDS
                    : SUBCOMMANDS.stream().filter(s -> !s.equals("bypass")).toList();
            return TabCompleteUtil.filterStartsWith(visible, args[0]);
        }
        if (args[0].equalsIgnoreCase("area")) {
            if (args.length == 2) {
                return TabCompleteUtil.filterStartsWith(AREA_SUBCOMMANDS, args[1]);
            }
            if (args.length == 3 && (args[1].equalsIgnoreCase("trust") || args[1].equalsIgnoreCase("untrust"))) {
                return TabCompleteUtil.onlinePlayerNames(args[2]);
            }
            if (args.length == 3 && AREA_FLAG_SUBCOMMANDS.contains(args[1].toLowerCase())) {
                return TabCompleteUtil.filterStartsWith(ON_OFF, args[2]);
            }
        }
        return List.of();
    }
}
