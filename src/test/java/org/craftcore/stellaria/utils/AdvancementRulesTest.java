package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementRulesTest {

    private static AdvancementDefinitions.Definition def(String id, AdvancementDefinitions.TriggerType type,
                                                         String key, long goal, List<String> ids, boolean hidden) {
        return new AdvancementDefinitions.Definition(id, "tab", null, "CLOCK", id, id,
                AdvancementDefinitions.Difficulty.EASY, hidden, null,
                new AdvancementDefinitions.Trigger(type, key, goal, ids));
    }

    private static final AdvancementDefinitions.Definition COUNTER =
            def("logs", AdvancementDefinitions.TriggerType.COUNTER, "kikori.logs", 100, List.of(), false);
    private static final AdvancementDefinitions.Definition EVENT =
            def("reply", AdvancementDefinitions.TriggerType.EVENT, "msg.reply", 1, List.of(), false);
    private static final AdvancementDefinitions.Definition DISTINCT =
            def("friends", AdvancementDefinitions.TriggerType.DISTINCT, "pay.recipients", 5, List.of(), false);
    private static final AdvancementDefinitions.Definition STAT =
            def("hour", AdvancementDefinitions.TriggerType.STAT, "playtime", 3600, List.of(), false);
    private static final AdvancementDefinitions.Definition ALL_OF =
            def("both", AdvancementDefinitions.TriggerType.ALL_OF, "", 2, List.of("logs", "reply"), false);
    private static final AdvancementDefinitions.Definition COMPLETED_TWO =
            def("two", AdvancementDefinitions.TriggerType.COMPLETED, "", 2, List.of(), false);
    private static final AdvancementDefinitions.Definition COMPLETED_ONE =
            def("one", AdvancementDefinitions.TriggerType.COMPLETED, "", 1, List.of(), true);
    private static final List<AdvancementDefinitions.Definition> ALL =
            List.of(COUNTER, EVENT, DISTINCT, STAT, ALL_OF, COMPLETED_TWO, COMPLETED_ONE);

    private static AdvancementRules.State state(Map<String, Long> counters, Map<String, Long> distinct, Set<String> completed) {
        return new AdvancementRules.State(counters, distinct, completed);
    }

    private static final AdvancementRules.State EMPTY = state(Map.of(), Map.of(), Set.of());

    @Test
    void counterMeetsGoalAtThreshold() {
        assertFalse(AdvancementRules.isMet(COUNTER, state(Map.of("kikori.logs", 99L), Map.of(), Set.of()), k -> 0, ALL));
        assertTrue(AdvancementRules.isMet(COUNTER, state(Map.of("kikori.logs", 100L), Map.of(), Set.of()), k -> 0, ALL));
        assertEquals(99L, AdvancementRules.progress(COUNTER, state(Map.of("kikori.logs", 99L), Map.of(), Set.of()), k -> 0, ALL));
    }

    @Test
    void eventNeedsOneOccurrence() {
        assertEquals(1L, AdvancementRules.goal(EVENT));
        assertFalse(AdvancementRules.isMet(EVENT, EMPTY, k -> 0, ALL));
        assertTrue(AdvancementRules.isMet(EVENT, state(Map.of("msg.reply", 3L), Map.of(), Set.of()), k -> 0, ALL));
        assertEquals(1L, AdvancementRules.progress(EVENT, state(Map.of("msg.reply", 3L), Map.of(), Set.of()), k -> 0, ALL));
    }

    @Test
    void distinctUsesDistinctCounts() {
        assertTrue(AdvancementRules.isMet(DISTINCT, state(Map.of(), Map.of("pay.recipients", 5L), Set.of()), k -> 0, ALL));
        assertFalse(AdvancementRules.isMet(DISTINCT, state(Map.of("pay.recipients", 9L), Map.of(), Set.of()), k -> 0, ALL));
    }

    @Test
    void statReadsStatFunction() {
        assertTrue(AdvancementRules.isMet(STAT, EMPTY, k -> k.equals("playtime") ? 3600 : 0, ALL));
        assertFalse(AdvancementRules.isMet(STAT, EMPTY, k -> 3599, ALL));
    }

    @Test
    void allOfCountsCompletedIds() {
        assertEquals(1L, AdvancementRules.progress(ALL_OF, state(Map.of(), Map.of(), Set.of("logs")), k -> 0, ALL));
        assertTrue(AdvancementRules.isMet(ALL_OF, state(Map.of(), Map.of(), Set.of("logs", "reply")), k -> 0, ALL));
    }

    @Test
    void completedIgnoresItselfAndOtherCompletedTypes() {
        AdvancementRules.State s = state(Map.of(), Map.of(), Set.of("logs", "one", "two"));
        assertEquals(1L, AdvancementRules.progress(COMPLETED_TWO, s, k -> 0, ALL));
        assertFalse(AdvancementRules.isMet(COMPLETED_TWO, s, k -> 0, ALL));
        assertTrue(AdvancementRules.isMet(COMPLETED_TWO, state(Map.of(), Map.of(), Set.of("logs", "reply")), k -> 0, ALL));
    }

    @Test
    void indexesByKeyAndListsDependents() {
        Map<String, List<AdvancementDefinitions.Definition>> index = AdvancementRules.indexByKey(ALL);
        assertEquals(List.of(COUNTER), index.get("kikori.logs"));
        assertEquals(List.of(STAT), index.get("playtime"));
        assertEquals(List.of(ALL_OF, COMPLETED_TWO, COMPLETED_ONE), AdvancementRules.dependents(ALL));
        assertEquals(List.of(STAT), AdvancementRules.ofType(ALL, AdvancementDefinitions.TriggerType.STAT));
    }

    @Test
    void hiddenIsConcealedUntilCompleted() {
        assertTrue(AdvancementRules.isConcealed(COMPLETED_ONE, false));
        assertFalse(AdvancementRules.isConcealed(COMPLETED_ONE, true));
        assertFalse(AdvancementRules.isConcealed(COUNTER, false));
    }
}
