package com.andrei.pokerface;

/* Add class docstring here */
public class Player {
    private final int seatIndex;          // fixed seat position at the table (0..n-1)
    private String name;
    private int stack;                    // chips not yet committed to the pot
    private final int[] holeCards;        // size 2; -1 = not yet dealt (sentinel, consistent with CardUtils range checks)
    private int roundBet;                 // chips committed during the CURRENT betting round only
    private int totalCommitted;           // chips committed during the WHOLE hand; drives side-pot math
    private boolean folded;               // has this player folded?
    private boolean allIn;                // is this player all in?
    private boolean eliminated;           // is this player eliminated?

    public Player(int seatIndex, String name, int stack) {
        this.seatIndex = seatIndex;
        this.name = name;
        this.stack = stack;
        this.holeCards = new int[]{-1, -1};
        this.roundBet = 0;
        this.totalCommitted = 0;
        this.folded = false;
        this.allIn = false;
        this.eliminated = false;
    }

    /* Hand lifecycle */
    // Deal a hole card to this player
    public void dealHoleCard(int slot, int card) {
        if (slot < 0 || slot > 1) {
            throw new IllegalArgumentException("Hole card slot must be 0 or 1");
        }
        holeCards[slot] = card;
    }

    // Display this player's hole cards
    public int[] getHoleCards() {
        return holeCards.clone();
    }

    /** Reset betting state for a new betting round (preflop -> flop -> turn -> river). */
    public void resetForNewRound() {
        roundBet = 0;
    }

    /**
     * Reset all state for a brand new hand (new deal). An eliminated player
     * resets into a permanently-folded state rather than a fresh one.
     */
    public void resetForNewHand() {
        roundBet = 0;
        totalCommitted = 0;
        folded = eliminated;
        allIn = false;
        holeCards[0] = -1;
        holeCards[1] = -1;
    }

    /* Betting actions */

    /**
     * Commit chips from the stack into the pot (covers calls, bets, raises, and blinds).
     * If this exhausts the stack, the player is automatically marked all-in.
     */
    public void commit(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Cannot commit a negative amount");
        }
        if (amount > stack) {
            throw new IllegalArgumentException("Cannot commit more than remaining stack");
        }
        stack -= amount;
        roundBet += amount;
        totalCommitted += amount;
        if (stack == 0) {
            allIn = true;
        }
    }

    // fold
    public void fold() {
        folded = true;
    }

    /** Active means still able to act this round (hasn't folded, hasn't already shoved all-in). */
    public boolean isActive() {
        return !folded && !allIn;
    }

    /* Getters / setters */

    public int getSeatIndex() { return seatIndex; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getStack() { return stack; }
    public void addToStack(int amount) { stack += amount; } // used when awarding pot winnings
    public int getRoundBet() { return roundBet; }
    public int getTotalCommitted() { return totalCommitted; }
    public boolean isFolded() { return folded; }
    public boolean isAllIn() { return allIn; }
    public void setStack(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Stack cannot be negative");
        }
        this.stack = amount;
    }
    /** Marks this player as permanently out of the session (e.g. busted in a tournament). */
    public void eliminate() { eliminated = true; }
    public boolean isEliminated() { return eliminated; }

}