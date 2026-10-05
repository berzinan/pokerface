package com.andrei.pokerface;

/**
 * Everything that defines one ring-game benchmark run except the bots
 * themselves: table stakes, how many hands, and the three seeds that make the
 * whole run reproducible.
 *
 * This exists because BotRingStatsCollector.collect() would otherwise take a
 * dozen positional arguments, four of them same-typed ints meaning entirely
 * different things. Transposing totalHands and seatShuffleBlockSize compiles
 * cleanly and silently produces a different experiment -- grouping them here
 * at least puts the validation in one place and lets a caller name what it is
 * configuring.
 *
 * Seeds are raw longs rather than injected suppliers on purpose: the point of
 * the benchmark pipeline is that the same configuration reproduces the same
 * numbers, and a supplier is free to be System::nanoTime.
 */
public record RingBenchmark(
        int smallBlind,
        int bigBlind,
        int buyIn,
        int totalHands,
        int handsPerSeedBlock,     // hands before every seed-driven bot is rebuilt with a fresh seed
        int seatShuffleBlockSize,  // hands before the seat permutation is redrawn
        long dealSeed,             // seeds the per-hand deal seed stream
        long agentSeed,            // seeds the agent-reseeding stream
        long seatSeed              // seeds the seat permutation stream
) {

    public RingBenchmark {
        if (smallBlind <= 0 || bigBlind <= 0) {
            throw new IllegalArgumentException("Blinds must be positive");
        }
        if (bigBlind < smallBlind) {
            throw new IllegalArgumentException("Big blind cannot be smaller than the small blind");
        }
        if (buyIn <= 0) {
            throw new IllegalArgumentException("buyIn must be positive");
        }
        if (totalHands <= 0) {
            throw new IllegalArgumentException("totalHands must be positive");
        }
        if (handsPerSeedBlock <= 0) {
            throw new IllegalArgumentException("handsPerSeedBlock must be positive");
        }
        if (seatShuffleBlockSize <= 0) {
            throw new IllegalArgumentException("seatShuffleBlockSize must be positive");
        }
    }

    /**
     * 5/10 blinds, 1000 buy-in, reseeding and reshuffling every 100 hands.
     *
     * The block sizes matter more than they look. The seat permutation must be
     * redrawn far more often than once per rotation of n offsets: with only n
     * draws, relative ORDER between bots ("always acts immediately after the
     * calling station") is nowhere near averaged out, even though seat-INDEX
     * time can be balanced exactly. A deterministic cyclic shift provably
     * cannot fix this -- it preserves every bot's relative position to every
     * other bot on every offset by construction. 100 hands per draw gives
     * plenty of independent order permutations at any realistic hand budget.
     */
    public static RingBenchmark of(int totalHands) {
        return new RingBenchmark(5, 10, 1000, totalHands, 100, 100, 42L, 1337L, 12345L);
    }

    /** Same defaults, at a different table size. */
    public static RingBenchmark of(int smallBlind, int bigBlind, int buyIn, int totalHands) {
        return new RingBenchmark(smallBlind, bigBlind, buyIn, totalHands, 100, 100, 42L, 1337L, 12345L);
    }
}