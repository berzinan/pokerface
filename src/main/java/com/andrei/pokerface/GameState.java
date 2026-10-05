package com.andrei.pokerface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Optional;

public class GameState {

    private final Deck deck;
    private final int[] communityCards;   // fixed size 5; -1 = not yet dealt
    private int communityCardsDealt;      // how many of the 5 slots are currently filled

    private final List<Player> players;   // all players seated at the table, indexed by seat order
    private final List<Pot> pots;         // main pot + any side pots; recomputed via computeSidePots()

    private int currentBet;               // amount a player must match this round to call
    private int minRaise;                 // minimum legal raise INCREMENT over currentBet (NL rule)
    private int dealerIndex;              // seat index of the dealer button
    private int playerTurnIndex;          // seat index of the player currently acting
    private Round round;                  // PREFLOP, FLOP, TURN, RIVER, SHOWDOWN
    private int smallBlind;               // mutable: a tournament escalates blinds between hands
    private int bigBlind;                 // mutable: see setBlinds()
    private boolean handComplete;         // true once the hand has resolved (fold-out or showdown)
    private HandLogger logger;            // event subscriber; defaults to a no-op so logging is opt-in

    public GameState(List<Player> players, int smallBlind, int bigBlind) {
        if (players == null || players.size() < 2) {
            throw new IllegalArgumentException("Need at least 2 players to start a hand");
        }
        this.deck = new Deck();
        this.communityCards = new int[]{-1, -1, -1, -1, -1};
        this.communityCardsDealt = 0;
        this.players = players;
        this.pots = new ArrayList<>();
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.round = Round.PREFLOP;
        this.dealerIndex = 0;
        this.playerTurnIndex = 0;
        this.currentBet = 0;
        this.minRaise = bigBlind;
        this.handComplete = false;
        this.logger = HandLogger.NO_OP;
    }

    /* -------------------------------------------------------------- */
    /* Blind level                                                     */
    /* -------------------------------------------------------------- */

    /**
     * Changes the blinds in effect. Call only BETWEEN hands: the new bigBlind
     * reaches minRaise via startNewHand(), so changing blinds mid-hand would
     * leave the current round enforcing the previous level's minimum raise.
     *
     * A table's blind level is the one piece of its configuration that
     * legitimately changes over a session, which is why it lives here as
     * mutable state rather than forcing a whole new GameState per level.
     * The escalation POLICY -- when to step up, by how much, rounded to what
     * denomination -- deliberately stays outside the engine.
     */
    public void setBlinds(int smallBlind, int bigBlind) {
        if (smallBlind <= 0 || bigBlind <= 0) {
            throw new IllegalArgumentException("Blinds must be positive");
        }
        if (bigBlind < smallBlind) {
            throw new IllegalArgumentException("Big blind cannot be smaller than the small blind");
        }
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
    }

    /* -------------------------------------------------------------- */
    /* Logging                                                         */
    /* -------------------------------------------------------------- */

    /**
     * Registers the event subscriber for this table. Passing null resets to
     * the no-op logger rather than throwing.
     */
    public void setLogger(HandLogger logger) {
        this.logger = (logger == null) ? HandLogger.NO_OP : logger;
    }

    public HandLogger getLogger() {
        return logger;
    }

    /* -------------------------------------------------------------- */
    /* Dealing                                                         */
    /* -------------------------------------------------------------- */

    public void dealHoleCards(int seed) {
        deck.shuffle(seed);
        for (int slot = 0; slot < 2; slot++) {
            for (Player p : players) {
                if (p.isEliminated()) continue; // busted seats never receive cards
                p.dealHoleCard(slot, deck.deal());
            }
        }
    }

    /** Deal exactly one more community card (flop should be called 3x, turn/river once each). */
    public void dealCommunityCard() {
        if (communityCardsDealt >= 5) {
            throw new IllegalStateException("All 5 community cards have already been dealt");
        }
        if (communityCardsDealt == 0 || communityCardsDealt == 3 || communityCardsDealt == 4) {
            deck.burn();
        }
        int card = deck.deal();
        communityCards[communityCardsDealt] = card;
        communityCardsDealt++;
        logger.log(new GameEvent.CommunityCardDealt(round, card, communityCardsDealt));
    }

    /** Returns only the community cards dealt so far */
    public int[] getCommunityCards() {
        return Arrays.copyOf(communityCards, communityCardsDealt);
    }

