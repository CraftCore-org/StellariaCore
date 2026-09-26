package org.craftcore.stellaria.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void onlyUnregisteredPathsAreLoadedInGivenOrder() {
        assertEquals(List.of("m/root", "m/b"),
                AdvancementJson.missing(List.of("m/root", "m/a", "m/b"), Set.of("m/a")));
        assertEquals(List.of(), AdvancementJson.missing(List.of("m/root"), Set.of("m/root")));
    }
}
