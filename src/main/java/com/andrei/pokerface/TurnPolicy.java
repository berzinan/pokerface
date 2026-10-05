package com.andrei.pokerface;

import java.util.function.LongSupplier;

/**
 * Per-decision timing configuration for a hand: how long an agent may take,
 * and where the clock comes from.
 *
 * Measurement and enforcement are deliberately separate. The stopwatch ALWAYS
 * runs -- every decision's duration is recorded as a GameEvent.DecisionTimed
 * regardless of configuration, because decision latency is a statistic worth
 * having even where no deadline applies. Only enforcement is optional.
 *
 * The two pipelines want opposite things:
 *   - tournament play uses timed(30_000): a real move timer for humans and
 *     bots alike
 *   - benchmarking uses UNTIMED: a 200k-hand run makes ~1M decisions, so a
 *     deadline that fires on a GC pause or a scheduler hiccup would inject a
 *     forced fold into the bb/100 samples and make the run non-reproducible
 *
 * Enforcement is measure-after, not abandonment: the agent call runs to
 * completion and an overrunning result is discarded, not interrupted. Java
 * cannot forcibly stop a running method, and the alternative (a worker thread
 * per decision) leaks a CPU-burning thread for any agent that ignores
 * interrupts, and obliges every agent to be thread-safe. An agent stuck in an
 * infinite loop therefore hangs the run -- that is a bug in the agent, and it
 * announces itself immediately.
 *
 * The clock is a nanoTime-style monotonic source, injectable so tests can
 * drive the timeout path without real time passing. It measures durations
 * only; it is unrelated to the wall-clock used for blind escalation.
 */
public record TurnPolicy(long budgetMillis, LongSupplier nanoClock) {

    /**
     * Measure every decision, enforce nothing. The benchmark pipeline's policy,
     * and the default for any caller that doesn't specify one.
     */
    public static final TurnPolicy UNTIMED = new TurnPolicy(PlayerView.UNLIMITED, System::nanoTime);

    public TurnPolicy {
        if (budgetMillis < 0 && budgetMillis != PlayerView.UNLIMITED) {
            throw new IllegalArgumentException(
                    "budgetMillis must be non-negative or PlayerView.UNLIMITED");
        }
        if (nanoClock == null) {
            throw new IllegalArgumentException("nanoClock cannot be null");
        }
    }

    /** A real move timer of the given length, read from System.nanoTime(). */
    public static TurnPolicy timed(long budgetMillis) {
        return new TurnPolicy(budgetMillis, System::nanoTime);
    }

    /** True when an overrun should be replaced by the fallback action. */
    public boolean isEnforcing() {
        return budgetMillis != PlayerView.UNLIMITED;
    }

    /**
     * The budget in nanoseconds, for comparison against measured elapsed time.
     * Meaningless when isEnforcing() is false; callers must check that first.
     */
    public long budgetNanos() {
        return budgetMillis * 1_000_000L;
    }
}