    /** Convenience: this player's hole cards + whatever community cards are up, ready for HandEvaluator. */
    public int[] getEvaluableCards(Player player) {
        int[] hole = player.getHoleCards();
        int[] board = getCommunityCards();
        int[] combined = new int[hole.length + board.length];
        System.arraycopy(hole, 0, combined, 0, hole.length);
        System.arraycopy(board, 0, combined, hole.length, board.length);
        return combined;
    }

    /* -------------------------------------------------------------- */
    /* Betting round bookkeeping                                       */
    /* -------------------------------------------------------------- */

    /**
     * Applies a chip commitment from the acting player (call/bet/raise/blind) and keeps
     * currentBet in sync so the next caller knows what they owe.
     */
    public void commitChips(Player player, int amount) {
        player.commit(amount);
        if (player.getRoundBet() > currentBet) {
            currentBet = player.getRoundBet();
        }
    }

    public void foldPlayer(Player player) {
        player.fold();
    }

    /** Advances to the next street, resetting per-round (not per-hand) betting state. */
    public void advanceRound() {
        switch (round) {
            case PREFLOP -> round = Round.FLOP;
            case FLOP -> round = Round.TURN;
            case TURN -> round = Round.RIVER;
            case RIVER -> round = Round.SHOWDOWN;
            case SHOWDOWN -> throw new IllegalStateException("Hand is already at showdown");
        }
        currentBet = 0;
        minRaise = bigBlind;
        for (Player p : players) {
            p.resetForNewRound();
        }
    }

    /** Moves playerTurnIndex to the next player who is still able to act (not folded, not all-in). */
    public void advanceTurn() {
        int n = players.size();
        for (int step = 1; step <= n; step++) {
            int candidate = (playerTurnIndex + step) % n;
            if (players.get(candidate).isActive()) {
                playerTurnIndex = candidate;
                return;
            }
        }
        // No active players remain (everyone left is folded or all-in) -- betting is over for this round.
        // Note: this relies on eliminated players being folded, which resetForNewHand() guarantees.
    }

    /** Number of players still in the session (not eliminated). Distinct from
      * players.size(), which is the fixed seat count for the whole table. */
    private int countLivePlayers() {
        return (int) players.stream().filter(p -> !p.isEliminated()).count();
    }

    /**
     * Walks forward from fromSeat (EXCLUSIVE) and returns the seat index of the
     * stepsForward-th live (non-eliminated) player encountered. Eliminated seats
     * are skipped entirely.
     */
    private int liveSeatOffset(int fromSeat, int stepsForward) {
        int n = players.size();
        int seat = fromSeat;
        int found = 0;
        for (int i = 0; i < n; i++) {
            seat = (seat + 1) % n;
            if (!players.get(seat).isEliminated()) {
                found++;
                if (found == stepsForward) {
                    return seat;
                }
            }
        }
        throw new IllegalStateException(
                "Fewer than " + stepsForward + " live players remain at the table");
    }

    /** True once at most one player can still voluntarily act -- betting for the hand is effectively over. */
    public boolean isBettingOver() {
        long stillIn = players.stream().filter(p -> !p.isFolded()).count();
        long canAct = players.stream().filter(Player::isActive).count();
        return stillIn <= 1 || canAct == 0;
    }

    /* -------------------------------------------------------------- */
    /* Blinds & first actor                                            */
    /* -------------------------------------------------------------- */

    /**
     * Posts small and big blinds from the appropriate seats for the CURRENT dealerIndex.
     * Must be called once per hand, after startNewHand() and before any betting action.
     * Heads-up is a special case: the dealer posts the small blind (not dealer+1).
     * A blind is capped at the poster's remaining stack, which may force an all-in blind.
     */
    public void postBlinds() {
        boolean headsUp = (countLivePlayers() == 2);
        int sbSeat = headsUp ? dealerIndex : liveSeatOffset(dealerIndex, 1);
        int bbSeat = headsUp ? liveSeatOffset(dealerIndex, 1) : liveSeatOffset(dealerIndex, 2);

        Player sbPlayer = players.get(sbSeat);
        Player bbPlayer = players.get(bbSeat);

        int sbAmount = Math.min(smallBlind, sbPlayer.getStack());
        commitChips(sbPlayer, sbAmount);
        logger.log(new GameEvent.BlindPosted(sbPlayer.getSeatIndex(), "SB", sbAmount, sbPlayer.getStack()));

        int bbAmount = Math.min(bigBlind, bbPlayer.getStack());
        commitChips(bbPlayer, bbAmount);
        logger.log(new GameEvent.BlindPosted(bbPlayer.getSeatIndex(), "BB", bbAmount, bbPlayer.getStack()));
    }

