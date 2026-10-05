package com.andrei.pokerface;

import java.util.List;
import java.util.Optional;

/** Outcome of a TournamentRunner.run() call. */
public record TournamentResult(int handsPlayed, List<Player> finalPlayers) {

    /** The sole remaining non-eliminated player, if the tournament ended that way. */
    public Optional<Player> winner() {
        List<Player> live = finalPlayers.stream().filter(p -> !p.isEliminated()).toList();
        return live.size() == 1 ? Optional.of(live.get(0)) : Optional.empty();
    }
}