package com.andrei.pokerface;

import java.util.List;

/**
 * Immutable, information-restricted snapshot of GameState handed to a
 * PokerAgent when it is that agent's turn. Contains exactly what a real
 * player at the table would legitimately know -- nothing more. Built
 * fresh per call by GameState.buildPlayerView(); agents never hold a
 * reference to GameState or Player, and every array/list field is a
 * defensive copy so an agent cannot mutate engine state.
 */
public record PlayerView(
        int mySeatIndex,
        int[] myHoleCards,          // length 2
        int[] communityCards,       // length 0-5 depending on street
        Round round,
        int dealerIndex,
        int deckRemaining,          // count only, never the actual cards
        int currentBet,             // amount every player must match this round
        int minRaiseTarget,         // lowest legal RAISE amount (a "raise to" target); a
                                    // short all-in above currentBet is always legal even
                                    // if below this
        int potTotal,               // every chip committed this hand, folded chips included
        List<OpponentInfo> players, // every seat, in seat order, including self
        long timeBudgetMillis       // wall-clock allowance for this decision, or UNLIMITED
) {
    /**
     * timeBudgetMillis value meaning 'take as long as needed'. Used by the 
     * benchmark pipeline, where enforcing wall-clock deadline would inject
     * non-reproducible forced folds into chip samples.
     */
    public static final long UNLIMITED = -1L;


    public PlayerView {
        if (timeBudgetMillis < 0 && timeBudgetMillis != UNLIMITED) {
            throw new IllegalArgumentException(
                "timeBudgetMillis must be non-negative or PlayerView.UNLIMITED");
        }
        myHoleCards = myHoleCards.clone();
        communityCards = communityCards.clone();
        players = List.copyOf(players);
    }

    // Records generate a default accessor that returns the field as-is; for array
    // fields that returns the live internal array, not a copy. Override both so
    // callers can't mutate engine state through the "view".
    public int[] myHoleCards() {
        return myHoleCards.clone();
    }

    public int[] communityCards() {
        return communityCards.clone();
    }

    public OpponentInfo me() {
        return players.get(mySeatIndex);
    }

    /** Chips required to call, floored at 0 (covers the "nothing owed" case). */
    public int amountToCall() {
        return Math.max(0, currentBet - me().roundBet());
    }

    public int numActivePlayers() {
        int count = 0;
        for (OpponentInfo p : players) {
            if (!p.folded() && !p.allIn()) {
                count++;
            }
        }
        return count;
    }

    /**
     * True when a move timer is in force for this decision. An agent that does
     * variable-length work (rollouts, search) should branch on this rather than 
     * comparing against UNLIMITED
     */
    public boolean isTimed() {
        return timeBudgetMillis != UNLIMITED;
    }
}