    /**
     * Sets playerTurnIndex to the correct first actor for the CURRENT round.
     * Preflop: seat after the big blind (UTG), except heads-up where the dealer/SB acts first.
     * Postflop (flop/turn/river): first active seat left of the dealer.
     * If the computed seat is folded or all-in, advances to the next active seat.
     * Call this after postBlinds() (preflop) or after advanceRound() (postflop).
     */
    public void setFirstActor() {
        int startSeat;
        if (round == Round.PREFLOP) {
            startSeat = (countLivePlayers() == 2) ? dealerIndex : liveSeatOffset(dealerIndex, 3);
        } else {
            startSeat = liveSeatOffset(dealerIndex, 1);
        }
        playerTurnIndex = findFirstActiveSeatFrom(startSeat);
    }

    /** Walks forward from startSeat (inclusive) to the first seat whose player isActive(). */
    private int findFirstActiveSeatFrom(int startSeat) {
        int n = players.size();
        for (int step = 0; step < n; step++) {
            int seat = (startSeat + step) % n;
            if (players.get(seat).isActive()) {
                return seat;
            }
        }
        return startSeat; // nobody active; isBettingOver() will be true, caller should check that
    }

    /** Overload for actions that carry no explicit amount (CHECK, FOLD, CALL). */
    public void processAction(Action action) {
        processAction(action, 0);
    }

    public void processAction(Action action, int amount) {
        Player player = getPlayerToAct();
        int committedThisAction = 0; // chips actually moved from stack -> pot by this specific call

        switch (action) {
            case CHECK -> {
                if (player.getRoundBet() < currentBet) {
                    throw new IllegalStateException("Cannot check while a bet is outstanding");
                }
            }
            case FOLD -> foldPlayer(player);
            case CALL -> {
                int amountToCall = currentBet - player.getRoundBet();
                int actual = Math.max(0, Math.min(amountToCall, player.getStack()));
                if (actual > 0) {
                    commitChips(player, actual);
                }
                committedThisAction = actual;
            }
            case RAISE -> {
                if (amount <= currentBet) {
                    throw new IllegalStateException("Raise must exceed the current bet");
                }
                // A player can never raise TO more than their entire stack allows.
                int maxPossible = player.getRoundBet() + player.getStack();
                boolean isAllIn = (amount == maxPossible);
                int increment = amount - currentBet;
                if (increment < minRaise && !isAllIn) {
                    int minLegalTarget = currentBet + minRaise;
                    throw new IllegalStateException(
                            "Raise must be at least " + minLegalTarget
                                    + " (minimum raise increment is " + minRaise + ")");
                }
                int delta = amount - player.getRoundBet();
                commitChips(player, delta);
                committedThisAction = delta;
                // A short all-in raise (increment < minRaise) does not raise the bar for
                // subsequent players -- only a raise that meets or exceeds the minimum does.
                if (increment >= minRaise) {
                    minRaise = increment;
                }
            }
            default -> throw new IllegalArgumentException("Unhandled action: " + action);
        }

        logger.log(new GameEvent.ActionTaken(player.getSeatIndex(), round, action, committedThisAction, player.getStack()));

        advanceTurn();
    }


    /* -------------------------------------------------------------- */
    /* Side pots                                                       */
    /* -------------------------------------------------------------- */

    /**
     * Recomputes the main pot and any side pots from each player's totalCommitted.
     * Call this at showdown, or any time an all-in happens and you need to know the
     * current pot structure.
     *  If you only need the total, use totalPot() -- it does
     * not depend on this having been called.
     *
     * Algorithm: gather every distinct totalCommitted value (the "layers" at which someone
     * capped out), sorted ascending. Between two consecutive layers, every player who
     * committed at least the upper layer contributes (layerWidth) chips to that pot -- but
     * only players who haven't folded are eligible to win it. This naturally produces one
     * pot per all-in threshold, in O(n log n) for n players (n is bounded by table size,
     * so this is cheap even called every street).
     */
    public List<Pot> computeSidePots() {
        pots.clear();

        List<Player> contributors = new ArrayList<>();
        for (Player p : players) {
            if (p.getTotalCommitted() > 0) {
                contributors.add(p);
            }
        }
        if (contributors.isEmpty()) {
            return pots;
        }

        TreeSet<Integer> levels = new TreeSet<>();
        for (Player p : contributors) {
            levels.add(p.getTotalCommitted());
        }

        int previousLevel = 0;
        Pot previousPot = null;
        for (int level : levels) {
            int layerWidth = level - previousLevel;
            if (layerWidth <= 0) {
                continue;
            }

            int numContributors = 0;
            Set<Integer> eligibleSeats = new HashSet<>();
            for (Player p : contributors) {
                if (p.getTotalCommitted() >= level) {
                    numContributors++;
                    if (!p.isFolded()) {
                        eligibleSeats.add(p.getSeatIndex());
                    }
                }
            }

            int layerAmount = layerWidth * numContributors;

            // Merge into the previous pot if the eligible-winner set didn't change --
            // avoids splitting into pots that would always be won by the same people.
            if (previousPot != null && previousPot.getEligibleSeats().equals(eligibleSeats)) {
                previousPot.addAmount(layerAmount);
            } else {
                Pot pot = new Pot(layerAmount, eligibleSeats);
                pots.add(pot);
                previousPot = pot;
            }
            previousLevel = level;
        }

        return pots;
    }

