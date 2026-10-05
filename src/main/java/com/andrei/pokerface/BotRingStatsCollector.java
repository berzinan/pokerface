package com.andrei.pokerface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntSupplier;

/**
 * Runs RingGameRunner across seat-randomized, periodically-reseeded
 * sub-batches and aggregates results per bot NAME rather than per seat index.
 * This is the top of the benchmark pipeline: hand it a roster and a
 * RingBenchmark, get back a ranked bb/100 table with error bars.
 *
 * Two independent block sizes, for two different biases:
 *
 * seatShuffleBlockSize -- the seat permutation is redrawn this often.
 * Randomization must happen far more frequently than once per rotation of n
 * offsets: with only n permutation draws (n = bot count), relative ORDER
 * between bots (e.g. "always acts immediately after the calling station") is
 * nowhere near averaged out by the time totalHands is exhausted, even though
 * seat-INDEX time can be balanced exactly by a deterministic cyclic shift. A
 * cyclic shift provably CANNOT fix the order problem -- it preserves every
 * bot's relative position to every other bot on every offset, by
 * construction. Redrawing independently every seatShuffleBlockSize hands
 * decouples the number of order-permutation draws from the bot count.
 *
 * handsPerSeedBlock -- every bot is rebuilt from its factory with a fresh
 * seed this often. A seed-driven bot (RandomAgent, MonteCarloCallFoldAgent)
 * run from a single seed for the whole batch is being measured on one
 * realization of its own randomness, not on its policy. Rebuilding
 * periodically averages that out. Deterministic bots ignore the seed
 * entirely, so this costs them nothing -- which is why there is no separate
 * non-reseeding entry point. For the old "build once, never reseed"
 * behaviour, set handsPerSeedBlock equal to totalHands.
 *
 * Every seed is drawn from a seeded Random, so the same RingBenchmark
 * reproduces the same table exactly.
 */
public final class BotRingStatsCollector {

    private BotRingStatsCollector() {}

    static List<Integer> shuffledSeatAssignment(int n, Random random) {
        List<Integer> assignment = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            assignment.add(i);
        }
        Collections.shuffle(assignment, random);
        return assignment;
    }

    /** Convenience overload: untimed, no logging. */
    public static BotPerformanceReport collect(List<NamedAgentFactory> bots, RingBenchmark config) {
        return collect(bots, config, TurnPolicy.UNTIMED, HandLogger.NO_OP);
    }

    /**
     * @param bots   roster to compare; names must be distinct, since results are
     *               pooled by name and two bots sharing one would silently merge
     * @param config stakes, hand budget, block sizes and seeds
     * @param policy per-decision timing; UNTIMED measures latency without
     *               enforcing a deadline, which is what a benchmark wants
     * @param logger event subscriber for the whole run (HandLogger.NO_OP if unused)
     */
    public static BotPerformanceReport collect(
            List<NamedAgentFactory> bots,
            RingBenchmark config,
            TurnPolicy policy,
            HandLogger logger) {

        if (bots == null || bots.size() < 2) {
            throw new IllegalArgumentException("Need at least two bots to compare");
        }
        requireDistinctNames(bots);

        int n = bots.size();
        Random seatRandom = new Random(config.seatSeed());
        IntSupplier dealSeeds = new Random(config.dealSeed())::nextInt;
        IntSupplier agentSeeds = new Random(config.agentSeed())::nextInt;

        Map<String, List<Double>> bbSamplesByName = new LinkedHashMap<>();
        Map<String, RingGameResult.LatencyStats> latencyByName = new LinkedHashMap<>();
        for (NamedAgentFactory b : bots) {
            bbSamplesByName.put(b.name(), new ArrayList<>());
            latencyByName.put(b.name(), RingGameResult.LatencyStats.EMPTY);
        }

        int handsRemaining = config.totalHands();
        while (handsRemaining > 0) {
            int handsThisSeedBlock = Math.min(config.handsPerSeedBlock(), handsRemaining);

            // One fresh agent instance per bot for this whole seed block.
            List<PokerAgent> freshAgents = new ArrayList<>(n);
            for (NamedAgentFactory b : bots) {
                freshAgents.add(b.factory().apply(agentSeeds.getAsInt()));
            }

            int seedBlockRemaining = handsThisSeedBlock;
            while (seedBlockRemaining > 0) {
                int handsThisShuffleBlock = Math.min(config.seatShuffleBlockSize(), seedBlockRemaining);
                seedBlockRemaining -= handsThisShuffleBlock;

                List<Integer> seatAssignment = shuffledSeatAssignment(n, seatRandom);

                List<Player> players = new ArrayList<>(n);
                List<PokerAgent> agents = new ArrayList<>(n);
                List<String> seatOwner = new ArrayList<>(n);
                for (int seat = 0; seat < n; seat++) {
                    int botIndex = seatAssignment.get(seat);
                    NamedAgentFactory owner = bots.get(botIndex);
                    players.add(new Player(seat, owner.name(), config.buyIn()));
                    agents.add(freshAgents.get(botIndex));
                    seatOwner.add(owner.name());
                }

                RingGameResult result = RingGameRunner.runBatch(
                        players, agents,
                        config.smallBlind(), config.bigBlind(), config.buyIn(),
                        handsThisShuffleBlock, dealSeeds, policy, logger);

                for (int[] handNet : result.netChipsPerHand()) {
                    for (int seat = 0; seat < n; seat++) {
                        double bb = handNet[seat] / (double) config.bigBlind();
                        bbSamplesByName.get(seatOwner.get(seat)).add(bb);
                    }
                }
                for (int seat = 0; seat < n; seat++) {
                    String owner = seatOwner.get(seat);
                    latencyByName.put(owner, latencyByName.get(owner).plus(result.latency(seat)));
                }
            }

            handsRemaining -= handsThisSeedBlock;
        }

        List<BotStatLine> lines = new ArrayList<>(n);
        for (NamedAgentFactory b : bots) {
            lines.add(BotStatLine.fromSamples(
                    b.name(), bbSamplesByName.get(b.name()), latencyByName.get(b.name())));
        }
        return new BotPerformanceReport(lines);
    }

    /**
     * Results are pooled into a map keyed by bot name, so two bots sharing a
     * name would silently merge their samples and report double the hands.
     * Fail loudly instead.
     */
    private static void requireDistinctNames(List<NamedAgentFactory> bots) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (NamedAgentFactory b : bots) {
            if (seen.merge(b.name(), 1, Integer::sum) > 1) {
                throw new IllegalArgumentException(
                        "Duplicate bot name '" + b.name() + "' -- results are pooled by name, "
                                + "so every bot in a roster needs a distinct one");
            }
        }
    }
}