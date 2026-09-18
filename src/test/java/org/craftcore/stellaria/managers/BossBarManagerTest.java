package org.craftcore.stellaria.managers;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BossBarManagerTest {

    @Test
    void expirySnapshotSeparatesElapsedBarsFromVisibleBars() {
        // Returning expired bars with visible bars would leave an expired warning on screen.
        BossBar persistent = BossBar.bossBar(Component.text("persistent"), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        BossBar expired = BossBar.bossBar(Component.text("expired"), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        LinkedHashMap<String, BossBarManager.ChannelEntry> channels = new LinkedHashMap<>();
        channels.put("persistent", new BossBarManager.ChannelEntry(persistent, -1));
        channels.put("warning", new BossBarManager.ChannelEntry(expired, 1_000));

        BossBarManager.ExpirySnapshot snapshot = BossBarManager.expireAndSnapshot(channels, 1_000);
        channels.clear();

        assertEquals(List.of(persistent), snapshot.visibleBars());
        assertEquals(List.of(expired), snapshot.expiredBars());
    }

    @Test
    void queuedUpdateDoesNotMutateBossBarUntilDispatchedOutsideChannelStateMutation() {
        // Updating here rather than after the state mutation would run a Bukkit-visible bar update while locked.
        BossBar bar = BossBar.bossBar(Component.text("old"), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        BossBarManager.BossBarUpdate update = new BossBarManager.BossBarUpdate(
                bar, Component.text("new"), BossBar.Color.RED, BossBar.Overlay.NOTCHED_10, 0.5f, false);

        assertEquals(Component.text("old"), bar.name());

        BossBarManager.applyUpdate(update);

        assertEquals(Component.text("new"), bar.name());
        assertEquals(BossBar.Color.RED, bar.color());
        assertEquals(BossBar.Overlay.NOTCHED_10, bar.overlay());
        assertEquals(0.5f, bar.progress());
    }
}
