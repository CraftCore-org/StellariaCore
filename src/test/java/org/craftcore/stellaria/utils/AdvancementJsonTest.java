package org.craftcore.stellaria.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementJsonTest {

    private static AdvancementDefinitions.Definition def(String id, String parent,
                                                         AdvancementDefinitions.Difficulty difficulty, boolean hidden) {
        return new AdvancementDefinitions.Definition(id, "mining", parent, "IRON_AXE", "T-" + id, "D-" + id,
                difficulty, hidden, null,
                new AdvancementDefinitions.Trigger(AdvancementDefinitions.TriggerType.EVENT, "k", 1, List.of()));
    }

    private static JsonObject display(JsonObject json) {
        return json.getAsJsonObject("display");
    }

    @Test
    void advancementUsesFrameAnnounceParentAndImpossibleCriterion() {
        JsonObject json = AdvancementJson.advancement(
                def("kikori_1000", "kikori_100", AdvancementDefinitions.Difficulty.HARD, true), true, JsonPrimitive::new);
        assertEquals("stellaria:mining/kikori_100", json.get("parent").getAsString());
        assertEquals("goal", display(json).get("frame").getAsString());
        assertTrue(display(json).get("announce_to_chat").getAsBoolean());
        assertTrue(display(json).get("show_toast").getAsBoolean());
        assertTrue(display(json).get("hidden").getAsBoolean());
        assertEquals("minecraft:iron_axe", display(json).getAsJsonObject("icon").get("id").getAsString());
        assertEquals("T-kikori_1000", display(json).get("title").getAsString());
        assertEquals("minecraft:impossible",
                json.getAsJsonObject("criteria").getAsJsonObject("done").get("trigger").getAsString());
        assertEquals("[[\"done\"]]", json.get("requirements").toString());
        assertFalse(display(json).has("background"));
    }

    @Test
    void topLevelAdvancementHangsFromTabRoot() {
        JsonObject json = AdvancementJson.advancement(
                def("first", null, AdvancementDefinitions.Difficulty.EASY, false), false, JsonPrimitive::new);
        assertEquals("stellaria:mining/root", json.get("parent").getAsString());
        assertEquals("task", display(json).get("frame").getAsString());
        assertFalse(display(json).get("announce_to_chat").getAsBoolean());
    }

    @Test
    void rootHasBackgroundAndNoToastOrParent() {
        JsonObject json = AdvancementJson.root(new AdvancementDefinitions.Tab("mining", "採掘", "掘る", "DIAMOND_PICKAXE",
                "minecraft:block/stone"), JsonPrimitive::new);
        assertFalse(json.has("parent"));
        assertEquals("minecraft:block/stone", display(json).get("background").getAsString());
        assertFalse(display(json).get("show_toast").getAsBoolean());
        assertFalse(display(json).get("announce_to_chat").getAsBoolean());
    }

    @Test
    void hashChangesWithContent() {
        JsonObject a = AdvancementJson.advancement(def("x", null, AdvancementDefinitions.Difficulty.EASY, false), false, JsonPrimitive::new);
        JsonObject b = AdvancementJson.advancement(def("x", null, AdvancementDefinitions.Difficulty.HARD, false), false, JsonPrimitive::new);
        assertEquals(AdvancementJson.hash(a), AdvancementJson.hash(a.deepCopy()));
        assertNotEquals(AdvancementJson.hash(a), AdvancementJson.hash(b));
        assertEquals(64, AdvancementJson.hash(a).length());
    }

    private static LinkedHashMap<String, String> desired(String... pathsAndHashes) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pathsAndHashes.length; i += 2) {
            map.put(pathsAndHashes[i], pathsAndHashes[i + 1]);
        }
        return map;
    }

    private static final Map<String, String> PARENTS = Map.of("m/a", "m/root", "m/b", "m/a", "m/c", "m/root");

    @Test
    void unchangedRegisteredAdvancementsAreLeftAlone() {
        LinkedHashMap<String, String> want = desired("m/root", "r", "m/a", "1", "m/b", "2", "m/c", "3");
        AdvancementJson.Plan plan = AdvancementJson.plan(want, PARENTS, Map.copyOf(want), Set.copyOf(want.keySet()));
        assertEquals(List.of(), plan.remove());
        assertEquals(List.of(), plan.load());
    }

    @Test
    void changedAdvancementIsReloadedWithDescendants() {
        LinkedHashMap<String, String> want = desired("m/root", "r", "m/a", "1-new", "m/b", "2", "m/c", "3");
        Map<String, String> stored = Map.of("m/root", "r", "m/a", "1", "m/b", "2", "m/c", "3");
        AdvancementJson.Plan plan = AdvancementJson.plan(want, PARENTS, stored, Set.copyOf(want.keySet()));
        assertEquals(List.of("m/b", "m/a"), plan.remove());
        assertEquals(List.of("m/a", "m/b"), plan.load());
    }

    @Test
    void missingAdvancementIsLoadedAndStaleOnesRemoved() {
        LinkedHashMap<String, String> want = desired("m/root", "r", "m/a", "1", "m/b", "2", "m/c", "3");
        Map<String, String> stored = Map.of("m/root", "r", "m/a", "1", "m/b", "2", "m/c", "3", "m/old", "9");
        AdvancementJson.Plan plan = AdvancementJson.plan(want, PARENTS, stored, Set.of("m/root", "m/a", "m/b", "m/old"));
        assertEquals(List.of("m/old"), plan.remove());
        assertEquals(List.of("m/c"), plan.load());
    }
}
