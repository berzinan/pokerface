package com.andrei.pokerface;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Command-line entry point for a human to play a tournament against bots. The
 * human half of the project; Benchmark is the bot-vs-bot half.
 *
 * Differs from Benchmark in three ways that all follow from a person being at
 * the table:
 *
 *   The move timer is real. TurnPolicy.timed(...) gives every seat the same
 *   budget, human and bot alike. A decision that overruns is replaced with
 *   check-if-free-else-fold.
 *
 *   Blinds escalate on the wall clock, not a virtual one. A benchmark has to
 *   fake elapsed time because bots play thousands of hands a second; here real
 *   minutes pass, so TournamentRunner's wall-clock overload is correct.
 *
 *   The tournament stops when the human is out. Letting the bots play on to a
 *   survivor while the person watches would be pointless, so the end condition
 *   includes the human's elimination.
 */
public final class Play {

    private Play() {}

    /** The human always sits at seat 0, so the end condition can name them. */
    private static final int HUMAN_SEAT = 0;

    private static final Set<String> VALUE_FLAGS = Set.of(
            "--bots", "--name", "--stack", "--seconds", "--level-minutes",
            "--sb", "--bb", "--trials", "--max-hands", "--seed", "--log");

    private static final Set<String> BOOL_FLAGS = Set.of("--help", "-h");

    private static final String USAGE = """
            Usage: Play [--bots <a,b,c>] [options]

            Bots: montecarlo | random | caller | folder | allin
                  Default: montecarlo,random,caller. A repeated key is suffixed,
                  so "--bots random,random" seats Random-1 and Random-2.

            Options:
              --name <text>          your display name            (default: You)
              --stack <n>            starting stack for everyone  (default 1000)
              --seconds <n>          move timer per decision      (default 30)
              --sb <n> --bb <n>      starting blinds              (default 10 / 20)
              --level-minutes <n>    real minutes per blind level (default 5)
              --max-hands <n>        safety cap on hands          (default 500)
              --trials <n>           MonteCarlo rollouts when untimed (default 300)
              --seed <n>             fixes the deal sequence; omit for a random game
              --log <path>           also write a machine-readable hand log
              --help

            At your turn you get the board, the pot, what you owe and every seat's
            stack, then a prompt. Commands: fold | check | call | raise <amount>
            | allin. An illegal command is rejected and re-prompted; running out
            of time checks if it is free and folds otherwise.

            Examples:
              (no arguments -- three bots, 30-second timer)
              --bots montecarlo,montecarlo --seconds 60 --name Andrei
              --bots allin --stack 200 --seconds 15
            """;

