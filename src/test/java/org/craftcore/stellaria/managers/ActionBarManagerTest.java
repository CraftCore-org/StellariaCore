package org.craftcore.stellaria.managers;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionBarManagerTest {

    @Test
    void expirySnapshotRemovesElapsedChannelsAndKeepsInsertionOrder() {
        // A snapshot that retained the expired "flash" entry would keep stale HUD text visible.
        LinkedHashMap<String, ActionBarManager.ChannelEntry> channels = new LinkedHashMap<>();
        channels.put("persistent", new ActionBarManager.ChannelEntry(Component.text("persistent"), -1));
        channels.put("flash", new ActionBarManager.ChannelEntry(Component.text("flash"), 1_000));
        channels.put("countdown", new ActionBarManager.ChannelEntry(Component.text("countdown"), 1_001));

        List<ActionBarManager.ChannelEntry> snapshot = ActionBarManager.expireAndSnapshot(channels, 1_000);
        channels.clear();

        assertEquals(List.of(
                new ActionBarManager.ChannelEntry(Component.text("persistent"), -1),
                new ActionBarManager.ChannelEntry(Component.text("countdown"), 1_001)
        ), snapshot);
    }

    @Test
    void clearInvalidatesSnapshotCapturedBeforeConcurrentReloadCleanup() {
        // Sending this old snapshot after persistent cleanup would resurrect the disabled action bar.
        ActionBarManager.ChannelState state = new ActionBarManager.ChannelState();
        state.put("persistent", new ActionBarManager.ChannelEntry(Component.text("persistent"), -1));

        ActionBarManager.ChannelSnapshot snapshot = state.expireAndSnapshot(1_000);
        assertTrue(state.isCurrent(snapshot));

        state.remove("persistent");

        assertFalse(state.isCurrent(snapshot));
    }
}
