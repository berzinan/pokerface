package com.andrei.pokerface;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * PokerAgent backed by a human at a terminal. Renders the PlayerView as
 * readable text, reads a command, validates it against the same legality rules
 * GameState.processAction enforces, and re-prompts on any violation rather than
 * ever handing GameState something that would throw.
 *
 * Input/output are injected (not System.in/System.out directly) so tests can
 * drive this with canned input and capture output without touching the real
 * console.
 *
 * THREADING. Reading input on a timer is the whole reason this class is more
 * complicated than it looks. BufferedReader.readLine() blocks and cannot be
 * portably interrupted -- there is no timeout variant, and interrupting the
 * thread does not unblock a read on System.in. So a dedicated daemon thread
 * does nothing but read lines and hand them to a BlockingQueue, and the game
 * thread waits on that queue with a timeout it CAN abandon. The human is idle
 * while we wait, so abandoning the wait is safe; what Java cannot interrupt is
 * computation, and there is none here.
 *
 * The reader thread is a daemon and is never stopped. It spends its life
 * blocked in readLine() on a stream this class does not own, so there is
 * nothing meaningful to close -- the daemon flag is what lets the JVM exit
 * anyway. It starts lazily on the first decision, so an instance that is
 * constructed and never used spawns nothing.
 *
 * Only one human seat is supported per input stream. Two instances reading the
 * same System.in would race for lines, and whichever thread won would be
 * arbitrary.
 */
public class HumanCliAgent implements PokerAgent {

    /** Sentinel deadline meaning "wait as long as it takes". */
    private static final long NO_DEADLINE = Long.MIN_VALUE;

    private final BufferedReader in;
    private final PrintStream out;
    private final String name;

    /** Lines read so far, oldest first; the endOfInput marker is the last entry. */
    private final BlockingQueue<Line> pendingLines = new LinkedBlockingQueue<>();

    /** Set once the stream ends or fails, so later turns fail fast instead of waiting. */
    private volatile boolean inputClosed = false;

    /** Non-null when the stream failed rather than ending cleanly; reported as the cause. */
    private volatile IOException readFailure = null;

    private Thread readerThread = null;

    /** One line of input, or the marker that no more will arrive. */
    private record Line(String text, boolean endOfInput) {}

    public HumanCliAgent(InputStream in, PrintStream out) {
        this(in, out, "Human");
    }

