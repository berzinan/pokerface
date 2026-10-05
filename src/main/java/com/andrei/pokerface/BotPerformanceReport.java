package com.andrei.pokerface;

import java.util.Comparator;
import java.util.List;

/**
 * Ring-game comparison across multiple named bots, with seat-position bias
 * cancelled by rotation. This is the primary instrument for ranking your own
 * bots against each other: bb/100 with an error bar, from independent
 * fixed-stack hands.
 */
public record BotPerformanceReport(List<BotStatLine> lines) {

    public BotPerformanceReport {
        lines = List.copyOf(lines);
    }

    public BotStatLine forBot(String name) {
        return lines.stream()
                .filter(l -> l.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No stats for bot: " + name));
    }

    /**
     * Human-readable table, ranked best bb/100 to worst.
     *
     * Read the StdErr column before the bb/100 column. Two bots whose
     * intervals overlap have not been separated by this run, however
     * different their headline numbers look -- that is what the error bar is
     * for. The latency columns are a separate axis entirely: they say nothing
     * about strength, only about whether a bot could survive a move timer.
     */
    public String formatTable() {
        List<BotStatLine> ranked = lines.stream()
                .sorted(Comparator.comparingDouble(BotStatLine::bbPer100).reversed())
                .toList();

        boolean anyLatency = lines.stream().anyMatch(l -> l.decisions() > 0);

        StringBuilder sb = new StringBuilder();
        if (anyLatency) {
            sb.append(String.format("%-20s %10s %12s %12s %12s %12s%n",
                    "Bot", "Hands", "bb/100", "+/- StdErr", "Mean ms", "Max ms"));
            for (BotStatLine line : ranked) {
                sb.append(String.format("%-20s %10d %12.2f %12.2f %12.3f %12.3f%n",
                        line.name(), line.handsPlayed(), line.bbPer100(), line.bbPer100StdError(),
                        line.meanDecisionMillis(), line.maxDecisionMillis()));
            }
        } else {
            sb.append(String.format("%-20s %10s %12s %12s%n",
                    "Bot", "Hands", "bb/100", "+/- StdErr"));
            for (BotStatLine line : ranked) {
                sb.append(String.format("%-20s %10d %12.2f %12.2f%n",
                        line.name(), line.handsPlayed(), line.bbPer100(), line.bbPer100StdError()));
            }
        }
        return sb.toString();
    }
}