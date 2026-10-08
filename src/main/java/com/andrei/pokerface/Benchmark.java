package com.andrei.pokerface;

import java.util.List;
import java.util.Set;

/**
 * Command-line entry point for comparing bots against each other. The
 * bot-vs-bot half of the project; Play is the human half.
 *
 * Two modes, answering two different questions, and the distinction matters
 * more than it looks:
 *
 *   ring -- fixed-stack hands, reported as bb/100 with a standard error. This
 *   is what actually separates two bots. Every hand is an independent sample
 *   of decision quality, so it converges fast.
 *
 *   tournament -- escalating blinds and elimination, reported as win rate.
 *   This is what a human would experience, but it is a poor instrument for
 *   ranking: a whole tournament yields one bit of information, so telling two
 *   similar bots apart needs an enormous number of them.
 *
 * Run ring to decide which bot is better. Run tournament to find out what that
 * difference is worth under survival pressure. Both default on, since the
 * contrast is the point.
 *
 * Neither mode enforces a move timer (TurnPolicy.UNTIMED). Decision latency is
 * still measured and reported, but a deadline firing on a GC pause would
 * inject a forced fold into the chip samples and make a run unreproducible.
 */
public final class Benchmark {

    private Benchmark() {}

    private static final Set<String> VALUE_FLAGS = Set.of(
            "--bots", "--mode", "--trials",
            "--hands", "--sb", "--bb", "--buyin", "--seed-block", "--shuffle-block",
            "--tournaments", "--stack", "--virtual-ms",
            "--deal-seed", "--agent-seed", "--seat-seed");

    private static final Set<String> BOOL_FLAGS = Set.of("--help", "-h");

    private static final String USAGE = """
            Usage: Benchmark --bots <a,b,c> [options]

            Bots: montecarlo | random | caller | folder | allin
                  At least two. A repeated key is suffixed automatically, so
                  "--bots random,random" yields Random-1 and Random-2.

            Mode:
              --mode <ring|tournament|both>   default: both

            Ring options (bb/100, the metric that separates bots):
              --hands <n>            total hands                    (default 20000)
              --sb <n> --bb <n>      blinds                         (default 5 / 10)
              --buyin <n>            per-hand stack reset           (default 1000)
              --seed-block <n>       hands between agent reseeds    (default 100)
              --shuffle-block <n>    hands between seat shuffles    (default 100)

            Tournament options (win rate under elimination):
              --tournaments <n>      tournaments to play            (default 200)
              --stack <n>            starting stack                 (default 1000)
              --virtual-ms <n>       virtual ms per hand, which is what drives
                                     blind escalation               (default 2000)

            Shared:
              --trials <n>           MonteCarlo rollouts per decision (default 300)
              --deal-seed <n>        seeds the deal stream          (default 42)
              --agent-seed <n>       seeds agent reseeding          (default 1337)
              --seat-seed <n>        seeds seat shuffling, ring only (default 12345)
              --help

            Examples:
              --bots montecarlo,caller
              --bots montecarlo,random,random,random --mode ring --hands 200000
              --bots montecarlo,caller,folder --mode tournament --tournaments 1000
            """;

    public static void main(String[] args) {
        CliArgs cli;
        try {
            cli = CliArgs.parse(args, VALUE_FLAGS, BOOL_FLAGS);
        } catch (IllegalArgumentException e) {
            fail(e.getMessage());
            return;
        }

        if (cli.has("--help") || cli.has("-h") || !cli.has("--bots")) {
            System.out.print(USAGE);
            return;
        }

        try {
            run(cli);
        } catch (IllegalArgumentException e) {
            fail(e.getMessage());
        }
    }

    private static void run(CliArgs cli) {
        int trials = cli.positiveInt("--trials", AgentRegistry.DEFAULT_MONTE_CARLO_TRIALS);
        List<NamedAgentFactory> bots = AgentRegistry.resolveRoster(
                AgentRegistry.splitKeys(cli.str("--bots", "")), trials);

        if (bots.size() < 2) {
            throw new IllegalArgumentException("--bots needs at least two entries to compare");
        }

        String mode = cli.str("--mode", "both").trim().toLowerCase();
        boolean ring = mode.equals("ring") || mode.equals("both");
        boolean tournament = mode.equals("tournament") || mode.equals("both");
        if (!ring && !tournament) {
            throw new IllegalArgumentException("--mode must be ring, tournament or both (got '" + mode + "')");
        }

        long dealSeed = cli.longValue("--deal-seed", 42L);
        long agentSeed = cli.longValue("--agent-seed", 1337L);

        if (ring) {
            // Built through the canonical constructor rather than a convenience
            // factory: every field is coming from a flag anyway, and naming them
            // here keeps the argument order honest.
            RingBenchmark config = new RingBenchmark(
                    cli.positiveInt("--sb", 5),
                    cli.positiveInt("--bb", 10),
                    cli.positiveInt("--buyin", 1000),
                    cli.positiveInt("--hands", 20_000),
                    cli.positiveInt("--seed-block", 100),
                    cli.positiveInt("--shuffle-block", 100),
                    dealSeed,
                    agentSeed,
                    cli.longValue("--seat-seed", 12345L));

            System.out.println();
            System.out.println("== Ring game: " + config.totalHands() + " hands, "
                    + bots.size() + " seats, " + config.smallBlind() + "/" + config.bigBlind()
                    + " blinds, " + config.buyIn() + " buy-in ==");
            System.out.print(BotRingStatsCollector.collect(bots, config).formatTable());
            System.out.println();
            System.out.println("Read StdErr before bb/100: two bots whose intervals overlap have");
            System.out.println("not been separated by this run, however different the headlines look.");
            System.out.println("Mean/Max ms say nothing about strength -- only whether a bot could");
            System.out.println("survive a move timer.");
        }

        if (tournament) {
            int tournamentCount = cli.positiveInt("--tournaments", 200);
            int startingStack = cli.positiveInt("--stack", 1000);
            long virtualMillisPerHand = cli.longValue("--virtual-ms", 2_000L);

            TournamentBenchmark config = new TournamentBenchmark(
                    tournamentCount,
                    startingStack,
                    virtualMillisPerHand,
                    BlindSchedule.increasing(new BlindLevel(10, 20), 0.33, 60_000L, 5),
                    TournamentEndCondition.LAST_PLAYER_STANDING.orAfter(10_000),
                    dealSeed,
                    agentSeed);

            System.out.println();
            System.out.println("== Tournaments: " + tournamentCount + " runs, "
                    + bots.size() + " seats, " + startingStack + " starting stack, "
                    + "blinds up every " + (60_000L / virtualMillisPerHand) + " hands ==");
            System.out.print(BotTournamentStatsCollector.collect(bots, config).formatTable());
            System.out.println();
            System.out.println("A non-zero inconclusive count means tournaments are hitting the hand");
            System.out.println("cap: blinds are escalating too slowly for the starting stack. Lower");
            System.out.println("--stack or raise --virtual-ms.");
        }
    }

    private static void fail(String message) {
        System.err.println("error: " + message);
        System.err.println();
        System.err.print(USAGE);
        System.exit(2);
    }
}