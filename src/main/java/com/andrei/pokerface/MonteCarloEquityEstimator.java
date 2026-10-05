package com.andrei.pokerface;

import java.util.random.RandomGenerator;

/**
 * Estimates a hand's equity by simulation: deal the opponents random hole
 * cards and fill out the board at random, score everyone, and average the
 * result over many trials.
 *
 * Three things govern the cost, and all three matter because this is the
 * hottest code in the benchmark pipeline -- a decision is thousands of
 * trials, a hand is several decisions, a benchmark is hundreds of thousands
 * of hands.
 *
 * Partial shuffle. A trial needs 2*numOpponents + cardsNeeded cards, which
 * is nine preflop against two opponents. The old code ran a full Fisher-Yates
 * over all ~45 remaining cards every trial and used the first few. The loop
 * here stops after the cards it actually draws, which is still a uniformly
 * random sample of them.
 *
 * RandomGenerator, not Random. java.util.Random is thread-safe via a
 * compare-and-swap on an AtomicLong per call, and nothing here is concurrent.
 * Callers should pass a SplittableRandom. The parameter type is the
 * RandomGenerator interface, so an existing Random still works unchanged.
 *
 * Optional deadline. estimateEquity can stop early when a wall-clock
 * deadline passes, so a timed agent spends its whole move budget and no more
 * instead of running a fixed trial count that is either wasteful or too slow.
 * The clock is consulted every CLOCK_CHECK_INTERVAL trials rather than every
 * trial, since a nanoTime call is a meaningful fraction of a single trial's
 * cost.
 */
public final class MonteCarloEquityEstimator {

    private MonteCarloEquityEstimator() {}

    /** No deadline: run the full trial count. */
    public static final long NO_DEADLINE = Long.MAX_VALUE;

    /**
     * Trials between clock reads. A power of two so the check is a cheap mask,
     * and large enough that nanoTime is negligible against the trials it
     * guards, but small enough to not overshoot a deadline noticeably.
     */
    private static final int CLOCK_CHECK_INTERVAL = 256;

    /** Run exactly this many trials, with no deadline. */
    public static double estimateEquity(
            int[] holeCards, int[] communityCards, int numOpponents, int trials, RandomGenerator random) {
        return estimateEquity(holeCards, communityCards, numOpponents, trials, random, NO_DEADLINE);
    }

    /**
     * @param maxTrials     upper bound on trials; the deadline may cut it short
     * @param deadlineNanos a System.nanoTime() value to stop at, or NO_DEADLINE
     * @return the mean trial score: 1.0 for an outright win, 0.0 for a loss,
     *         and 1/(1+tiedOpponents) for a tie split
     */
    public static double estimateEquity(
            int[] holeCards,
            int[] communityCards,
            int numOpponents,
            int maxTrials,
            RandomGenerator random,
            long deadlineNanos) {

        if (maxTrials <= 0) {
            throw new IllegalArgumentException("maxTrials must be positive");
        }
        // With nobody left to beat, every trial would score 1.0. Skip the work.
        if (numOpponents <= 0) {
            return 1.0;
        }

        // Cards not already visible to us, built once per call.
        boolean[] used = new boolean[52];
        for (int card : holeCards) {
            used[card] = true;
        }
        for (int card : communityCards) {
            used[card] = true;
        }
        int[] remainingCards = new int[52 - holeCards.length - communityCards.length];
        int writeIndex = 0;
        for (int card = 0; card < 52; card++) {
            if (!used[card]) {
                remainingCards[writeIndex++] = card;
            }
        }

        int knownCount = communityCards.length;
        int cardsNeeded = 5 - knownCount;
        int drawCount = 2 * numOpponents + cardsNeeded;
        if (drawCount > remainingCards.length) {
            throw new IllegalArgumentException(
                    "Not enough cards left for " + numOpponents + " opponents and a full board");
        }

        // Reused across trials; nothing here escapes.
        int[] trialCommunity = new int[5];
        int[] ownHand = new int[holeCards.length + 5];
        int[] opponentHand = new int[2 + 5];
        int[] opponentRanks = new int[numOpponents];
        System.arraycopy(holeCards, 0, ownHand, 0, holeCards.length);
        System.arraycopy(communityCards, 0, trialCommunity, 0, knownCount);

        double totalScore = 0.0;
        int trialsRun = 0;

        for (int trial = 0; trial < maxTrials; trial++) {
            if (deadlineNanos != NO_DEADLINE
                    && (trial & (CLOCK_CHECK_INTERVAL - 1)) == 0
                    && trial > 0
                    && System.nanoTime() >= deadlineNanos) {
                break;
            }

            partialShuffle(remainingCards, drawCount, random);

            // Board first, so the slice an opponent reads is contiguous.
            for (int i = 0; i < cardsNeeded; i++) {
                trialCommunity[knownCount + i] = remainingCards[i];
            }
            System.arraycopy(trialCommunity, 0, ownHand, holeCards.length, 5);
            int ownRank = HandEvaluator.evaluateBestHand(ownHand);

            System.arraycopy(trialCommunity, 0, opponentHand, 2, 5);
            for (int i = 0; i < numOpponents; i++) {
                opponentHand[0] = remainingCards[cardsNeeded + 2 * i];
                opponentHand[1] = remainingCards[cardsNeeded + 2 * i + 1];
                opponentRanks[i] = HandEvaluator.evaluateBestHand(opponentHand);
            }

            totalScore += scoreTrial(ownRank, opponentRanks);
            trialsRun++;
        }

        return totalScore / trialsRun;
    }

    /**
     * Places a uniformly random sample of drawCount cards into positions
     * 0..drawCount-1. Each step swaps position i with a uniform pick from the
     * untouched tail, which is Fisher-Yates stopped early -- the prefix has
     * exactly the distribution a full shuffle would have produced.
     *
     * The array stays a permutation of the same cards between trials, so no
     * reset is needed.
     */
    private static void partialShuffle(int[] cards, int drawCount, RandomGenerator random) {
        for (int i = 0; i < drawCount; i++) {
            int j = i + random.nextInt(cards.length - i);
            int temp = cards[i];
            cards[i] = cards[j];
            cards[j] = temp;
        }
    }

    /**
     * One trial's contribution: 1.0 for an outright win, 0.0 for a loss, and
     * an even split among everyone holding the best hand on a tie -- so a
     * three-way tie pays 1/3, not 1/2.
     */
    private static double scoreTrial(int ownRank, int[] opponentRanks) {
        int bestOppScore = Integer.MIN_VALUE;
        for (int rank : opponentRanks) {
            if (rank > bestOppScore) {
                bestOppScore = rank;
            }
        }
        if (ownRank > bestOppScore) {
            return 1.0;
        }
        if (ownRank < bestOppScore) {
            return 0.0;
        }
        int tiedOpponents = 0;
        for (int rank : opponentRanks) {
            if (rank == bestOppScore) {
                tiedOpponents++;
            }
        }
        return 1.0 / (1 + tiedOpponents);
    }
}