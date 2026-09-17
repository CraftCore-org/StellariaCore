package org.craftcore.stellaria.utils;

import org.bukkit.Particle;
import org.bukkit.Color;
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

    @Test
    void fallsBackForMalformedConfiguredColor() {
        List<String> warnings = new ArrayList<>();

        assertEquals(Color.fromRGB(0x55FF55),
                ParticleUtil.parseColorOrFallback("#not-a-color", Color.fromRGB(0x55FF55), warnings::add));
        assertEquals(1, warnings.size());
    }

    @Test
    void parsesSixDigitHexColor() {
        List<String> warnings = new ArrayList<>();

        assertEquals(Color.fromRGB(0x123ABC),
                ParticleUtil.parseColorOrFallback("#123ABC", Color.WHITE, warnings::add));
        assertEquals(List.of(), warnings);
    }
}
