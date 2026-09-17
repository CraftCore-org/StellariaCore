package org.craftcore.stellaria.utils;

import org.craftcore.stellaria.managers.HeadshopManager.PoolHead;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/** ヘッドショップのローテーション候補を選ぶ、副作用を持たないヘルパー。 */
public final class HeadshopRotationUtil {

    private HeadshopRotationUtil() {
    }

    /**
     * 前回分と現在分の重複をできる限り避けて、最大{@code limit}件をランダムに選ぶ。
     * 候補不足時は現在分だけの除外、最後に全プールへと段階的に緩和する。
     */
    public static List<PoolHead> select(List<PoolHead> pool, Set<Integer> previousIds, Set<Integer> currentIds,
                                        int limit, Random random) {
        if (limit <= 0 || pool.isEmpty()) {
            return List.of();
        }

        List<PoolHead> candidates = firstSufficient(pool, limit,
                head -> !previousIds.contains(head.id()) && !currentIds.contains(head.id()),
                head -> !currentIds.contains(head.id()),
                head -> true);
        Collections.shuffle(candidates, random);
        return List.copyOf(candidates.subList(0, Math.min(limit, candidates.size())));
    }

    @SafeVarargs
    private static List<PoolHead> firstSufficient(List<PoolHead> pool, int limit, Predicate<PoolHead>... filters) {
        List<PoolHead> fallback = List.of();
        for (Predicate<PoolHead> filter : filters) {
            List<PoolHead> candidates = new ArrayList<>(pool.stream().filter(filter).toList());
            if (candidates.size() >= limit) {
                return candidates;
            }
            fallback = candidates;
        }
        return new ArrayList<>(fallback);
    }
}
