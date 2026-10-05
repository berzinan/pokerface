package com.andrei.pokerface;

/**
 * Everything that defines one bot-vs-bot tournament benchmark except the bots
 * themselves. The tournament-pipeline counterpart to RingBenchmark, and
 * deliberately the same shape so the two collectors read alike.
 *
 * virtualMillisPerHand is the piece with no ring-game equivalent. Blind
 * escalation is keyed to elapsed session time, but a bot tournament plays a
 * thousand hands a second, so a real clock would leave every tournament stuck
 * on level 0 and ending at the hand cap. Feeding the schedule a virtual clock
 * that advances a fixed amount per hand restores the pressure that makes
 * tournaments actually finish -- and makes the whole run reproducible, since
 * escalation no longer depends on how fast the machine is.
 *
 * Pick it against your schedule's interval: at the default 60-second levels,
 * 2000 virtual ms per hand means a level change every 30 hands.
 */
public record TournamentBenchmark(
        int tournamentCount,
        int startingStack,
        long virtualMillisPerHand,
        BlindSchedule blindSchedule,
        TournamentEndCondition endCondition,
        long dealSeed,
        long agentSeed
) {

    public TournamentBenchmark {
        if (tournamentCount <= 0) {
            throw new IllegalArgumentException("tournamentCount must be positive");
        }
        if (startingStack <= 0) {
            throw new IllegalArgumentException("startingStack must be positive");
        }
        if (virtualMillisPerHand <= 0) {
            throw new IllegalArgumentException(
                    "virtualMillisPerHand must be positive, or blinds would never escalate");
        }
        if (blindSchedule == null || endCondition == null) {
            throw new IllegalArgumentException("blindSchedule and endCondition are required");
        }
    }

    /**
     * 10/20 blinds growing 33% every virtual minute, rounded to 5-chip units,
     * 2000 virtual ms per hand (so a level every 30 hands), running until one
     * player is left or 10,000 hands have passed.
     *
     * The hand cap is a backstop, not the expected exit. A tournament that
     * ends on the cap is reported as inconclusive and counts toward nobody's
     * win rate, so if those start appearing, the blinds are escalating too
     * slowly for the starting stack.
     */
    public static TournamentBenchmark of(int tournamentCount, int startingStack) {
        return new TournamentBenchmark(
                tournamentCount,
                startingStack,
                2_000L,
                BlindSchedule.increasing(new BlindLevel(10, 20), 0.33, 60_000L, 5),
                TournamentEndCondition.LAST_PLAYER_STANDING.orAfter(10_000),
                42L,
                1337L);
    }
}