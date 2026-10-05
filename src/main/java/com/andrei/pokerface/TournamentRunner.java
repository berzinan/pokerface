package com.andrei.pokerface;

import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Drives a tournament to completion on a fixed roster: deals hands until the
 * end condition fires, eliminating anyone who busts, with blinds escalating
 * as the session clock advances.
 *
 * Two clocks, deliberately. They measure different things and only coincide
 * at a real table:
 *
 *   policy.nanoClock() -- real elapsed duration, read twice per decision to
 *   time the agent's move. Always a genuine monotonic clock.
 *
 *   sessionClock -- tournament progress in milliseconds, read once per hand
 *   to ask the BlindSchedule what level applies. For a human at a table this
 *   is the wall clock. For a bot-vs-bot benchmark it must NOT be: a thousand
 *   bot hands finish in a second, so wall-clock blinds would never escalate,
 *   every tournament would run to the hand cap, and nothing would ever
 *   resolve. BotTournamentStatsCollector supplies a virtual clock advancing a
 *   fixed step per hand instead, which also makes escalation reproducible.
 *
 * There is one GameState for the whole tournament. Blind changes call
 * setBlinds() between hands rather than rebuilding it, so the dealer button,
 * the deck and the logger all simply carry on.
 *
 * Bust handling is inlined as elimination rather than pluggable. The previous
 * BustHandler interface existed to let a cash-game or rebuy mode share this
 * loop, but no such mode exists or is planned -- a ring game has its own
 * runner with no elimination at all.
 */
public final class TournamentRunner {

    private TournamentRunner() {}

    /**
     * @param players       seated players; agents.get(i) controls players.get(i)
     * @param agents        one agent per seat
     * @param blindSchedule what blinds apply at a given elapsed session time
     * @param endCondition  when to stop dealing
     * @param seedSource    a fresh RNG seed per hand
     * @param policy        per-decision time budget and the clock that measures it
     * @param sessionClock  tournament progress in milliseconds, for blind escalation
     * @param logger        event subscriber (HandLogger.NO_OP if unused)
     */
    public static TournamentResult run(
            List<Player> players,
            List<PokerAgent> agents,
            BlindSchedule blindSchedule,
            TournamentEndCondition endCondition,
            IntSupplier seedSource,
            TurnPolicy policy,
            LongSupplier sessionClock,
            HandLogger logger) {

        if (players == null || agents == null || players.size() != agents.size()) {
            throw new IllegalArgumentException(
                    "Need exactly one agent per player (" + (players == null ? 0 : players.size()) + " players)");
        }
        if (policy == null) {
            throw new IllegalArgumentException("TurnPolicy cannot be null (use TurnPolicy.UNTIMED)");
        }

        long startMillis = sessionClock.getAsLong();

        BlindLevel level = blindSchedule.blindsFor(0L);
        GameState state = new GameState(players, level.smallBlind(), level.bigBlind());
        state.setLogger(logger);

        int handsPlayed = 0;
        while (!endCondition.isTournamentOver(state, handsPlayed)) {
            HandRunner.playHand(state, agents, seedSource.getAsInt(), policy);
            handsPlayed++;

            for (Player p : players) {
                if (p.getStack() == 0 && !p.isEliminated()) {
                    p.eliminate();
                }
            }

            // Between hands is the only safe point to change blinds: startNewHand()
            // re-seeds minRaise from the big blind, so a mid-hand change would leave
            // the current round enforcing the previous level's minimum raise.
            long elapsedMillis = sessionClock.getAsLong() - startMillis;
            BlindLevel next = blindSchedule.blindsFor(elapsedMillis);
            if (next.smallBlind() != state.getSmallBlind() || next.bigBlind() != state.getBigBlind()) {
                state.setBlinds(next.smallBlind(), next.bigBlind());
            }
        }

        return new TournamentResult(handsPlayed, players);
    }

    /** Convenience overload: real wall clock for blind escalation. */
    public static TournamentResult run(
            List<Player> players,
            List<PokerAgent> agents,
            BlindSchedule blindSchedule,
            TournamentEndCondition endCondition,
            IntSupplier seedSource,
            TurnPolicy policy,
            HandLogger logger) {
        return run(players, agents, blindSchedule, endCondition, seedSource, policy,
                System::currentTimeMillis, logger);
    }

    /** Convenience overload: constant blinds, stop at one survivor, untimed, no logging. */
    public static TournamentResult runFreezeout(
            List<Player> players, List<PokerAgent> agents,
            int smallBlind, int bigBlind, IntSupplier seedSource) {
        return run(players, agents,
                BlindSchedule.constant(smallBlind, bigBlind),
                TournamentEndCondition.LAST_PLAYER_STANDING,
                seedSource,
                TurnPolicy.UNTIMED,
                HandLogger.NO_OP);
    }
}