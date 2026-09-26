package org.craftcore.stellaria.utils;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementDefinitionsTest {

    private static final Set<String> ICONS = Set.of("NETHER_STAR", "WOODEN_AXE", "IRON_AXE", "CLOCK");

    private final List<String> warnings = new ArrayList<>();

    private AdvancementDefinitions.Parsed parse(String advancementsYaml) {
        String yaml = """
                tabs:
                  mining:
                    title: "採掘"
                    description: "掘る"
                    icon: WOODEN_AXE
                  stellaria:
                    title: "すてらりあ"
                    description: "総合"
                    icon: NETHER_STAR
                    background: "minecraft:block/amethyst_block"
                advancements:
                """ + advancementsYaml.indent(2);
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return AdvancementDefinitions.parse(config, ICONS::contains, warnings::add);
    }

    private static List<String> ids(AdvancementDefinitions.Parsed parsed) {
        return parsed.definitions().stream().map(AdvancementDefinitions.Definition::id).toList();
    }

    @Test
    void parsesTabsWithDefaultBackground() {
        AdvancementDefinitions.Parsed parsed = parse("");
        assertEquals("minecraft:block/stone", parsed.tabs().get("mining").background());
        assertEquals("minecraft:block/amethyst_block", parsed.tabs().get("stellaria").background());
    }

    @Test
    void parsesCounterDefinition() {
        AdvancementDefinitions.Parsed parsed = parse("""
                kikori_100:
                  tab: mining
                  icon: WOODEN_AXE
                  title: "見習い木こり"
                  description: "100本"
                  difficulty: normal
                  reward: 400
                  trigger: { type: counter, key: kikori.logs, goal: 100 }
                """);
        AdvancementDefinitions.Definition def = parsed.find("kikori_100").orElseThrow();
        assertEquals(AdvancementDefinitions.Difficulty.NORMAL, def.difficulty());
        assertEquals(AdvancementDefinitions.TriggerType.COUNTER, def.trigger().type());
        assertEquals("kikori.logs", def.trigger().key());
        assertEquals(100L, def.trigger().goal());
        assertEquals(400L, def.reward());
        assertFalse(def.hidden());
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    @Test
    void eventGoalIsOneAndAllOfGoalIsIdCount() {
        AdvancementDefinitions.Parsed parsed = parse("""
                a:
                  tab: stellaria
                  icon: CLOCK
                  title: "A"
                  description: "a"
                  difficulty: easy
                  trigger: { type: event, key: join.count }
                b:
                  tab: stellaria
                  icon: CLOCK
                  title: "B"
                  description: "b"
                  difficulty: easy
                  trigger: { type: stat, key: playtime, goal: 3600 }
                both:
                  tab: stellaria
                  icon: NETHER_STAR
                  title: "AB"
                  description: "ab"
                  difficulty: hard
                  trigger: { type: all_of, ids: [a, b] }
                """);
        assertEquals(1L, parsed.find("a").orElseThrow().trigger().goal());
        assertEquals(2L, parsed.find("both").orElseThrow().trigger().goal());
        assertEquals(List.of("a", "b"), parsed.find("both").orElseThrow().trigger().ids());
    }

    @Test
    void invalidEntriesAreDroppedWithWarnings() {
        AdvancementDefinitions.Parsed parsed = parse("""
                bad_tab:
                  tab: nowhere
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                bad_icon:
                  tab: mining
                  icon: NOT_A_THING
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                bad_type:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: teleport, key: k }
                bad_difficulty:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: impossible
                  trigger: { type: event, key: k }
                bad_goal:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: counter, key: k, goal: 0 }
                bad_stat:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: stat, key: nothing, goal: 5 }
                Bad-Id:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                root:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                ok:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                """);
        assertEquals(List.of("ok"), ids(parsed));
        assertEquals(8, warnings.size(), warnings::toString);
    }

    @Test
    void referencesToDroppedOrMissingEntriesCascade() {
        AdvancementDefinitions.Parsed parsed = parse("""
                broken:
                  tab: mining
                  icon: NOT_A_THING
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                child:
                  tab: mining
                  parent: broken
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                grandchild:
                  tab: mining
                  parent: child
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                needs_missing:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: all_of, ids: [grandchild] }
                other_tab_parent:
                  tab: stellaria
                  parent: fine
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                fine:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                """);
        assertEquals(List.of("fine"), ids(parsed));
    }

    @Test
    void cyclesAreDropped() {
        AdvancementDefinitions.Parsed parsed = parse("""
                a:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: all_of, ids: [b] }
                b:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: all_of, ids: [a] }
                p:
                  tab: mining
                  parent: q
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                q:
                  tab: mining
                  parent: p
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                self:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: all_of, ids: [self] }
                ok:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                """);
        assertEquals(List.of("ok"), ids(parsed));
    }

    @Test
    void parentsComeBeforeChildren() {
        AdvancementDefinitions.Parsed parsed = parse("""
                child:
                  tab: mining
                  parent: base
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                base:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                """);
        assertEquals(List.of("base", "child"), ids(parsed));
    }
}
