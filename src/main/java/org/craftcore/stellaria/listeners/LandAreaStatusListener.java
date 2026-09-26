package org.craftcore.stellaria.listeners;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.LandManager;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.FormatUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * チャンク移動時に、そのチャンクのエリア状態（所有者・ドア/チェスト開放・爆発許可・PvP）を
 * アクションバーに表示する。土地保護の可否判定（LandProtectionListener）とは別の関心事
 * （情報表示のみ、何もキャンセルしない）のためファイルを分けている。
 *
 * 同じエリア内を歩き回っている間は再表示しない — プレイヤーごとに直近表示したエリアの
 * 状態（AreaSignature）を覚えておき、実際に内容が変わった時だけアクションバー/ボスバーを更新する。
 * これが無いと、1つの広いエリア内でチャンク境界を跨ぐたびに（全く同じ内容を）再送してしまい、
 * ちらつきの原因になる。
 *
 * PvP有効エリアに入っている間はボスバーで警告する（BossBarManager経由）。PvP有効エリアから
 * 出た瞬間だけは、何も表示されないと気付きにくいため{@code land.status_pvp_disabled_notice}を
 * 数秒間flash表示する（それ以外の遷移では単にclearChannelするだけでよい）。
 */
public class LandAreaStatusListener implements Listener {

    private static final String AREA_STATUS_CHANNEL = "land_area_status";
    private static final String PVP_WARNING_CHANNEL = "land_pvp_warning";
    private static final long PVP_DISABLED_NOTICE_TICKS = 40L;

    /** 直近表示したエリアの状態。未claim地はUNCLAIMED定数で表す。 */
    private record AreaSignature(UUID owner, boolean pvpEnabled, boolean explosionsAllowed,
                                  boolean doorsOpen, boolean chestsOpen) {
        static final AreaSignature UNCLAIMED = new AreaSignature(null, false, false, false, false);
    }

    private final StellariaCore plugin;
    private final Map<UUID, AreaSignature> lastShown = new HashMap<>();

    public LandAreaStatusListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        updateDisplay(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastShown.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        // テレポートは頻度が低いので、チャンク越境チェックを省いて毎回updateDisplayに判断させる
        // （内部のsignature比較で、テレポート先が元と同じエリアなら結局何も再送されない）。
        updateDisplay(event.getPlayer(), to);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || sameChunk(from, to)) {
            return;
        }
        updateDisplay(event.getPlayer(), to);
    }

    private boolean sameChunk(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && (a.getBlockX() >> 4) == (b.getBlockX() >> 4)
                && (a.getBlockZ() >> 4) == (b.getBlockZ() >> 4);
    }

    private void updateDisplay(Player player, Location location) {
        LandManager land = plugin.getLandManager();
        UUID owner = land.ownerOf(location);
        AreaSignature signature = owner == null
                ? AreaSignature.UNCLAIMED
                : new AreaSignature(owner, land.isPvpAllowed(location), land.explosionsAllowed(location),
                        land.doorsOpenToOthers(location), land.chestsOpenToOthers(location));

        AreaSignature previous = lastShown.put(player.getUniqueId(), signature);
        if (signature.equals(previous)) {
            return;
        }
        boolean leftPvpArea = previous != null && previous.pvpEnabled() && !signature.pvpEnabled();

        if (owner != null && !owner.equals(player.getUniqueId())) {
            plugin.getAdvancementManager().increment(player, "land.visited_other", 1);
        }
        if (owner == null) {
            plugin.getActionBarManager().clearChannel(player, AREA_STATUS_CHANNEL);
            notifyPvpAreaLeft(player, leftPvpArea);
            return;
        }

        String ownerLine = FormatUtil.replace(
                plugin.getConfigManager().getMessage("land.status_owner", player), "%owner%", ownerName(owner));
        StringBuilder text = new StringBuilder(ownerLine);
        if (signature.doorsOpen()) {
            text.append(plugin.getConfigManager().getMessage("land.status_doors_open", player));
        }
        if (signature.chestsOpen()) {
            text.append(plugin.getConfigManager().getMessage("land.status_chests_open", player));
        }
        if (signature.explosionsAllowed()) {
            text.append(plugin.getConfigManager().getMessage("land.status_explosions_allowed", player));
        }
        plugin.getActionBarManager().setChannel(player, AREA_STATUS_CHANNEL, ColorUtil.component(text.toString()));

        if (signature.pvpEnabled()) {
            plugin.getBossBarManager().setChannel(player, PVP_WARNING_CHANNEL,
                    ColorUtil.component(plugin.getConfigManager().getMessage("land.status_pvp_warning", player)),
                    BossBar.Color.RED, BossBar.Overlay.PROGRESS, 1.0f);
        } else {
            notifyPvpAreaLeft(player, leftPvpArea);
        }
    }

    /** PvP有効エリアから出た瞬間だけ、ボスバーで一時的に通知する（それ以外は単にクリアするだけでよい）。 */
    private void notifyPvpAreaLeft(Player player, boolean leftPvpArea) {
        plugin.getBossBarManager().clearChannel(player, PVP_WARNING_CHANNEL);
        if (leftPvpArea) {
            plugin.getBossBarManager().flash(player, PVP_WARNING_CHANNEL,
                    ColorUtil.component(plugin.getConfigManager().getMessage("land.status_pvp_disabled_notice", player)),
                    BossBar.Color.GREEN, BossBar.Overlay.PROGRESS, 1.0f, PVP_DISABLED_NOTICE_TICKS);
        }
    }

    private String ownerName(UUID uuid) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        return name != null ? name : uuid.toString();
    }
}
