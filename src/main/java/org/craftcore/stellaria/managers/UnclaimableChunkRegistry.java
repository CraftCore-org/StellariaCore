package org.craftcore.stellaria.managers;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 保護不可に指定されたチャンクのインメモリ状態を保持する。 */
final class UnclaimableChunkRegistry {

    private final Set<LandManager.ChunkKey> marked = ConcurrentHashMap.newKeySet();

    boolean mark(LandManager.ChunkKey key) {
        return marked.add(key);
    }

    boolean unmark(LandManager.ChunkKey key) {
        return marked.remove(key);
    }

    boolean isMarked(LandManager.ChunkKey key) {
        return marked.contains(key);
    }
}
