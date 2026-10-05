package com.andrei.pokerface;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * THROWAWAY measurement harness -- delete after use.
 *
 * Measures hands per second across several table configurations, because
 * throughput is dominated by which agents are seated rather than by the
 * engine. Quoting one figure without saying which configuration produced it
 * is meaningless: the spread between trivial bots and Monte Carlo bots here
 * is well over an order of magnitude.
 *
 * Every configuration is warmed up before being timed. A cold JVM runs the
 * first batch interpreted while the JIT is still compiling, which understates
 * throughput by a large factor.
 *
 * Single-threaded throughout -- no configuration here uses more than one core.
 */
public final class MeasureThroughput {

    private MeasureThroughput() {}

    private static final int WARMUP_HANDS = 2_000;
    private static final int TIMED_HANDS  = 20_000;

    public static void main(String[] args) {
        System.out.println("JVM: " + System.getProperty("java.version")
                + "   cores: " + Runtime.getRuntime().availableProcessors()
                + "   (single-threaded workload)");
        System.out.println();
        System.out.printf("%-34s %8s %14s %14s%n",
                "Configuration", "Seats", "hands/sec", "ms/hand");

        // Cheapest possible: every hand runs to showdown, so this measures
        // engine overhead plus showdown evaluation with negligible agent cost.
        measure("AlwaysCall x2", () -> List.of(new AlwaysCallAgent(), new AlwaysCallAgent()));
        measure("AlwaysCall x6", () -> {
            List<PokerAgent> agents = new ArrayList<>();
            for (int i = 0; i < 6; i++) agents.add(new AlwaysCallAgent());
            return agents;
        });

        // Folding ends most hands preflop via the fold-win path, which is the
        // shortest route through a hand -- the flattering end of the range.
        measure("Folding x6", () -> {
            List<PokerAgent> agents = new ArrayList<>();
            for (int i = 0; i < 6; i++) agents.add(new FoldingAgent());
            return agents;
        });

        // Exercises every branch: folds, calls, raises, side pots, all-ins.
        measure("Random x6", () -> {
            List<PokerAgent> agents = new ArrayList<>();
            for (int i = 0; i < 6; i++) agents.add(new RandomAgent(100 + i));
            return agents;
        });

        // Realistic mixed table: one thinking bot against cheap opposition.
        measure("MonteCarlo-300 + Random x5", () -> {
            List<PokerAgent> agents = new ArrayList<>();
            agents.add(new MonteCarloCallFoldAgent(300, 7));
            for (int i = 0; i < 5; i++) agents.add(new RandomAgent(200 + i));
            return agents;
        });

        // The heavy end: every seat running rollouts.
        measure("MonteCarlo-300 x3", () -> List.of(
                new MonteCarloCallFoldAgent(300, 1),
                new MonteCarloCallFoldAgent(300, 2),
                new MonteCarloCallFoldAgent(300, 3)));

        System.out.println();
        System.out.println("Throughput is set by the agents, not the engine. Quote the");
        System.out.println("configuration alongside any figure taken from this table.");
    }

    private static void measure(String label, java.util.function.Supplier<List<PokerAgent>> agentFactory) {
        List<PokerAgent> warmupAgents = agentFactory.get();
        RingGameRunner.runBatch(
                seats(warmupAgents.size()), warmupAgents,
                5, 10, 1000, WARMUP_HANDS, seeds());

        List<PokerAgent> agents = agentFactory.get();
        List<Player> players = seats(agents.size());

        long start = System.nanoTime();
        RingGameResult result = RingGameRunner.runBatch(
                players, agents, 5, 10, 1000, TIMED_HANDS, seeds());
        long elapsedNanos = System.nanoTime() - start;

        double seconds = elapsedNanos / 1e9;
        double handsPerSec = result.handsPlayed() / seconds;
        double msPerHand = (seconds * 1000.0) / result.handsPlayed();

        System.out.printf("%-34s %8d %14.0f %14.3f%n",
                label, agents.size(), handsPerSec, msPerHand);
    }

    private static List<Player> seats(int count) {
        List<Player> players = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            players.add(new Player(i, "P" + i, 1000));
        }
        return players;
    }

    private static IntSupplier seeds() {
        AtomicInteger counter = new AtomicInteger(0);
        return counter::getAndIncrement;
    }
}
