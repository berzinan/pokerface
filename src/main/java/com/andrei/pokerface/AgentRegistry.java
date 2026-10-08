package com.andrei.pokerface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps a command-line bot key ("random", "montecarlo", ...) to a
 * NamedAgentFactory. This is the one place to touch when adding a reference
 * bot to the command-line tools -- neither Benchmark nor Play names a concrete
 * agent class.
 *
 * Returns NamedAgentFactory rather than some registry-specific type because
 * that is exactly what both collectors consume: a display name plus a
 * seed-to-agent function. Factories take a seed even where the agent ignores
 * it (AlwaysCallAgent, FoldingAgent and AllInAgent are deterministic), so
 * every bot has one shape and nothing downstream needs a special case.
 */
public final class AgentRegistry {

    private AgentRegistry() {}

    /** Default rollouts per decision for the Monte Carlo bot when untimed. */
    public static final int DEFAULT_MONTE_CARLO_TRIALS = 300;

    /** Accepted keys, in the order --help lists them. */
    public static final List<String> KEYS =
            List.of("montecarlo", "random", "caller", "folder", "allin");

    /**
     * @param key              a bot key (case-insensitive; a few aliases accepted)
     * @param monteCarloTrials rollouts per decision, used only by the montecarlo
     *                         bot, and only when it runs without a move timer --
     *                         a timed decision runs to its budget instead
     * @throws IllegalArgumentException if the key is not recognised
     */
    public static NamedAgentFactory resolve(String key, int monteCarloTrials) {
        return switch (key.trim().toLowerCase(Locale.ROOT)) {
            case "montecarlo", "mc" ->
                    new NamedAgentFactory("MonteCarlo", seed -> new MonteCarloCallFoldAgent(monteCarloTrials, seed));
            case "random", "rand" ->
                    new NamedAgentFactory("Random", RandomAgent::new);
            case "caller", "alwayscall", "call" ->
                    new NamedAgentFactory("Caller", seed -> new AlwaysCallAgent());
            case "folder", "folding", "fold" ->
                    new NamedAgentFactory("Folder", seed -> new FoldingAgent());
            case "allin", "shove" ->
                    new NamedAgentFactory("AllIn", seed -> new AllInAgent());
            default -> throw new IllegalArgumentException(
                    "Unknown bot '" + key + "'. Known bots: " + String.join(", ", KEYS));
        };
    }

    /**
     * Resolves a comma-separated roster, suffixing any name that appears more
     * than once: "random,random,caller" yields Random-1, Random-2 and Caller.
     *
     * The suffixing is not cosmetic. Both stats collectors pool results into a
     * map keyed by name and reject duplicates outright, so a roster with two
     * bots called "Random" would fail rather than quietly merge their samples.
     * A name that appears once is left alone, so a single montecarlo entry is
     * reported as "MonteCarlo" and not "MonteCarlo-1".
     */
    public static List<NamedAgentFactory> resolveRoster(List<String> keys, int monteCarloTrials) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("Roster cannot be empty");
        }

        Map<String, Integer> nameCounts = new HashMap<>();
        for (String key : keys) {
            nameCounts.merge(resolve(key, monteCarloTrials).name(), 1, Integer::sum);
        }

        Map<String, Integer> seen = new HashMap<>();
        List<NamedAgentFactory> roster = new ArrayList<>(keys.size());
        for (String key : keys) {
            NamedAgentFactory spec = resolve(key, monteCarloTrials);
            String name = spec.name();
            if (nameCounts.get(name) > 1) {
                name = name + "-" + seen.merge(name, 1, Integer::sum);
            }
            roster.add(new NamedAgentFactory(name, spec.factory()));
        }
        return roster;
    }

    /** Splits a "--bots a,b,c" value into keys, rejecting blank entries. */
    public static List<String> splitKeys(String csv) {
        List<String> keys = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                keys.add(trimmed);
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("No bots given");
        }
        return keys;
    }
}