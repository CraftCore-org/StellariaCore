package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RankingFormatTest {

    @Test
    void formatsCountsWithThousandsSeparator() {
        assertEquals("0", RankingFormat.value("deaths", 0));
        assertEquals("12,345", RankingFormat.value("mobkills", 12_345));
    }

    @Test
    void formatsDistanceInMetersBelowOneKilometer() {
        assertEquals("0 m", RankingFormat.value("distance", 0));
        assertEquals("850 m", RankingFormat.value("distance", 85_099));
        assertEquals("999 m", RankingFormat.value("distance", 99_999));
    }

    @Test
    void formatsDistanceInKilometersFromOneKilometer() {
        assertEquals("1.0 km", RankingFormat.value("distance", 100_000));
        assertEquals("12.3 km", RankingFormat.value("distance", 1_234_567));
        assertEquals("1,234.6 km", RankingFormat.value("distance", 123_456_789));
    }

    @Test
    void rankCountsOnlyPlayersStrictlyAbove() {
        assertEquals(1, RankingFormat.rank(0));
        assertEquals(4, RankingFormat.rank(3));
    }

    @Test
    void selfAlreadyCountedIsNotAddedAgain() {
        assertEquals(10, RankingFormat.totalWithSelf(10, true));
    }

    @Test
    void hiddenOrNotYetSnapshottedSelfIsAddedToTotal() {
        assertEquals(11, RankingFormat.totalWithSelf(10, false));
        assertEquals(1, RankingFormat.totalWithSelf(0, false));
    }
}
