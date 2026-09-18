package org.craftcore.stellaria.utils;

import org.craftcore.stellaria.managers.HeadshopManager.PoolHead;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadshopRotationUtilTest {

    @Test
    void avoidsPreviousAndCurrentHeadsWhenEnoughAlternativesExist() {
        List<PoolHead> pool = java.util.stream.IntStream.rangeClosed(1, 15)
                .mapToObj(id -> new PoolHead(
                        id,
                        "head-" + id,
                        "texture-" + id,
                        "item-data-" + id
                ))
                .toList();

        List<PoolHead> selected = HeadshopRotationUtil.select(
                pool, Set.of(1, 2, 3, 4, 5), Set.of(6, 7, 8, 9, 10), 5, new Random(0));

        assertEquals(5, selected.size());
        assertTrue(selected.stream().allMatch(head -> head.id() >= 11));
    }

    @Test
    void fallsBackToPoolWhenThereAreTooFewAlternatives() {
        List<PoolHead> pool = java.util.stream.IntStream.rangeClosed(1, 3)
                .mapToObj(id -> new PoolHead(
                        id,
                        "head-" + id,
                        "texture-" + id,
                        "item-data-" + id
                ))
                .toList();

        assertEquals(3, HeadshopRotationUtil.select(pool, Set.of(), Set.of(1, 2, 3), 5, new Random(0)).size());
    }
}
