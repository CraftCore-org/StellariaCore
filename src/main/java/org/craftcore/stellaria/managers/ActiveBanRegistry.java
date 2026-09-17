package org.craftcore.stellaria.managers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ActiveBanRegistry {
    private final Map<UUID, BanEntry> entries = new ConcurrentHashMap<>();

    public record BanEntry(int id, UUID targetUuid, UUID moderatorUuid, String reason,
                           long bannedAt, Long expiresAt) {
        public boolean isExpired(long now) {
            return expiresAt != null && now >= expiresAt;
        }
    }

    public void put(BanEntry entry) {
        entries.put(entry.targetUuid(), entry);
    }

    public BanEntry getActive(UUID targetUuid, long now) {
        BanEntry entry = entries.get(targetUuid);
        if (entry != null && entry.isExpired(now)) {
            entries.remove(targetUuid, entry);
            return null;
        }
        return entry;
    }

    public void remove(UUID targetUuid) {
        entries.remove(targetUuid);
    }

    public void clear() {
        entries.clear();
    }
}
