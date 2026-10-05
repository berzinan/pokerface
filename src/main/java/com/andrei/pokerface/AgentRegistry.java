package com.andrei.pokerface;

import java.util.List;
import java.util.Locale;
import java.util.function.LongFunction;

/**
 * Maps a CLI bot key ("random", "montecarlo", ...) to a display name and a
 * seed-driven factory. This is the single place to touch when adding a new
 * reference bot to the benchmark harness -- RunSim itself never names a
 * concrete agent class.
 *
 * Factories take a seed even when the agent ignores it (AlwaysCallAgent,
 * FoldingAgent, AllInAgent are deterministic), so every bot has the same
 * shape and can be handed to BotRingStatsCollector.collectWithSeedRotation
 * without special-casing.
 */
public final class AgentRegistry {

    private AgentRegistry() {}

    /**
     * A resolved bot: stable display name plus the factory that builds it.
     * LongFunction, not IntFunction, so a BotSpec's factory can be handed
     * straight to NamedAgentFactory without an adapter.
     */
    public record BotSpec(String displayName, LongFunction<PokerAgent> factory) {}

    /** Keys accepted by RunSim --bots, in the order shown by --help. */
    public static final List<String> KEYS =
            List.of("montecarlo", "random", "caller", "folder", "allin");

    /**
     * @param key               a bot key (case-insensitive; a few aliases accepted)
     * @param monteCarloTrials  rollouts per decision, used only by the montecarlo bot
     * @throws IllegalArgumentException if the key is not recognised
     */
    public static BotSpec resolve(String key, int monteCarloTrials) {
        return switch (key.trim().toLowerCase(Locale.ROOT)) {
            case "montecarlo", "mc" ->
                    new BotSpec("MonteCarlo", seed -> new MonteCarloCallFoldAgent(monteCarloTrials, seed));
            case "random", "rand" ->
                    new BotSpec("Random", RandomAgent::new);
            case "caller", "alwayscall", "call" ->
                    new BotSpec("Caller", seed -> new AlwaysCallAgent());
            case "folder", "folding", "fold" ->
                    new BotSpec("Folder", seed -> new FoldingAgent());
            case "allin", "shove" ->
                    new BotSpec("AllIn", seed -> new AllInAgent());
            default -> throw new IllegalArgumentException(
                    "Unknown bot '" + key + "'. Known bots: " + String.join(", ", KEYS));
        };
    }
}