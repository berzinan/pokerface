package com.andrei.pokerface;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Drives a single poker hand end-to-end: blinds -> preflop betting -> flop ->
 * turn -> river -> showdown (or an earlier fold-out), against a set of
 * PokerAgent implementations. This is the engine driver layer -- GameState
 * exposes every primitive (postBlinds, setFirstActor, buildPlayerView,
 * processAction, advanceRound, dealCommunityCard, checkFoldWin,
 * resolveShowdown) but nothing else calls them in the right order for a full
 * hand.
 *
 * This layer also owns per-decision timing. GameState is entirely time-free;
 * HandRunner holds the clock, stamps each PlayerView with its budget, times
 * the agent call, and substitutes a fallback action on an overrun. See
 * TurnPolicy for why enforcement is measure-after rather than abandonment.
 *
 * One playHand() call plays exactly one hand. Chip stacks persist on the
 * Player objects inside the GameState across calls, so a caller wanting a
 * full session calls playHand() repeatedly with a fresh seed each time --
 * that's the next layer up, not built here. Note that this runner does NOT
 * handle player elimination: a 0-stack player will still be dealt in and will
 * check/call for 0 next hand (Player.resetForNewHand() clears allIn/folded
 * every hand), but can never win a pot since GameState.computeSidePots() only
 * counts players with totalCommitted() > 0. The tournament layer filters
 * busted players out before calling this again.
 */
public final class HandRunner {

    private HandRunner() {}

    /** How many community cards get dealt when entering each post-flop street. */
    private static final Map<Round, Integer> CARDS_FOR_ROUND = Map.of(
            Round.FLOP, 3,
            Round.TURN, 1,
            Round.RIVER, 1
    );

    /** Convenience overload: measure decision latency, enforce no deadline. */
    public static HandResult playHand(GameState state, List<PokerAgent> agents, int seed) {
        return playHand(state, agents, seed, TurnPolicy.UNTIMED);
    }

    /**
     * Plays one complete hand on the given GameState.
     *
     * @param state  the table to play on; startNewHand(seed) is called internally,
     *               so the caller should NOT call it beforehand.
     * @param agents agents indexed by seat -- agents.get(i) must be the agent
     *               controlling state.getPlayers().get(i). Size must match the
     *               player count.
     * @param seed   RNG seed forwarded to GameState.startNewHand / shuffle.
     * @param policy per-decision time budget and clock.
     * @return a HandResult describing how the hand ended and who won.
     */
    public static HandResult playHand(
            GameState state, List<PokerAgent> agents, int seed, TurnPolicy policy) {

        if (agents == null || agents.size() != state.getPlayers().size()) {
            throw new IllegalArgumentException(
                    "Need exactly one agent per seat (" + state.getPlayers().size() + " players)");
        }
        if (policy == null) {
            throw new IllegalArgumentException("TurnPolicy cannot be null (use TurnPolicy.UNTIMED)");
        }

        state.startNewHand(seed);
        state.postBlinds();
        state.setFirstActor();

        runBettingRound(state, agents, policy);
        Optional<Player> foldWinner = state.checkFoldWin();
        if (foldWinner.isPresent()) {
            return HandResult.foldWin(foldWinner.get());
        }

        // Flop, turn, river: advance the street, deal its cards, run betting,
        // then re-check for a fold-out after every street. If everyone left is
        // already all-in, runBettingRound() below is a no-op (isBettingOver()
        // short-circuits it immediately) but we still walk through every street
        // so the board is fully dealt out before showdown -- no separate code
        // path is needed for the "everyone shoved early" case.
        while (state.getRound() != Round.RIVER) {
            state.advanceRound();
            dealStreetCards(state);

            state.setFirstActor();
            runBettingRound(state, agents, policy);

            foldWinner = state.checkFoldWin();
            if (foldWinner.isPresent()) {
                return HandResult.foldWin(foldWinner.get());
            }
        }

        state.advanceRound(); // RIVER -> SHOWDOWN
        List<Player> winners = state.resolveShowdown();
        return HandResult.showdown(winners);
    }

    /** Deals the correct number of community cards for whatever round GameState is now in. */
    private static void dealStreetCards(GameState state) {
        int count = CARDS_FOR_ROUND.getOrDefault(state.getRound(), 0);
        for (int i = 0; i < count; i++) {
            state.dealCommunityCard();
        }
    }

    private static void runBettingRound(GameState state, List<PokerAgent> agents, TurnPolicy policy) {
        if (state.isBettingOver()) {
            return; // e.g. everyone left is already all-in from a previous street
        }

        int toAct = countActive(state);
        while (toAct > 0 && !state.isBettingOver()) {
            int actingSeat = state.getPlayerTurnIndex();
            int betBefore = state.getCurrentBet();

            takeTurn(state, agents.get(actingSeat), actingSeat, policy);

            toAct--;
            if (state.getCurrentBet() > betBefore) {
                // That action was a raise -- CALL/CHECK/FOLD never increase currentBet
                // (CALL is capped at matching it, see GameState.processAction). Everyone
                // else needs to respond to it. The raiser themselves only needs to be
                // excluded from that count if they can still act again later -- if the
                // raise itself put them all-in, countActive(state) already excludes them
                // (isActive() is false), so subtracting 1 again would incorrectly skip a
                // different player who still owes a response to this raise.
                boolean raiserStillActive = state.getPlayers().get(actingSeat).isActive();
                toAct = countActive(state) - (raiserStillActive ? 1 : 0);
            }
        }
    }

    /**
     * Builds the acting seat's view, times the agent's decision, and applies it.
     *
     * Replaces the old GameState.takeTurn() convenience method, which couldn't
     * support a move timer: enforcement needs a seam between "agent returned"
     * and "action applied", so the result can be discarded before it reaches
     * processAction().
     */
    private static void takeTurn(GameState state, PokerAgent agent, int seat, TurnPolicy policy) {
        PlayerView view = state.buildPlayerView(seat, policy.budgetMillis());

        long start = policy.nanoClock().getAsLong();
        ActionResult result = agent.performAction(view);
        long elapsedNanos = policy.nanoClock().getAsLong() - start;

        if (result == null) {
            throw new IllegalStateException(
                    "Agent " + agent.getName() + " in seat " + seat + " returned a null ActionResult");
        }

        boolean timedOut = policy.isEnforcing() && elapsedNanos > policy.budgetNanos();
        if (timedOut) {
            result = timeoutFallback(view);
        }

        state.getLogger().log(new GameEvent.DecisionTimed(
                seat, state.getRound(), elapsedNanos, timedOut));

        state.processAction(result.action(), result.amount());
    }

    /**
     * What an agent that ran out of time is deemed to have done: check if it
     * costs nothing, otherwise fold. Never an illegal action -- CHECK is legal
     * exactly when amountToCall() is 0, and FOLD is always legal.
     */
    private static ActionResult timeoutFallback(PlayerView view) {
        return view.amountToCall() == 0 ? ActionResult.check() : ActionResult.fold();
    }

    private static int countActive(GameState state) {
        return (int) state.getPlayers().stream().filter(Player::isActive).count();
    }
}