    /**
     * Every chip committed this hand, across all pots and all contributors,
     * folded players included.
     *
     */
    public int totalPot() {
        int sum = 0;
        for (Player p : players) {
            sum += p.getTotalCommitted();
        }
        return sum;
    }

    /**
     * Awards a single pot to its (possibly tied) winners. pot.getAmount() may not
     * divide evenly among tiedWinners -- that's expected and not something the
     * chip system tries to prevent up front. Instead we split evenly with integer
     * division, then hand out the leftover chips one at a time following the
     * standard live-poker odd-chip rule: whoever is seated closest to the left of
     * the dealer button gets priority.
     */
    public void awardPot(Pot pot, List<Player> tiedWinners) {
        if (tiedWinners.isEmpty()) {
            throw new IllegalArgumentException("Cannot award a pot with no winners");
        }
        int share = pot.getAmount() / tiedWinners.size();
        int remainder = pot.getAmount() % tiedWinners.size();

        List<Player> ordered = new ArrayList<>(tiedWinners);
        int n = players.size();
        int firstToActSeat = (dealerIndex + 1) % n;
        ordered.sort(Comparator.comparingInt(
                p -> Math.floorMod(p.getSeatIndex() - firstToActSeat, n)));

        for (Player p : ordered) {
            p.addToStack(share);
        }
        for (int i = 0; i < remainder; i++) {
            ordered.get(i).addToStack(1);
        }

        List<Integer> winnerSeats = tiedWinners.stream().map(Player::getSeatIndex).toList();
        logger.log(new GameEvent.PotAwarded(pots.indexOf(pot), pot.getAmount(), winnerSeats));
    }

    /* -------------------------------------------------------------- */
    /* Hand resolution                                                  */
    /* -------------------------------------------------------------- */

    /**
     * Fold-out short circuit: if at most one player has not folded, that player wins
     * every chip currently in the pot, regardless of side-pot contribution levels --
     * once everyone else has folded, eligibility from computeSidePots() no longer
     * applies (there's nobody left to contest a side pot against). Call this after
     * every foldPlayer() call; if it returns a winner, the hand is over immediately
     * without evaluating any hands or reaching showdown.
     */
    public Optional<Player> checkFoldWin() {
        List<Player> remaining = new ArrayList<>();
        for (Player p : players) {
            if (!p.isFolded()) {
                remaining.add(p);
            }
        }
        if (remaining.size() != 1) {
            return Optional.empty();
        }
        int winnings = totalPot();
        Player winner = remaining.get(0);
        winner.addToStack(winnings);
        pots.clear();
        handComplete = true;

        // No Pot objects survive this path (side-pot layers were never separately
        // awarded), so this is logged as a single aggregate award rather than one
        // PotAwarded event per layer.
        logger.log(new GameEvent.PotAwarded(-1, winnings, List.of(winner.getSeatIndex())));
        logger.log(new GameEvent.HandEnded(true, List.of(winner.getSeatIndex())));

        return Optional.of(winner);
    }

