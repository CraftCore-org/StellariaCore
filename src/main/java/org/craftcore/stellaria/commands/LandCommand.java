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
import org.craftcore.stellaria.managers.LandBorderParticleManager;
import org.craftcore.stellaria.managers.LandManager;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.ParticleUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * /land コマンド。claim/unclaim/info/list/map/help/bypassはargs[0]で直接ディスパッチする個人操作、
 * trust/untrust/trustlist/pvp/explosions/doors/chestsは/land area <sub>としてまとめてある
 * エリア（隣接claimの集合）単位の操作 — こちらを触った時だけエリア全体に変更が及ぶことを
 * コマンド名で明示するため、あえて独立したサブコマンドにせず"area"の下にネストしている。
 * /land rule <flag> <on|off|default>は逆に「今立っているチャンク1つだけ」の個別設定で、
 * エリアの設定を上書きする（defaultで個別設定を解除するとまたエリアの設定に従う）。
 */
public class LandCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "claim", "unclaim", "info", "list", "map", "border", "help", "area", "rule", "bypass", "unclaimable");
    private static final List<String> AREA_SUBCOMMANDS = List.of(
            "trust", "untrust", "trustlist", "pvp", "explosions", "doors", "chests");
    private static final List<String> AREA_FLAG_SUBCOMMANDS = List.of("pvp", "explosions", "doors", "chests");
    private static final List<String> RULE_FLAG_SUBCOMMANDS = List.of("pvp", "explosions", "doors", "chests");
    private static final List<String> ON_OFF = List.of("on", "off");
    private static final List<String> RULE_VALUES = List.of("on", "off", "default");

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
        if (plugin.getLandManager().isWorldDisabled(player.getWorld().getName())) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.world_disabled", player));
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "claim" -> handleClaim(player);
            case "unclaim" -> handleUnclaim(player);
            case "info" -> handleInfo(player);
            case "list" -> handleList(player);
            case "map" -> handleMap(player);
            case "border" -> handleBorder(player, args);
            case "help" -> handleHelp(player);
            case "area" -> handleArea(player, args);
            case "rule" -> handleRule(player, args);
            case "bypass" -> handleBypass(player);
            case "unclaimable" -> handleUnclaimable(player, args);
            default -> player.sendMessage(plugin.getConfigManager().getUsageMessage("land.usage", player));
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
            case UNCLAIMABLE -> player.sendMessage(
                    plugin.getConfigManager().getMessage("land.claim_unavailable", player));
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
        LandManager.UnclaimOutcome outcome = plugin.getLandManager().unclaim(player, adminOverride);
        switch (outcome.result()) {
            case SUCCESS -> {
                // 管理者が他人のclaimを解除した場合は、誰の土地だったかが分かる専用メッセージも出す
                // （オーナー本人には見えない操作なので、実行者側に何をしたか明示するため）。
                if (adminOverride && originalOwner != null && !originalOwner.equals(player.getUniqueId())) {
                    player.sendMessage(FormatUtil.replace(
                            plugin.getConfigManager().getMessage("land.unclaimed_admin_override", player),
                            "%owner%", ownerName(originalOwner)));
                }
                // outcome.refundAmount()はclaim時に実際に支払った金額（または移行前の既存claimなら
                // configのフォールバック価格）。現在のconfig価格(costText())をそのまま表示すると、
                // claim後にconfigが変更された場合に実際の返金額と表示がズレるため使わない。
                boolean refunded = outcome.refundAmount() > 0;
                String key = refunded ? "land.unclaimed" : "land.unclaimed_no_refund";
                String message = plugin.getConfigManager().getMessage(key, player);
                if (refunded) {
                    message = FormatUtil.replace(message, "%refund%", plugin.getEconomyManager().format(outcome.refundAmount()));
                }
                player.sendMessage(message);
            }
            case NOT_CLAIMED -> player.sendMessage(plugin.getConfigManager().getMessage("land.not_claimed", player));
            case NOT_OWNER -> player.sendMessage(plugin.getConfigManager().getMessage("land.not_your_claim", player));
            case DATABASE_ERROR -> player.sendMessage(plugin.getConfigManager().getMessage("land.database_error", player));
            case SELF_TARGET -> { }
        }
    }

    private void handleInfo(Player player) {
        Location location = player.getLocation();
        LandManager land = plugin.getLandManager();
        if (land.isUnclaimable(LandManager.ChunkKey.of(location))) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.info_unclaimable", player));
            return;
        }
        UUID owner = land.ownerOf(location);
        if (owner == null) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.info_unclaimed", player));
            return;
        }
        String message = plugin.getConfigManager().getMessage("land.info_owner", player);
        message = FormatUtil.replace(message, "%owner%", ownerName(owner));
        message = FormatUtil.replace(message, "%pvp_state%", onOffText(player, land.isPvpAllowed(location)));
        message = FormatUtil.replace(message, "%explosions_state%", onOffText(player, land.explosionsAllowed(location)));
        message = FormatUtil.replace(message, "%doors_state%", onOffText(player, land.doorsOpenToOthers(location)));
        message = FormatUtil.replace(message, "%chests_state%", onOffText(player, land.chestsOpenToOthers(location)));
        player.sendMessage(message);

        // どのフラグがこのチャンク個別の設定（/land rule）で上書きされているかを分かりやすく別行で示す
        // （エリア全体の設定と紛らわしくならないよう、上の行とは別メッセージにしている）。
        List<String> overridden = new ArrayList<>();
        if (land.chunkRuleOverride(location, LandManager.AreaFlag.PVP) != null) {
            overridden.add(plugin.getConfigManager().getMessage("land.rule_flag_name_pvp", player));
        }
        if (land.chunkRuleOverride(location, LandManager.AreaFlag.EXPLOSIONS) != null) {
            overridden.add(plugin.getConfigManager().getMessage("land.rule_flag_name_explosions", player));
        }
        if (land.chunkRuleOverride(location, LandManager.AreaFlag.DOORS) != null) {
            overridden.add(plugin.getConfigManager().getMessage("land.rule_flag_name_doors", player));
        }
        if (land.chunkRuleOverride(location, LandManager.AreaFlag.CHESTS) != null) {
            overridden.add(plugin.getConfigManager().getMessage("land.rule_flag_name_chests", player));
        }
        if (!overridden.isEmpty()) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("land.info_chunk_overrides", player),
                    "%flags%", String.join("、", overridden)));
        }
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

    /**
     * 現在地を中心に周辺チャンクの保護状態を正方形のグリッドで表示する。
     * マインクラのデフォルトフォントはmonospaceではない（文字ごとに描画幅が違う）ため、
     * 文字を変えて色分けすると列がズレる。これを避けるため、全マス同じ文字（■）だけを使い、
     * 色だけで状態を区別する（色は文字の描画幅に影響しないので、これなら確実に揃う）。
     */
    private void handleMap(Player player) {
        // 上限を設けていないと管理者の設定ミスで極端に大きいグリッドを送りかねないため、
        // このコマンド自体の妥当な使用範囲としてクランプする（DB等は絡まないので性能上の理由ではない）。
        int radius = Math.min(10, Math.max(1, plugin.getConfigManager().getInt("land.map-radius", 4)));
        World world = player.getWorld();
        LandManager.ChunkKey center = LandManager.ChunkKey.of(player.getLocation());
        LandManager land = plugin.getLandManager();
        UUID self = player.getUniqueId();

        player.sendMessage(plugin.getConfigManager().getMessage("land.map_header", player));
        player.sendMessage(FormatUtil.replace(
                plugin.getConfigManager().getMessage("land.map_facing", player), "%direction%", facing4(player)));
        player.sendMessage(plugin.getConfigManager().getMessage("land.map_axis", player));

        // 1行ずつ個別にsendMessageする（このコードベースの他の複数行出力＝handleHelp/trustlist等と
        // 同じ確立された方式に合わせる。1メッセージに\nを埋め込んで一括送信する方式は
        // このコードベースに前例が無く挙動が未検証のため、あえて採用しない）。
        for (int dz = -radius; dz <= radius; dz++) {
            StringBuilder row = new StringBuilder();
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx == 0 && dz == 0) {
                    row.append("&%e■");
                    continue;
                }
                Location cell = new Location(world, (center.chunkX() + dx) * 16.0, 64, (center.chunkZ() + dz) * 16.0);
                if (land.isUnclaimable(new LandManager.ChunkKey(world.getName(), center.chunkX() + dx, center.chunkZ() + dz))) {
                    row.append("&%5■");
                    continue;
                }
                UUID owner = land.ownerOf(cell);
                if (owner == null) {
                    row.append("&%8■");
                } else if (owner.equals(self)) {
                    row.append("&%a■");
                } else if (land.trustedPlayers(cell).contains(self)) {
                    row.append("&%b■");
                } else {
                    row.append("&%c■");
                }
            }
            player.sendMessage(FormatUtil.text(player, row.toString()));
        }

        for (String line : plugin.getConfigManager().getMessageList("land.map_legend")) {
            player.sendMessage(FormatUtil.text(player, line));
        }
    }

    /**
     * ヨー角を北/東/南/西の4方位に丸める。/land mapのグリッドがX/Z軸だけなので8方位ではなく4方位に対応させる。
     * Minecraftのヨーは0/360=南(+Z)、90=西(-X)、180=北(-Z)、270=東(+X)。
     */
    private String facing4(Player player) {
        float yaw = ((player.getLocation().getYaw() % 360) + 360) % 360;
        String key;
        if (yaw >= 45 && yaw < 135) {
            key = "land.map_direction_west";
        } else if (yaw >= 135 && yaw < 225) {
            key = "land.map_direction_north";
        } else if (yaw >= 225 && yaw < 315) {
            key = "land.map_direction_east";
        } else {
            key = "land.map_direction_south";
        }
        return plugin.getConfigManager().getMessage(key, player);
    }

    private void handleHelp(Player player) {
        for (String line : plugin.getConfigManager().getMessageList("land.help")) {
            player.sendMessage(FormatUtil.text(player, line));
        }
    }

    /** /land border [on|off] [半径]。引数なしは現在の状態を反転する。 */
    private void handleBorder(Player player, String[] args) {
        int defaultRadius = Math.max(1, plugin.getConfigManager().getInt(
                "land.border-particle.toggle-radius-default", 3));
        if (args.length == 1) {
            boolean enabled = plugin.getLandBorderParticleManager().toggle(player, defaultRadius, LandBorderParticleManager.Mode.CLAIMED);
            sendBorderState(player, enabled, defaultRadius);
            return;
        }

        String action = args[1].toLowerCase();
        if (action.equals("off") && args.length == 2) {
            plugin.getLandBorderParticleManager().disableAndForget(player.getUniqueId());
            player.sendMessage(plugin.getConfigManager().getMessage("land.border_disabled", player));
            return;
        }
        if (!action.equals("on") || args.length > 3) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("land.border_usage", player));
            return;
        }

        int radius = args.length == 3 ? parseBorderRadius(player, args[2]) : defaultRadius;
        if (radius < 1) {
            return;
        }
        plugin.getLandBorderParticleManager().enable(player, radius, LandBorderParticleManager.Mode.CLAIMED);
        sendBorderState(player, true, radius);
    }

    private int parseBorderRadius(Player player, String value) {
        int maxRadius = plugin.getConfigManager().getInt("land.border-particle.max-radius", 64);
        try {
            int radius = Integer.parseInt(value);
            if (radius >= 1 && radius <= maxRadius) {
                return radius;
            }
        } catch (NumberFormatException ignored) {
            // 下のusageへ統一する。
        }
        player.sendMessage(plugin.getConfigManager().getUsageMessage("land.border_usage", player));
        return -1;
    }

    private void sendBorderState(Player player, boolean enabled, int radius) {
        String message = plugin.getConfigManager().getMessage(
                enabled ? "land.border_enabled" : "land.border_disabled", player);
        player.sendMessage(FormatUtil.replace(message, "%radius%", String.valueOf(radius)));

        if (enabled) {
            int points = plugin.getLandBorderParticleManager().currentPointCount(player.getUniqueId());
            if (points == 0) {
                player.sendMessage(plugin.getConfigManager().getMessage("land.border_no_claims_nearby", player));
            }
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

    /** /land unclaimable <on|off>。運営用の隠しサブコマンドのため、補完・helpには載せない。 */
    private void handleUnclaimable(Player player, String[] args) {
        if (!player.hasPermission("stellaria.land.admin")) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.no_permission", player));
            return;
        }
        if (args.length != 2 || (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off"))) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("land.unclaimable_usage", player));
            return;
        }

        boolean enabled = args[1].equalsIgnoreCase("on");
        LandManager.UnclaimableChunkResult result = plugin.getLandManager().setUnclaimable(
                LandManager.ChunkKey.of(player.getLocation()), enabled);
        String messageKey = switch (result) {
            case SUCCESS -> enabled ? "land.unclaimable_enabled" : "land.unclaimable_disabled";
            case ALREADY_CLAIMED -> "land.unclaimable_claimed";
            case ALREADY_UNCLAIMABLE -> "land.unclaimable_already_enabled";
            case NOT_UNCLAIMABLE -> "land.unclaimable_already_disabled";
            case DATABASE_ERROR -> "land.database_error";
        };
        player.sendMessage(plugin.getConfigManager().getMessage(messageKey, player));
    }

    // ------------------------------------------------------------------
    // /land area <sub>
    // ------------------------------------------------------------------

    private void handleArea(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("land.area_usage", player));
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
            default -> player.sendMessage(plugin.getConfigManager().getUsageMessage("land.area_usage", player));
        }
    }

    private void handleAreaTrust(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("land.trust_usage", player));
            return;
        }
        String targetName = args[2];
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetName);
        if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline()) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.player_not_found", player));
            return;
        }
        LandManager.ActionResult result = plugin.getLandManager().trust(player, targetPlayer.getUniqueId());
        sendTrustResult(player, result, "land.trust_added", targetName);
    }

    private void handleAreaUntrust(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("land.untrust_usage", player));
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
            case DATABASE_ERROR -> player.sendMessage(plugin.getConfigManager().getMessage("land.database_error", player));
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
            player.sendMessage(plugin.getConfigManager().getUsageMessage(usageKey, player));
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
            case DATABASE_ERROR -> player.sendMessage(plugin.getConfigManager().getMessage("land.database_error", player));
        }
    }

    // ------------------------------------------------------------------
    // /land rule <flag> <on|off|default>（今立っているチャンク1つだけの個別設定）
    // ------------------------------------------------------------------

    private void handleRule(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("land.rule_usage", player));
            return;
        }
        String ruleSub = args[1].toLowerCase();
        switch (ruleSub) {
            case "pvp" -> handleRuleFlag(player, args, LandManager.AreaFlag.PVP,
                    "land.rule_pvp_usage", "land.rule_pvp_on", "land.rule_pvp_off", "land.rule_pvp_default");
            case "explosions" -> handleRuleFlag(player, args, LandManager.AreaFlag.EXPLOSIONS,
                    "land.rule_explosions_usage", "land.rule_explosions_on", "land.rule_explosions_off", "land.rule_explosions_default");
            case "doors" -> handleRuleFlag(player, args, LandManager.AreaFlag.DOORS,
                    "land.rule_doors_usage", "land.rule_doors_on", "land.rule_doors_off", "land.rule_doors_default");
            case "chests" -> handleRuleFlag(player, args, LandManager.AreaFlag.CHESTS,
                    "land.rule_chests_usage", "land.rule_chests_on", "land.rule_chests_off", "land.rule_chests_default");
            default -> player.sendMessage(plugin.getConfigManager().getUsageMessage("land.rule_usage", player));
        }
    }

    private void handleRuleFlag(Player player, String[] args, LandManager.AreaFlag flag,
                                 String usageKey, String onKey, String offKey, String defaultKey) {
        if (args.length < 3) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage(usageKey, player));
            return;
        }
        Boolean value;
        String successKey;
        switch (args[2].toLowerCase()) {
            case "on" -> { value = Boolean.TRUE; successKey = onKey; }
            case "off" -> { value = Boolean.FALSE; successKey = offKey; }
            case "default" -> { value = null; successKey = defaultKey; }
            default -> {
                player.sendMessage(plugin.getConfigManager().getUsageMessage(usageKey, player));
                return;
            }
        }
        LandManager.ActionResult result = plugin.getLandManager().setChunkRule(player, flag, value);
        switch (result) {
            case SUCCESS -> player.sendMessage(plugin.getConfigManager().getMessage(successKey, player));
            case NOT_CLAIMED -> player.sendMessage(plugin.getConfigManager().getMessage("land.info_unclaimed", player));
            case NOT_OWNER -> player.sendMessage(plugin.getConfigManager().getMessage("land.not_owner_trust", player));
            case SELF_TARGET -> { }
            case DATABASE_ERROR -> player.sendMessage(plugin.getConfigManager().getMessage("land.database_error", player));
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
            return ParticleUtil.parseColorOrFallback(
                    plugin.getConfigManager().getString("land.border-particle.color", "#55FF55"), Color.fromRGB(0x55FF55),
                    plugin.getLogger()::warning);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("land.border-particle.color の値が不正なため、デフォルト色にフォールバックします: " + e.getMessage());
            return Color.fromRGB(0x55FF55);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            List<String> visible = sender.hasPermission("stellaria.land.admin")
                    ? SUBCOMMANDS
                    : SUBCOMMANDS.stream().filter(s -> !s.equals("bypass") && !s.equals("unclaimable")).toList();
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
        if (args[0].equalsIgnoreCase("rule")) {
            if (args.length == 2) {
                return TabCompleteUtil.filterStartsWith(RULE_FLAG_SUBCOMMANDS, args[1]);
            }
            if (args.length == 3 && RULE_FLAG_SUBCOMMANDS.contains(args[1].toLowerCase())) {
                return TabCompleteUtil.filterStartsWith(RULE_VALUES, args[2]);
            }
        }
        if (args[0].equalsIgnoreCase("border") && args.length == 2) {
            return TabCompleteUtil.filterStartsWith(ON_OFF, args[1]);
        }
        return List.of();
    }
}