    public HumanCliAgent(InputStream in, PrintStream out, String name) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
        this.name = name;
    }

    @Override
    public ActionResult performAction(PlayerView view) {
        startReaderIfNeeded();

        // One deadline for the whole turn, not per prompt -- otherwise typing
        // nonsense would buy unlimited extra thinking time.
        long deadlineNanos = view.isTimed()
                ? System.nanoTime() + view.timeBudgetMillis() * 1_000_000L
                : NO_DEADLINE;

        render(view, deadlineNanos);

        while (true) {
            prompt(deadlineNanos);
            String line = awaitLine(deadlineNanos);
            if (line == null) {
                return onTimeout(view);
            }

            ParsedCommand parsed = parse(line);
            if (parsed == null) {
                out.println("Unrecognized command. Options: fold, check, call, raise <amount>, allin");
                continue;
            }
            String error = validate(parsed, view);
            if (error != null) {
                out.println(error);
                continue;
            }
            return toActionResult(parsed, view);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    /* -------------------------------------------------------------- */
    /* Input reading                                                     */
    /* -------------------------------------------------------------- */

    private void startReaderIfNeeded() {
        if (readerThread != null) {
            return;
        }
        readerThread = new Thread(this::readLoop, "pokerface-human-input");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Reads lines until the stream ends, queueing each one. An IOException is
     * treated as end of input: the stream is gone either way, and the cause is
     * kept so the game thread can report it.
     */
    private void readLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                pendingLines.put(new Line(line, false));
            }
        } catch (IOException e) {
            readFailure = e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        inputClosed = true;
        // Wakes a waiting game thread immediately rather than letting it sit
        // out the full move timer for input that will never arrive.
        pendingLines.offer(new Line(null, true));
    }

    /**
     * Waits for the next line, up to the deadline.
     *
     * @return the line, or null if the deadline passed first
     * @throws IllegalStateException if the input stream has ended
     */
    private String awaitLine(long deadlineNanos) {
        if (inputClosed && pendingLines.isEmpty()) {
            throw inputClosedException();
        }
        try {
            Line line;
            if (deadlineNanos == NO_DEADLINE) {
                line = pendingLines.take();
            } else {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return null;
                }
                line = pendingLines.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (line == null) {
                    return null;
                }
            }
            if (line.endOfInput()) {
                throw inputClosedException();
            }
            return line.text();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for human input", e);
        }
    }

    private IllegalStateException inputClosedException() {
        String message = "Input stream closed before a command was entered. "
                + "If you're launching via 'mvn exec:java', stdin may not be attached correctly -- "
                + "run 'java -cp target/classes com.andrei.pokerface.Play' instead.";
        return readFailure == null
                ? new IllegalStateException(message)
                : new IllegalStateException(message, readFailure);
    }

    /**
     * Discards anything already typed. Called only after a timeout: a keystroke
     * that lands after the deadline was not an action on this turn, and letting
     * it sit in the queue would make it the answer to the NEXT decision --
     * applying a command the human chose while looking at a different board.
     *
     * Deliberately not called before a turn, so piped input (tests, scripted
     * sessions) can queue every command up front and still be consumed in
     * order.
     */
    private void drainPendingInput() {
        pendingLines.removeIf(line -> !line.endOfInput());
    }

    /* -------------------------------------------------------------- */
    /* Timeout                                                           */
    /* -------------------------------------------------------------- */

    /**
     * What a human who ran out of time is deemed to have done: check if it
     * costs nothing, otherwise fold. Matches the fallback HandRunner applies to
     * any agent that overruns, so a human and a bot are treated identically.
     *
     * Returning here rather than blocking on is what makes the timer real:
     * HandRunner's own enforcement would discard a late answer, but only after
     * waiting for one.
     */
    private ActionResult onTimeout(PlayerView view) {
        drainPendingInput();
        boolean free = view.amountToCall() == 0;
        out.println();
        out.println(free
                ? "Out of time -- checked for you."
                : "Out of time -- folded for you.");
        return free ? ActionResult.check() : ActionResult.fold();
    }

    /* -------------------------------------------------------------- */
    /* Rendering                                                        */
    /* -------------------------------------------------------------- */

    private void render(PlayerView view, long deadlineNanos) {
        OpponentInfo me = view.me();
        int maxTarget = me.roundBet() + me.stack();
        boolean canRaise = maxTarget > view.currentBet();

        out.println();
        out.println("---- " + view.round() + " ----");
        out.println("Board: " + (view.communityCards().length == 0
                ? "(none)" : CardUtils.handToString(view.communityCards())));
        out.println("Your hand: " + renderHoleCards(view.myHoleCards()));
        out.println("Pot: " + view.potTotal() + "  Current bet: " + view.currentBet()
                + "  To call: " + view.amountToCall());
        out.println("Your stack: " + me.stack() + "  Your round bet: " + me.roundBet());
        if (canRaise) {
            // A full min-raise can exceed the stack. Shoving is still a legal
            // short all-in raise, so advertise that rather than a target the
            // player cannot reach.
            if (view.minRaiseTarget() > maxTarget) {
                out.println("Raise: all-in only, for " + maxTarget
                        + " (a full raise would need " + view.minRaiseTarget() + ").");
            } else {
                out.println("Min raise target: " + view.minRaiseTarget()
                        + "  Max (all-in) target: " + maxTarget);
            }
        }
        out.println("Seats:");
        for (OpponentInfo o : view.players()) {
            String status = o.eliminated() ? "out"
                    : o.folded() ? "folded"
                    : o.allIn() ? "all-in" : "active";
            String marker = (o.seatIndex() == view.mySeatIndex()) ? " (you)" : "";
            out.println("  " + o.seatIndex() + " " + o.name() + marker
                    + " stack=" + o.stack() + " bet=" + o.roundBet() + " [" + status + "]");
        }
        if (deadlineNanos != NO_DEADLINE) {
            out.println("You have " + secondsRemaining(deadlineNanos) + "s to act "
                    + "(no action = check if free, else fold).");
        }
        out.println("Commands: fold | check | call | raise <amount> | allin");
    }

    private String renderHoleCards(int[] holeCards) {
        for (int c : holeCards) {
            if (c < 0) {
                return "(not dealt)";
            }
        }
        return CardUtils.handToString(holeCards);
    }

    /**
     * Shows the seconds left on a timed turn, so a re-prompt after a bad
     * command tells the human how much of their budget it cost.
     */
    private void prompt(long deadlineNanos) {
        if (deadlineNanos == NO_DEADLINE) {
            out.print("> ");
        } else {
            out.print("(" + secondsRemaining(deadlineNanos) + "s) > ");
        }
        out.flush();
    }

    /** Whole seconds left, rounded up so a live timer never reads 0 while still open. */
    private long secondsRemaining(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        return remainingNanos <= 0 ? 0 : (remainingNanos + 999_999_999L) / 1_000_000_000L;
    }

    /* -------------------------------------------------------------- */
    /* Parsing                                                           */
    /* -------------------------------------------------------------- */

    private enum Kind { FOLD, CHECK, CALL, RAISE, ALLIN }

    private record ParsedCommand(Kind kind, Integer amount) {}

    private ParsedCommand parse(String line) {
        String trimmed = line.trim().toLowerCase();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] parts = trimmed.split("\\s+");
        String head = parts[0];

        return switch (head) {
            case "fold", "f" -> new ParsedCommand(Kind.FOLD, null);
            case "check", "k" -> new ParsedCommand(Kind.CHECK, null);
            case "call", "c" -> new ParsedCommand(Kind.CALL, null);
            case "allin", "shove", "all-in" -> new ParsedCommand(Kind.ALLIN, null);
            case "raise", "bet", "r" -> parseRaise(parts);
            default -> null;
        };
    }

    private ParsedCommand parseRaise(String[] parts) {
        if (parts.length != 2) {
            return null;
        }
        try {
            return new ParsedCommand(Kind.RAISE, Integer.parseInt(parts[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /* -------------------------------------------------------------- */
    /* Validation -- mirrors GameState.processAction's legality rules     */
    /* -------------------------------------------------------------- */

    private String validate(ParsedCommand cmd, PlayerView view) {
        OpponentInfo me = view.me();
        int maxTarget = me.roundBet() + me.stack();

        return switch (cmd.kind()) {
            case FOLD -> null;
            case CHECK -> (view.amountToCall() > 0)
                    ? "Cannot check: " + view.amountToCall() + " to call. Use call, raise, or fold."
                    : null;
            case CALL -> (view.amountToCall() == 0)
                    ? "Nothing to call. Use check instead."
                    : null;
            case ALLIN -> (maxTarget <= view.currentBet())
                    ? "Cannot go all-in as a raise here. Use call or fold."
                    : null;
            case RAISE -> validateRaise(cmd.amount(), view, me, maxTarget);
        };
    }

    private String validateRaise(int amount, PlayerView view, OpponentInfo me, int maxTarget) {
        if (amount <= view.currentBet()) {
            return "Raise must exceed the current bet of " + view.currentBet() + ".";
        }
        if (amount > maxTarget) {
            return "You only have " + me.stack() + " chips; max raise target is " + maxTarget + ".";
        }
        boolean isAllIn = (amount == maxTarget);
        int minIncrement = view.minRaiseTarget() - view.currentBet();
        int increment = amount - view.currentBet();
        if (increment < minIncrement && !isAllIn) {
            return "Raise must be at least " + view.minRaiseTarget()
                    + " (or go all-in for less with 'allin').";
        }
        return null;
    }

    /* -------------------------------------------------------------- */
    /* Conversion                                                        */
    /* -------------------------------------------------------------- */

    private ActionResult toActionResult(ParsedCommand cmd, PlayerView view) {
        OpponentInfo me = view.me();
        int maxTarget = me.roundBet() + me.stack();
        return switch (cmd.kind()) {
            case FOLD -> ActionResult.fold();
            case CHECK -> ActionResult.check();
            case CALL -> ActionResult.call();
            case ALLIN -> ActionResult.raiseTo(maxTarget);
            case RAISE -> ActionResult.raiseTo(cmd.amount());
        };
    }
}