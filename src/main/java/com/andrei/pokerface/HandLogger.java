package com.andrei.pokerface;

/**
 * Subscriber interface for the engine's event stream. Implementations decide
 * what to do with each event -- print it, write it to disk, accumulate it in
 * memory for test assertions, tally statistics. Events flow one way only:
 * nothing ever reads a value back from a logger, and no logger can alter
 * engine behaviour.
 *
 * Most events are fired by GameState as a side effect of its state-mutating
 * calls; DecisionTimed is fired by HandRunner, which is where the clock
 * lives. Both reach the logger registered via GameState.setLogger(). The
 * default (HandLogger.NO_OP) is wired in automatically, so any GameState
 * that never calls setLogger() sees zero behaviour change.
 *
 * Implementations that switch over GameEvent should name every variant
 * explicitly rather than using a default arm -- see ConsoleHandLogger for
 * why.
 */
@FunctionalInterface
public interface HandLogger {

    void log(GameEvent event);

    /** Logger that discards every event -- the default for any GameState that never opts in. */
    HandLogger NO_OP = event -> {};
}