    /**
     * Resolves the hand at showdown. Recomputes side pots, and for each pot independently
     * finds the best hand among that pot's eligible, non-folded players and awards it to
     * them (splitting ties per awardPot's odd-chip rule). A player eligible for the main
     * pot but not a side pot (because they went all-in for less) can win the main pot while
     * someone else wins the side pot -- eligibility is checked pot-by-pot, not decided once
     * for the whole hand. Assumes all 5 community cards have already been dealt. Returns the
     * distinct set of players who won at least one pot.
     */
    public List<Player> resolveShowdown() {
        computeSidePots();
        List<Player> allWinners = new ArrayList<>();

        for (Pot pot : pots) {
            List<Player> eligible = new ArrayList<>();
            for (Player p : players) {
                if (!p.isFolded() && pot.isEligible(p.getSeatIndex())) {
                    eligible.add(p);
                }
            }
            List<Player> potWinners = bestHandWinners(eligible);
            awardPot(pot, potWinners); // logs its own PotAwarded event
            for (Player w : potWinners) {
                if (!allWinners.contains(w)) {
                    allWinners.add(w);
                }
            }
        }

        handComplete = true;
        List<Integer> winnerSeats = allWinners.stream().map(Player::getSeatIndex).toList();
        logger.log(new GameEvent.HandEnded(false, winnerSeats));
        return allWinners;
    }

    /** Among the given eligible players, returns all whose best 7-card hand ties for highest. */
    private List<Player> bestHandWinners(List<Player> eligible) {
        int bestScore = Integer.MIN_VALUE;
        List<Player> winners = new ArrayList<>();
        for (Player p : eligible) {
            int score = HandEvaluator.evaluateBestHand(getEvaluableCards(p));
            if (score > bestScore) {
                bestScore = score;
                winners.clear();
                winners.add(p);
            } else if (score == bestScore) {
                winners.add(p);
            }
        }
        return winners;
    }

    /* -------------------------------------------------------------- */
    /* New hand setup                                                   */
    /* -------------------------------------------------------------- */

    public void startNewHand(int seed) {
        Arrays.fill(communityCards, -1);
        communityCardsDealt = 0;
        pots.clear();
        currentBet = 0;
        minRaise = bigBlind; // picks up any blind level change applied since the last hand
        round = Round.PREFLOP;
        handComplete = false;
        for (Player p : players) {
            p.resetForNewHand();
        }
        dealerIndex = liveSeatOffset(dealerIndex, 1);
        playerTurnIndex = dealerIndex;
        dealHoleCards(seed); // shuffle() restores a full 52-card deck, then deals

        logger.log(new GameEvent.HandStarted(dealerIndex, seed));
    }

    /* -------------------------------------------------------------- */
    /* Player-facing view / agent integration                          */
    /* -------------------------------------------------------------- */

    /**
     *  Equivalent to buildPlayerView(seatIndex, PlayerView.UNLIMITED)
     */
    public PlayerView buildPlayerView(int seatIndex) {
        return buildPlayerView(seatIndex, PlayerView.UNLIMITED);
    }

    /**
     * Builds the information-restricted view for the player in the given seat.
     * This is the ONLY channel through which a PokerAgent should see game state --
     * never pass a Player or GameState reference to an agent directly.
     * 
     * timeBudgetMillis is passed straight through the view. GameState neither
     * reads a clock nor enforces the budget; it only carries the number the turn
     * driver supplies, so the engine is time-free.
     */
    public PlayerView buildPlayerView(int seatIndex, long timeBudgetMillis) {
        Player self = players.get(seatIndex);

        List<OpponentInfo> infos = new ArrayList<>();
        for (Player p : players) {
            infos.add(new OpponentInfo(
                    p.getSeatIndex(),
                    p.getName(),
                    p.getStack(),
                    p.getRoundBet(),
                    p.getTotalCommitted(),
                    p.isFolded(),
                    p.isAllIn()
            ));
        }

        return new PlayerView(
                seatIndex,
                self.getHoleCards(),
                getCommunityCards(),
                round,
                dealerIndex,
                deck.remainingCards(),
                currentBet,
                currentBet + minRaise,
                totalPot(),
                infos,
                timeBudgetMillis
        );
    }

    /* -------------------------------------------------------------- */
    /* Getters                                                          */
    /* -------------------------------------------------------------- */

    public List<Player> getPlayers() { return players; }
    public List<Pot> getPots() { return pots; }
    public int getCurrentBet() { return currentBet; }
    public int getMinRaise() { return minRaise; }
    public int getDealerIndex() { return dealerIndex; }
    public int getLivePlayerCount() { return countLivePlayers(); }
    public int getPlayerTurnIndex() { return playerTurnIndex; }
    public Player getPlayerToAct() { return players.get(playerTurnIndex); }
    public Round getRound() { return round; }
    public int getSmallBlind() { return smallBlind; }
    public int getBigBlind() { return bigBlind; }
    public boolean isHandComplete() { return handComplete; }
}