    public static void main(String[] args) {
        CliArgs cli;
        try {
            cli = CliArgs.parse(args, VALUE_FLAGS, BOOL_FLAGS);
        } catch (IllegalArgumentException e) {
            fail(e.getMessage());
            return;
        }

        if (cli.has("--help") || cli.has("-h")) {
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
        int startingStack = cli.positiveInt("--stack", 1000);
        int moveSeconds = cli.positiveInt("--seconds", 30);
        int smallBlind = cli.positiveInt("--sb", 10);
        int bigBlind = cli.positiveInt("--bb", 20);
        int levelMinutes = cli.positiveInt("--level-minutes", 5);
        int maxHands = cli.positiveInt("--max-hands", 500);
        String humanName = cli.str("--name", "You");

        if (bigBlind < smallBlind) {
            throw new IllegalArgumentException("--bb must be at least --sb");
        }

        List<NamedAgentFactory> botSpecs = AgentRegistry.resolveRoster(
                AgentRegistry.splitKeys(cli.str("--bots", "montecarlo,random,caller")), trials);

        // One root seed, two independent streams drawn from it. Seeding the deal
        // stream and the agent stream with the same value would correlate each
        // bot's randomness with specific deals -- harmless in a single game, but
        // the benchmark pipeline depends on those streams being independent and
        // there is no reason to model it wrongly here.
        Random rootSeeds = cli.has("--seed")
                ? new Random(cli.longValue("--seed", 0L))
                : new Random();
        Random agentSeeds = new Random(rootSeeds.nextLong());
        IntSupplier dealSeeds = new Random(rootSeeds.nextLong())::nextInt;

        // Seat 0 is the human; bots fill the rest in roster order.
        List<Player> players = new ArrayList<>(botSpecs.size() + 1);
        List<PokerAgent> agents = new ArrayList<>(botSpecs.size() + 1);
        players.add(new Player(HUMAN_SEAT, humanName, startingStack));
        agents.add(new HumanCliAgent(System.in, System.out, humanName));
        for (NamedAgentFactory spec : botSpecs) {
            players.add(new Player(players.size(), spec.name(), startingStack));
            agents.add(spec.factory().apply(agentSeeds.nextLong()));
        }

        // Console narration is the game itself -- hand boundaries, showdowns and
        // timer expiries. A file log is optional and machine-readable.
        ConsoleHandLogger console = new ConsoleHandLogger(players);
        FileHandLogger file = cli.has("--log") ? new FileHandLogger(cli.str("--log", "game.log")) : null;
        HandLogger logger = (file == null) ? console : new CompositeHandLogger(console, file);

        printSeating(players, moveSeconds, smallBlind, bigBlind, levelMinutes);

        TournamentResult result;
        try {
            result = TournamentRunner.run(
                    players,
                    agents,
                    BlindSchedule.increasing(
                            new BlindLevel(smallBlind, bigBlind), 0.33,
                            levelMinutes * 60_000L, Math.max(1, smallBlind / 2)),
                    endCondition(maxHands),
                    dealSeeds,
                    TurnPolicy.timed(moveSeconds * 1000L),
                    logger);
        } finally {
            if (file != null) {
                file.close();
            }
        }

        printSummary(result, players);
    }

    /**
     * Stops on any of three things: one player left, the human eliminated, or
     * the hand cap. The human's elimination is the addition that matters --
     * TournamentRunner would otherwise keep dealing until the bots resolved it
     * among themselves, with nobody watching.
     */
    private static TournamentEndCondition endCondition(int maxHands) {
        return (state, handsPlayed) ->
                state.getLivePlayerCount() <= 1
                        || state.getPlayers().get(HUMAN_SEAT).isEliminated()
                        || handsPlayed >= maxHands;
    }

    private static void printSeating(
            List<Player> players, int moveSeconds, int smallBlind, int bigBlind, int levelMinutes) {
        System.out.println("===== Pokerface =====");
        System.out.println("Blinds " + smallBlind + "/" + bigBlind
                + ", rising 33% every " + levelMinutes + " minute" + (levelMinutes == 1 ? "" : "s")
                + ". " + moveSeconds + " seconds per decision.");
        System.out.println("Seated:");
        for (Player p : players) {
            String marker = (p.getSeatIndex() == HUMAN_SEAT) ? "  <- you" : "";
            System.out.println("  seat " + p.getSeatIndex() + ": " + p.getName()
                    + " (" + p.getStack() + " chips)" + marker);
        }
    }

    private static void printSummary(TournamentResult result, List<Player> players) {
        Player human = players.get(HUMAN_SEAT);

        System.out.println();
        System.out.println("===== Tournament over after " + result.handsPlayed() + " hands =====");

        result.winner().ifPresentOrElse(
                winner -> System.out.println(winner.getSeatIndex() == HUMAN_SEAT
                        ? "You won, with every chip on the table."
                        : "Winner: " + winner.getName() + " (" + winner.getStack() + " chips)"),
                () -> System.out.println(human.isEliminated()
                        ? "You busted out. The bots were still playing."
                        : "Stopped at the hand cap with no single winner."));

        System.out.println("Final stacks:");
        for (Player p : players) {
            System.out.println("  " + p.getName() + ": " + p.getStack()
                    + (p.isEliminated() ? " (eliminated)" : ""));
        }
    }

    private static void fail(String message) {
        System.err.println("error: " + message);
        System.err.println();
        System.err.print(USAGE);
        System.exit(2);
    }
}