package com.andrei.pokerface;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Prints a human-readable narration: hand boundaries, move-timer expiries,
 * and at showdown, every non-folded player's hole cards alongside their best
 * hand description, followed by the pot award(s). Distinct from
 * FileHandLogger's terse one-line-per-event format, which is built for
 * parsing rather than reading live.
 *
 * Keeps its own copy of the community board, since GameEvent never carries it
 * as a whole -- it's rebuilt incrementally from CommunityCardDealt events and
 * reset on every HandStarted. Holds the live Player list to read hole cards
 * and fold status, since hole cards are never logged as events at all (see
 * GameState.dealHoleCards). That is safe because hole cards are cleared
 * solely by resetForNewHand(), which the *next* hand's startNewHand()
 * triggers -- always after this logger has already read them via the current
 * hand's HandEnded event.
 *
 * Output is injected rather than hardcoded to System.out, matching
 * HumanCliAgent, so a caller or test can capture it without swapping the
 * JVM's stdout.
 *
 * The switch over GameEvent names every variant explicitly, including the two
 * deliberately ignored. There is no default arm, and there should never be
 * one: GameEvent is sealed precisely so that adding a variant breaks every
 * consumer at compile time, and a default silently swallows the new case
 * instead. Ignoring an event is fine -- doing so without saying which event
 * is not.
 */
public class ConsoleHandLogger implements HandLogger {
    private final List<Player> players;
    private final PrintStream out;
    private final List<Integer> board = new ArrayList<>();
    private final List<GameEvent.PotAwarded> potsThisHand = new ArrayList<>();

    public ConsoleHandLogger(List<Player> players) {
        this(players, System.out);
    }

    public ConsoleHandLogger(List<Player> players, PrintStream out) {
        this.players = players;
        this.out = out;
    }

    @Override
    public void log(GameEvent event) {
        switch (event) {
            case GameEvent.HandStarted e -> {
                board.clear();
                potsThisHand.clear();
                out.println("\n===== New hand (dealer seat " + e.dealerSeat() + ") =====");
            }
            case GameEvent.CommunityCardDealt e -> board.add(e.card());
            case GameEvent.PotAwarded e -> potsThisHand.add(e);
            case GameEvent.HandEnded e -> printShowdown(e);

            // Only worth surfacing when the timer actually expired -- otherwise
            // this fires once per decision and would bury the narration.
            case GameEvent.DecisionTimed e -> {
                if (e.timedOut()) {
                    out.println("Seat " + e.seatIndex() + " ran out of time -- "
                            + "checked if free, folded otherwise.");
                }
            }

            // Deliberately silent: HumanCliAgent's render() already shows the
            // blinds and the action so far before every prompt.
            case GameEvent.BlindPosted ignored -> { }
            case GameEvent.ActionTaken ignored -> { }
        }
    }

    private void printShowdown(GameEvent.HandEnded e) {
        if (e.wonByFold()) {
            int total = potsThisHand.stream().mapToInt(GameEvent.PotAwarded::amount).sum();
            out.println("Hand won by fold -- seat(s) " + e.winnerSeats() + " take " + total + " chips.");
            return;
        }

        out.println("---- Showdown ----");
        int[] boardCards = board.stream().mapToInt(Integer::intValue).toArray();
        if (boardCards.length > 0) {
            out.println("  Board: " + CardUtils.handToString(boardCards));
        }
        for (Player p : players) {
            int[] hole = p.getHoleCards(); // returns a clone; read once and reuse
            if (p.isFolded() || hole[0] < 0) {
                continue; // folded, or never dealt into this hand at all
            }
            String description = HandEvaluator.describeBestHand(combine(hole, boardCards));
            out.println("  Seat " + p.getSeatIndex() + " " + p.getName() + ": "
                    + CardUtils.handToString(hole) + "  ->  " + description);
        }
        for (GameEvent.PotAwarded pot : potsThisHand) {
            out.println("  Pot #" + pot.potIndex() + " (" + pot.amount() + ") -> seats " + pot.winnerSeats());
        }
    }

    private int[] combine(int[] hole, int[] boardCards) {
        int[] combined = new int[hole.length + boardCards.length];
        System.arraycopy(hole, 0, combined, 0, hole.length);
        System.arraycopy(boardCards, 0, combined, hole.length, boardCards.length);
        return combined;
    }
}