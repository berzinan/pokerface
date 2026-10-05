package com.andrei.pokerface;

import java.util.List;

/**
 * One bot's aggregated ring-game performance, pooled across every seat it
 * occupied.
 *
 * Carries two unrelated kinds of measurement. bb/100 and its standard error
 * answer "how well does this bot play?"; the latency figures answer "is it
 * fast enough to play under a move timer?". The second question matters
 * because a bot benchmarked untimed can still blow a 30-second budget in
 * tournament play, where its answer would be thrown away and replaced with a
 * fold.
 */
public record BotStatLine(
        String name,
        int handsPlayed,
        double bbPer100,
        double bbPer100StdError,
        long decisions,
        double meanDecisionMillis,
        double maxDecisionMillis) {

    /**
     * Builds a stat line from raw per-hand bb samples (already in big blinds,
     * one value per hand played) plus pooled decision-time aggregates.
     */
    public static BotStatLine fromSamples(
            String name, List<Double> bbSamples, RingGameResult.LatencyStats latency) {

        int hands = bbSamples.size();
        double mean = bbSamples.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        double sumSquaredDiff = 0;
        for (double v : bbSamples) {
            double diff = v - mean;
            sumSquaredDiff += diff * diff;
        }
        double variance = (hands > 1) ? sumSquaredDiff / (hands - 1) : 0.0;
        double stdErrorPerHand = Math.sqrt(variance / Math.max(hands, 1));

        return new BotStatLine(
                name,
                hands,
                mean * 100,
                stdErrorPerHand * 100,
                latency.decisions(),
                latency.meanMillis(),
                latency.maxMillis());
    }

    /** For callers with no latency data to attach. */
    public static BotStatLine fromSamples(String name, List<Double> bbSamples) {
        return fromSamples(name, bbSamples, RingGameResult.LatencyStats.EMPTY);
    }
}