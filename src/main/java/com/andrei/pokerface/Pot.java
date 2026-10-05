package com.andrei.pokerface;

import java.util.Set;

/**
 * Single pool of chips and the set of seats allowed to win it. One hand
 * produces one Pot when everybody can cover every bet, and several -- a main
 * pot plus one side pot per all-in threshold -- when they can't.
 *
 * Built exclusively by GameState.computeSidePots(), which recomputes the whole
 * list from each player's totalCommitted rather than mutating pots in place as
 * chips arrive. A Pot is therefore a snapshot of one layer of the betting, not
 * a running total that lives across streets.
 *
 * Deliberately half-immutable: the amount can grow (see addAmount), but the
 * eligible-seat set is frozen at construction. 
 */
public class Pot {
    private int amount;                       // chips in this pot, including chips from folded contributors
    private final Set<Integer> eligibleSeats; // seatIndex values of players who can win this pot

    /**
     * @param amount           starting chip total for this pot
     * @param eligibleSeats    seats that may win it; defensively copied so caller is free to mutate its own set
     */
    public Pot(int amount, Set<Integer> eligibleSeats) {
        this.amount = amount;
        this.eligibleSeats = Set.copyOf(eligibleSeats);
    }

    // chips currently in this pot
    public int getAmount() { return amount; }

    /**
     * Folds another betting layer into this pot. Used by computeSidePots() when
     * consecutive layers turn out to have identical eligible sets. 
     */
    public void addAmount(int extra) {
        if (extra < 0) {
            throw new IllegalArgumentException("Cannot add a negative amount to a pot");
        }
        amount += extra;
    }

     /**
     * Seats that can win this pot. Immutable.
     */
    public Set<Integer> getEligibleSeats() {
        return eligibleSeats;
    }

    // Can the player in this seat win this pot?
    public boolean isEligible(int seatIndex) {
        return eligibleSeats.contains(seatIndex);
    }

    @Override
    public String toString() {
        return "Pot{amount=" + amount + ", eligibleSeats=" + eligibleSeats + "}";
    }
}