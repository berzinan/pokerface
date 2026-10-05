package com.andrei.pokerface;

import java.util.List;

/**
 * Outcome of a RingGameRunner.runBatch() call: the net chip result of every
 * seat for every hand played, plus how long each seat's agent took to decide.
 *
 * Chip results are kept as raw per-hand samples (rather than only a running
 * total) so callers can compute not just a mean bb/100 but also its standard
 * error -- the whole point of this runner is to produce a metric whose
 * uncertainty can be quantified, not just a single number that looks precise
 * but isn't.
 *
 * Latency is kept as running aggregates rather than raw samples. A 200k-hand
 * batch makes roughly a million decisions, so retaining every sample to
 * compute percentiles would cost megabytes per seat for a statistic that
 * mean-and-max already answers well enough ("is this bot fast enough for a
 * 30-second move timer?").
 */
public record RingGameResult(
        int handsPlayed,
        List<int[]> netChipsPerHand,
        int bigBlind,
        List<LatencyStats> latencyPerSeat) {

    /**
     * Decision-time aggregates for one seat, accumulated from
     * GameEvent.DecisionTimed.
     */
    public record LatencyStats(long decisions, long totalNanos, long maxNanos) {

        public static final LatencyStats EMPTY = new LatencyStats(0, 0, 0);

        /** Mean decision time in milliseconds; 0 when no decisions were recorded. */
        public double meanMillis() {
            return decisions == 0 ? 0.0 : (totalNanos / (double) decisions) / 1_000_000.0;
        }

        /** Slowest single decision in milliseconds -- the figure a move timer has to clear. */
        public double maxMillis() {
            return maxNanos / 1_000_000.0;
        }

        /** Combines two seats' aggregates, for pooling one bot across the seats it occupied. */
        public LatencyStats plus(LatencyStats other) {
            return new LatencyStats(
                    decisions + other.decisions,
                    totalNanos + other.totalNanos,
                    Math.max(maxNanos, other.maxNanos));
        }
    }

    public RingGameResult {
        netChipsPerHand = List.copyOf(netChipsPerHand);
        latencyPerSeat = List.copyOf(latencyPerSeat);
    }

    /** For callers constructing a result without latency data (tests, hand-built fixtures). */
    public RingGameResult(int handsPlayed, List<int[]> netChipsPerHand, int bigBlind) {
        this(handsPlayed, netChipsPerHand, bigBlind, List.of());
    }

    /** Number of seats tracked, derived from the first recorded hand. Zero if no hands were recorded. */
    public int seatCount() {
        return netChipsPerHand.isEmpty() ? 0 : netChipsPerHand.get(0).length;
    }

    /** Latency for a seat, or EMPTY if this result carries no latency data. */
    public LatencyStats latency(int seatIndex) {
        return seatIndex < latencyPerSeat.size() ? latencyPerSeat.get(seatIndex) : LatencyStats.EMPTY;
    }

    /** Mean net chips won per hand for the given seat, expressed in big blinds. */
    public double meanBbPerHand(int seatIndex) {
        double totalChips = 0;
        for (int[] hand : netChipsPerHand) {
            totalChips += hand[seatIndex];
        }
        return (totalChips / handsPlayed) / bigBlind;
    }

    /** Standard bot-performance metric: big blinds won per 100 hands. */
    public double bbPer100(int seatIndex) {
        return meanBbPerHand(seatIndex) * 100;
    }

    /**
     * Standard error of bbPer100(seatIndex), from the per-hand sample variance.
     * Report this alongside bbPer100 -- a bbPer100 figure without an error bar
     * is not a comparison between bots, just a number.
     */
    public double bbPer100StdError(int seatIndex) {
        double meanPerHandBb = meanBbPerHand(seatIndex);
        double sumSquaredDiff = 0;
        for (int[] hand : netChipsPerHand) {
            double bb = hand[seatIndex] / (double) bigBlind;
            double diff = bb - meanPerHandBb;
            sumSquaredDiff += diff * diff;
        }
        double variance = (handsPlayed > 1) ? sumSquaredDiff / (handsPlayed - 1) : 0.0;
        double stdErrorPerHand = Math.sqrt(variance / handsPlayed);
        return stdErrorPerHand * 100;
    }
}