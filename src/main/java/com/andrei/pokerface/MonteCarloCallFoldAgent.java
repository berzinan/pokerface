package com.andrei.pokerface;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * Reference bot: estimates its equity by simulation and calls whenever that
 * equity beats the pot odds it is being offered. Never raises, never bluffs --
 * it is a yardstick for how far pure pot-odds discipline gets you, not an
 * attempt at a strong player.
 *
 * Checking is free, so it always checks rather than estimating anything when
 * nothing is owed.
 *
 * Time budget. When the view carries one (tournament play), the agent runs
 * rollouts until the budget is nearly spent rather than a fixed count, so a
 * generous move timer buys a sharper estimate instead of being wasted. When
 * there is none (the benchmark pipeline), it runs exactly the trial count it
 * was constructed with, which keeps a benchmark reproducible and comparable
 * across bots.
 *
 * The self-limit is advisory and uses System.nanoTime() directly, because an
 * agent sees only a PlayerView and never the TurnPolicy's clock. Enforcement
 * is HandRunner's job and uses that clock; this is the agent cooperating so
 * enforcement never has to fire. SAFETY_FRACTION leaves headroom so a bot
 * that finishes a batch of trials just under the wire does not lose its
 * answer to a few microseconds of overshoot.
 */
public class MonteCarloCallFoldAgent implements PokerAgent {

    /** Fraction of the move budget the agent will actually spend on rollouts. */
    private static final double SAFETY_FRACTION = 0.8;

    /**
     * Ceiling on trials when running against a deadline. Monte Carlo error
     * falls as 1/sqrt(n), so at 200k trials the standard error on an equity
     * estimate is around 0.001 -- far finer than any pot-odds threshold it
     * gets compared against. Past here a longer timer buys nothing.
     */
    private static final int MAX_TIMED_TRIALS = 200_000;

    private final int trials;
    private final RandomGenerator random;

    /**
     * @param trials rollouts per decision when untimed, and the floor on what
     *               a timed decision will attempt
     * @param seed   seeds this agent's rollout sampling
     */
    public MonteCarloCallFoldAgent(int trials, long seed) {
        if (trials <= 0) {
            throw new IllegalArgumentException("trials must be positive");
        }
        this.trials = trials;
        // SplittableRandom, not Random: Random synchronises on an AtomicLong
        // per draw, and a rollout draws many times. Nothing here is concurrent.
        this.random = new SplittableRandom(seed);
    }

    @Override
    public ActionResult performAction(PlayerView view) {
        if (view.amountToCall() == 0) {
            return ActionResult.check();
        }

        int maxTrials = trials;
        long deadlineNanos = MonteCarloEquityEstimator.NO_DEADLINE;
        if (view.isTimed()) {
            long budgetNanos = (long) (view.timeBudgetMillis() * 1_000_000L * SAFETY_FRACTION);
            deadlineNanos = System.nanoTime() + budgetNanos;
            maxTrials = Math.max(trials, MAX_TIMED_TRIALS);
        }

        double equity = MonteCarloEquityEstimator.estimateEquity(
                view.myHoleCards(),
                view.communityCards(),
                countLiveOpponents(view),
                maxTrials,
                random,
                deadlineNanos);

        // Pot odds. potTotal already includes the bet being faced, so the
        // chips in the pot after calling are potTotal + toCall, and the share
        // of them this call is buying is toCall / (potTotal + toCall).
        int toCall = view.amountToCall();
        double threshold = toCall / (double) (toCall + view.potTotal());

        return equity > threshold ? ActionResult.call() : ActionResult.fold();
    }

    /**
     * Opponents who can still win the pot. All-in players count: they have no
     * further decisions to make but their hand still goes to showdown.
     */
    private int countLiveOpponents(PlayerView view) {
        int count = 0;
        for (OpponentInfo p : view.players()) {
            if (p.seatIndex() != view.mySeatIndex() && !p.folded()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public String getName() {
        return "MonteCarlo";
    }
}