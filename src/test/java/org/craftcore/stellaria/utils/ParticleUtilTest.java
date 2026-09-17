package org.craftcore.stellaria.utils;

import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParticleUtilTest {

    @Test
    void resolvesInvalidParticleToDustAndWarns() {
        List<String> warnings = new ArrayList<>();

        assertEquals(Particle.DUST, ParticleUtil.resolveParticle("not_a_particle", warnings::add));
        assertEquals(1, warnings.size());
    }

    @Test
    void resolvesParticleCaseInsensitivelyWithoutWarning() {
        List<String> warnings = new ArrayList<>();

        assertEquals(Particle.FLAME, ParticleUtil.resolveParticle(" flame ", warnings::add));
        assertEquals(List.of(), warnings);
    }
}
