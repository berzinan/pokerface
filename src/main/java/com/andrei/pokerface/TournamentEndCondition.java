package com.andrei.pokerface;

/** Decides whether the tournament loop should stop after the hand just played. */
@FunctionalInterface
public interface TournamentEndCondition {
    boolean isTournamentOver(GameState state, int handsPlayed);

    /** Standard tournament/freezeout rule: stop once at most one player still has chips. */
    TournamentEndCondition LAST_PLAYER_STANDING = (state, handsPlayed) -> state.getLivePlayerCount() <= 1;

    /**
     * Combinator: also stop after a hard cap on hands played, regardless of chip
     * counts. Useful as a safety bound in tests, or for a training loop that wants
     * a fixed-length episode rather than playing to a single survivor.
     */
    default TournamentEndCondition orAfter(int maxHands) {
        return (state, handsPlayed) -> this.isTournamentOver(state, handsPlayed) || handsPlayed >= maxHands;
    }
}