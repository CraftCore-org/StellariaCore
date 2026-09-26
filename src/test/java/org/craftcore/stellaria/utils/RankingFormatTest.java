package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void tiedValuesShareTheSameRank() {
        assertArrayEquals(new long[]{1, 2, 2, 4}, RankingFormat.competitionRanks(new long[]{9, 5, 5, 3}, 0, 1));
    }

    @Test
    void tiesContinueAcrossPages() {
        // 2ページ目の先頭が前ページ末尾と同じ値なら、先頭の順位は「自分より大きい人数+1」で決まる
        assertArrayEquals(new long[]{9, 9, 13}, RankingFormat.competitionRanks(new long[]{5, 5, 4}, 10, 9));
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
