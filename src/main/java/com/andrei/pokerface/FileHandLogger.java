package com.andrei.pokerface;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;

/**
 * Writes one human-readable line per event to a file -- for post-session
 * debugging of a HandRunner loop. Opens the file once at construction and
 * keeps the writer open for the lifetime of the logger; call close() (or
 * use try-with-resources / try-with-a-GameState-scoped-block) when the
 * session ends, or buffered lines may never reach disk.
 *
 * Default constructor overwrites any existing file at the given path
 * (append=false) -- re-running a session doesn't require manually clearing
 * the old log first. Pass append=true to accumulate across multiple
 * sessions instead.
 *
 * Flushes after every line. That is a correctness-over-throughput choice:
 * for a debugging log, a session that crashes mid-hand should still leave a
 * readable file. For bulk simulation (thousands of hands/sec) use
 * InMemoryHandLogger instead and only attach a FileHandLogger when
 * inspecting a specific hand.
 */
public class FileHandLogger implements HandLogger, AutoCloseable {
    private final PrintWriter writer;

    public FileHandLogger(String path) {
        this(path, false);
    }

    public FileHandLogger(String path, boolean append) {
        try {
            this.writer = new PrintWriter(new FileWriter(path, append));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open log file: " + path, e);
        }
    }

    @Override
    public void log(GameEvent event) {
        writer.println(format(event));
        writer.flush();
    }

    private String format(GameEvent event) {
        return switch (event) {
            case GameEvent.DecisionTimed e -> "DECISION   seat=%d round=%-7s elapsed=%.3fms%s"
                    .formatted(e.seatIndex(), e.round(), e.elapsedNanos() / 1_000_000.0,
                               e.timedOut() ? "  TIMED_OUT" : "");
            case GameEvent.HandStarted e -> "HAND_START dealer=%d seed=%d"
                    .formatted(e.dealerSeat(), e.handSeed());
            case GameEvent.BlindPosted e -> "BLIND      seat=%d type=%-3s amount=%d stack=%d"
                    .formatted(e.seatIndex(), e.blindType(), e.amount(), e.resultingStack());
            case GameEvent.ActionTaken e -> "ACTION     seat=%d round=%-7s action=%-5s amount=%d stack=%d"
                    .formatted(e.seatIndex(), e.round(), e.action(), e.amount(), e.resultingStack());
            case GameEvent.CommunityCardDealt e -> "BOARD      round=%-7s card=%s dealt=%d"
                    .formatted(e.round(), CardUtils.cardToString(e.card()), e.cardsDealtSoFar());
            case GameEvent.PotAwarded e -> "POT        index=%d amount=%d winners=%s"
                    .formatted(e.potIndex(), e.amount(), e.winnerSeats());
            case GameEvent.HandEnded e -> "HAND_END   wonByFold=%s winners=%s"
                    .formatted(e.wonByFold(), e.winnerSeats());
        };
    }

    @Override
    public void close() {
        writer.close();
    }
}