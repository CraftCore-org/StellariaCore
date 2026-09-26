package org.craftcore.stellaria.utils;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 高速鉄道の 1 回の乗車が「始発から終着まで」かどうかの判定。 */
public final class RailRideRecord {

    private RailRideRecord() {
    }

    public static boolean isFullLine(List<String> stationsInOrder, boolean oneWay, @Nullable String departure, String arrival) {
        if (departure == null || stationsInOrder.size() < 2) {
            return false;
        }
        String first = stationsInOrder.getFirst();
        String last = stationsInOrder.getLast();
        if (departure.equalsIgnoreCase(first) && arrival.equalsIgnoreCase(last)) {
            return true;
        }
        return !oneWay && departure.equalsIgnoreCase(last) && arrival.equalsIgnoreCase(first);
    }
}
