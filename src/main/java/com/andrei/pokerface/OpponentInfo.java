package com.andrei.pokerface;

/**
 * Read-only public snapshot of a single seat. Used for every seat in a
 * PlayerView, including the acting agent's own seat -- hole cards are
 * deliberately excluded here and delivered separately in PlayerView so
 * that a single "players" list can never leak an opponent's cards.
 */
public record OpponentInfo(
        int seatIndex,
        String name,
        int stack,
        int roundBet,
        int totalCommitted,
        boolean folded,
        boolean allIn,
        boolean eliminated
) {}