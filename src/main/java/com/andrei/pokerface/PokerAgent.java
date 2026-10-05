package com.andrei.pokerface;

/**
 * Implemented by anything that plays poker: a scripted bot, an RL policy,
 * a CLI adapter for a human, etc. The turn driver (HandRunner) builds a
 * PlayerView, calls performAction() once, and applies the result via
 * GameState.processAction(). The interface's job is to make hidden
 * information (other hole cards, deck order) unreachable -- it does not,
 * and cannot, prevent an implementation from behaving badly with what
 * it's given (e.g. betting more than its stack); GameState still
 * validates and throws on illegal actions.
 *
 * Timing: view.timeBudgetMillis() is the wall-clock allowance for this
 * decision, or PlayerView.UNLIMITED when no timer is in force (the
 * benchmark pipeline runs untimed). The budget is advisory -- an agent
 * that does variable-length work should size that work to fit, since an
 * overrun is not aborted mid-computation but discarded afterwards and
 * replaced with check-if-free-else-fold.
 */
public interface PokerAgent {
    ActionResult performAction(PlayerView view);

    /** Display name; override for anything other than a generic label. */
    default String getName() {
        return "Agent";
    }
}