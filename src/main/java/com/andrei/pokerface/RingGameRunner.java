package com.andrei.pokerface;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Drives a fixed-stack "ring game" style batch of hands: unlike the
 * tournament layer, every player's stack is reset to a fixed buy-in before
 * each hand, and nobody is ever eliminated. This isolates the chip result of
 * each hand's decisions from tournament survival/ICM effects, which is what
 * makes bb/100 a usable, low-variance metric for comparing bots -- tournament
 * win-rate needs thousands of tournaments to separate skill from variance;
 * bb/100 over a batch of independent, fixed-stack hands converges far faster,
 * because each hand is an independent sample of decision quality rather than
 * a multi-hour survival outcome.
 *
 * Dealer button rotation carries across hands exactly as it would in a real
 * session (GameState.startNewHand() advances it internally) -- only the
 * stacks are force-reset. Blinds are constant for the whole batch: a bb/100
 * comparison requires a fixed blind size to normalize against, so there is no
 * blind schedule parameter here.
 *
 * Decision latency is always measured and returned, but by default no
 * deadline is enforced (TurnPolicy.UNTIMED). That split is deliberate: a
 * benchmark makes roughly a million decisions, so a move timer firing on a GC
 * pause would inject a forced fold into the chip samples and make the run
 * non-reproducible. Latency is a statistic here, not a rule.
 */
public final class RingGameRunner {

    private RingGameRunner() {}

    /**
     * Plays handsToPlay hands on a single GameState, resetting every player's
     * stack to buyIn before each hand.
     *
     * @param players     seated players; stack values are overwritten before every
     *                    hand, so their starting stacks don't matter, but list
     *                    order (seat index) does -- agents.get(i) must control
     *                    players.get(i)
     * @param agents      one agent per seat, same contract as HandRunner.playHand
     * @param smallBlind  constant small blind for every hand in the batch
     * @param bigBlind    constant big blind for every hand in the batch
     * @param buyIn       stack every player is reset to before each hand
     * @param handsToPlay number of hands to play
     * @param seedSource  supplies a fresh RNG seed per hand
     * @param policy      per-decision timing; UNTIMED measures without enforcing
     * @param logger      event subscriber for the whole batch (HandLogger.NO_OP if unused)
     */
    public static RingGameResult runBatch(
            List<Player> players,
            List<PokerAgent> agents,
            int smallBlind,
            int bigBlind,
            int buyIn,
            int handsToPlay,
            IntSupplier seedSource,
            TurnPolicy policy,
            HandLogger logger) {

        if (players == null || agents == null || players.size() != agents.size()) {
            throw new IllegalArgumentException(
                    "Need exactly one agent per player (" + (players == null ? 0 : players.size()) + " players)");
        }
        if (buyIn <= 0) {
            throw new IllegalArgumentException("Buy-in must be positive");
        }
        if (handsToPlay <= 0) {
            throw new IllegalArgumentException("handsToPlay must be positive");
        }

        GameState state = new GameState(players, smallBlind, bigBlind);

        // Tees DecisionTimed events off into per-seat aggregates on the way to
        // the caller's logger, so latency comes back in the result rather than
        // obliging every caller to attach a collector of their own.
        LatencyCollector latency = new LatencyCollector(players.size(), logger);
        state.setLogger(latency);

        List<int[]> netChipsPerHand = new ArrayList<>(handsToPlay);

        for (int h = 0; h < handsToPlay; h++) {
            for (Player p : players) {
                p.setStack(buyIn);
            }

            HandRunner.playHand(state, agents, seedSource.getAsInt(), policy);

            int[] net = new int[players.size()];
            for (int i = 0; i < players.size(); i++) {
                net[i] = players.get(i).getStack() - buyIn;
            }
            netChipsPerHand.add(net);
        }

        return new RingGameResult(handsToPlay, netChipsPerHand, bigBlind, latency.snapshot());
    }

    /** Convenience overload: untimed, no logging. */
    public static RingGameResult runBatch(
            List<Player> players, List<PokerAgent> agents,
            int smallBlind, int bigBlind, int buyIn,
            int handsToPlay, IntSupplier seedSource) {
        return runBatch(players, agents, smallBlind, bigBlind, buyIn, handsToPlay,
                seedSource, TurnPolicy.UNTIMED, HandLogger.NO_OP);
    }

    /** Convenience overload: untimed, with logging. */
    public static RingGameResult runBatch(
            List<Player> players, List<PokerAgent> agents,
            int smallBlind, int bigBlind, int buyIn,
            int handsToPlay, IntSupplier seedSource, HandLogger logger) {
        return runBatch(players, agents, smallBlind, bigBlind, buyIn, handsToPlay,
                seedSource, TurnPolicy.UNTIMED, logger);
    }

    /**
     * Accumulates per-seat decision times from the event stream, then forwards
     * every event to the caller's logger unchanged. Running aggregates only --
     * see RingGameResult for why percentiles aren't retained.
     */
    private static final class LatencyCollector implements HandLogger {
        private final long[] decisions;
        private final long[] totalNanos;
        private final long[] maxNanos;
        private final HandLogger delegate;

        LatencyCollector(int seats, HandLogger delegate) {
            this.decisions = new long[seats];
            this.totalNanos = new long[seats];
            this.maxNanos = new long[seats];
            this.delegate = (delegate == null) ? HandLogger.NO_OP : delegate;
        }

        @Override
        public void log(GameEvent event) {
            if (event instanceof GameEvent.DecisionTimed d) {
                int seat = d.seatIndex();
                decisions[seat]++;
                totalNanos[seat] += d.elapsedNanos();
                if (d.elapsedNanos() > maxNanos[seat]) {
                    maxNanos[seat] = d.elapsedNanos();
                }
            }
            delegate.log(event);
        }

        List<RingGameResult.LatencyStats> snapshot() {
            List<RingGameResult.LatencyStats> stats = new ArrayList<>(decisions.length);
            for (int seat = 0; seat < decisions.length; seat++) {
                stats.add(new RingGameResult.LatencyStats(
                        decisions[seat], totalNanos[seat], maxNanos[seat]));
            }
            return stats;
        }
    }
}