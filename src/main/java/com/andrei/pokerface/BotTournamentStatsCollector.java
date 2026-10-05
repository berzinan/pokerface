package com.andrei.pokerface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Runs seat-rotated tournaments and aggregates win rate per bot NAME. This is
 * the top of the tournament pipeline, and the answer to "how would these bots
 * do in the conditions a human actually plays in".
 *
 * Read it alongside the ring pipeline rather than instead of it. Tournament
 * win rate is the realistic measure but a statistically terrible one: each
 * tournament yields a single bit of information after thousands of hands, so
 * separating two similar bots needs an enormous number of them. bb/100 from
 * BotRingStatsCollector converges far faster. Use the ring table to rank
 * bots; use this one to find out what those rankings mean under elimination
 * and escalating blinds, where survival and stack depth matter and chip EV
 * alone does not.
 *
 * Seat rotation advances by one position per tournament (t % n) rather than
 * being drawn in blocks like the ring collector. Tournament counts are orders
 * of magnitude smaller than hand counts, so a deterministic sweep covers the
 * seat space more evenly than random draws would at that budget.
 *
 * Every bot is rebuilt from its factory at the start of each tournament, so a
 * seed-driven bot is measured across many realizations of its own randomness
 * rather than one.
 *
 * This loop used to live in two places: TournamentBatchRunner ran it without
 * name aggregation, and this class ran it again with. Nothing called the
 * former, so it is gone.
 */
public final class BotTournamentStatsCollector {

    private BotTournamentStatsCollector() {}

    /** Convenience overload: untimed, no logging. */
    public static BotTournamentReport collect(List<NamedAgentFactory> bots, TournamentBenchmark config) {
        return collect(bots, config, TurnPolicy.UNTIMED, HandLogger.NO_OP);
    }

    /**
     * @param bots   roster to compare; names must be distinct, since results
     *               are pooled by name
     * @param config tournament count, stakes, schedule and seeds
     * @param policy per-decision timing; UNTIMED for a benchmark, since a
     *               deadline firing on a GC pause would corrupt the sample
     * @param logger event subscriber for the whole run (HandLogger.NO_OP if unused)
     */
    public static BotTournamentReport collect(
            List<NamedAgentFactory> bots,
            TournamentBenchmark config,
            TurnPolicy policy,
            HandLogger logger) {

        if (bots == null || bots.size() < 2) {
            throw new IllegalArgumentException("Need at least two bots to compare");
        }
        requireDistinctNames(bots);

        int n = bots.size();
        IntSupplier dealSeeds = new Random(config.dealSeed())::nextInt;
        Random agentSeeds = new Random(config.agentSeed());

        Map<String, Integer> winsByName = new LinkedHashMap<>();
        Map<String, Integer> enteredByName = new LinkedHashMap<>();
        for (NamedAgentFactory b : bots) {
            winsByName.put(b.name(), 0);
            enteredByName.put(b.name(), 0);
        }

        long inconclusive = 0;

        for (int t = 0; t < config.tournamentCount(); t++) {
            int offset = t % n;

            List<Player> players = new ArrayList<>(n);
            List<PokerAgent> agents = new ArrayList<>(n);
            List<String> seatOwner = new ArrayList<>(n);
            for (int seat = 0; seat < n; seat++) {
                NamedAgentFactory owner = bots.get((seat + offset) % n);
                players.add(new Player(seat, owner.name(), config.startingStack()));
                agents.add(owner.factory().apply(agentSeeds.nextInt()));
                seatOwner.add(owner.name());
                enteredByName.merge(owner.name(), 1, Integer::sum);
            }

            TournamentResult result = TournamentRunner.run(
                    players, agents,
                    config.blindSchedule(),
                    config.endCondition(),
                    dealSeeds,
                    policy,
                    virtualClock(config.virtualMillisPerHand()),
                    logger);

            if (result.winner().isPresent()) {
                winsByName.merge(seatOwner.get(result.winner().get().getSeatIndex()), 1, Integer::sum);
            } else {
                inconclusive++;
            }
        }

        List<BotTournamentStatLine> lines = new ArrayList<>(n);
        for (NamedAgentFactory b : bots) {
            lines.add(BotTournamentStatLine.of(
                    b.name(), winsByName.get(b.name()), enteredByName.get(b.name())));
        }
        return new BotTournamentReport(lines, inconclusive);
    }

    /**
     * A clock that advances a fixed number of virtual milliseconds on every
     * read, starting at zero. TournamentRunner reads it once before the first
     * hand and once after each, so after h hands the schedule sees exactly
     * h * step elapsed -- blind escalation driven by hands played, wearing the
     * interface of elapsed time.
     *
     * A fresh one per tournament, so every tournament starts at level 0.
     */
    private static LongSupplier virtualClock(long millisPerRead) {
        long[] now = {0L};
        return () -> {
            long current = now[0];
            now[0] += millisPerRead;
            return current;
        };
    }

    /**
     * Results are pooled into a map keyed by bot name, so two bots sharing a
     * name would silently merge their wins and entries. Fail loudly instead.
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