package org.craftcore.stellaria.enchants;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 釣り人の粘りの連続回数と 1 日の支払い額を管理する。メモリのみで、再ログインでは消さない（再起動でリセット）。
 * 連続回数は最初の 1 匹が 0 で、windowMillis 以内に次を釣るたびに 1 増える。
 */
public final class AnglerStreakTracker {

    public record Settings(long windowMillis, long baseAmount, int maxStreak, double levelMultiplier, long dailyCap) {
    }

    public record Result(int streak, long payout, boolean capJustReached) {
    }

    private record State(long lastCatchMillis, int streak, LocalDate day, long paidToday) {
    }

    private final Map<UUID, State> states = new HashMap<>();

    public Result recordCatch(UUID id, long nowMillis, LocalDate today, Settings settings) {
        State previous = states.get(id);
        long paidBefore = previous != null && previous.day().equals(today) ? previous.paidToday() : 0;
        boolean continues = previous != null
                && previous.lastCatchMillis() >= 0
                && nowMillis - previous.lastCatchMillis() <= settings.windowMillis();
        int streak = continues ? previous.streak() + 1 : 0;

        long raw = Math.round(settings.baseAmount() * Math.min(streak, settings.maxStreak()) * settings.levelMultiplier());
        long payout = Math.max(0, Math.min(raw, settings.dailyCap() - paidBefore));
        long paidAfter = paidBefore + payout;
        boolean capJustReached = paidBefore < settings.dailyCap() && paidAfter >= settings.dailyCap();

        states.put(id, new State(nowMillis, streak, today, paidAfter));
        return new Result(streak, payout, capJustReached);
    }

    /** 連続回数だけを 0 に戻す（その日の支払い額は残す）。 */
    public void resetStreak(UUID id) {
        states.computeIfPresent(id, (key, state) -> new State(-1, 0, state.day(), state.paidToday()));
    }
}
