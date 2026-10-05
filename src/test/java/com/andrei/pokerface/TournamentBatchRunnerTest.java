package com.andrei.pokerface;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class TournamentBatchRunnerTest {

    private List<Player> makePlayers(int count, int startingStack) {
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            players.add(new Player(i, "P" + i, startingStack));
        }
        return players;
    }

    private IntSupplier incrementingSeeds() {
        AtomicInteger counter = new AtomicInteger(0);
        return counter::getAndIncrement;
    }

    // -------------------------------------------------------------------------
    // Argument validation
    // -------------------------------------------------------------------------

    @Test
    void runBatch_emptyAgentsThrows() {
        assertThrows(IllegalArgumentException.class, () -> TournamentBatchRunner.runBatch(
                () -> makePlayers(2, 200), List.of(),
                BlindSchedule.constant(5, 10), BustHandler.ELIMINATE,
                TournamentEndCondition.LAST_PLAYER_STANDING, incrementingSeeds(), HandLogger.NO_OP, 5));
    }

    @Test
    void runBatch_nonPositiveTournamentCountThrows() {
        List<PokerAgent> agents = List.of(new AllInAgent(), new AllInAgent());
        assertThrows(IllegalArgumentException.class, () -> TournamentBatchRunner.runBatch(
                () -> makePlayers(2, 200), agents,
                BlindSchedule.constant(5, 10), BustHandler.ELIMINATE,
                TournamentEndCondition.LAST_PLAYER_STANDING, incrementingSeeds(), HandLogger.NO_OP, 0));
    }

    @Test
    void runBatch_playerFactoryMismatchThrows() {
        List<PokerAgent> agents = List.of(new AllInAgent(), new AllInAgent());
        // factory produces 3 players for 2 agents
        Supplier<List<Player>> badFactory = () -> makePlayers(3, 200);

        assertThrows(IllegalArgumentException.class, () -> TournamentBatchRunner.runBatch(
                badFactory, agents,
                BlindSchedule.constant(5, 10), BustHandler.ELIMINATE,
                TournamentEndCondition.LAST_PLAYER_STANDING, incrementingSeeds(), HandLogger.NO_OP, 3));
    }

    // -------------------------------------------------------------------------
    // Core behavior
    // -------------------------------------------------------------------------

    @Test
    void runBatch_producesExactlyOneSessionResultPerTournament() {
        List<PokerAgent> agents = List.of(new AllInAgent(), new AllInAgent());

        TournamentBatchResult result = TournamentBatchRunner.runFreezeoutBatch(
                () -> makePlayers(2, 200), agents, 5, 10, incrementingSeeds(), 10);

        assertEquals(10, result.tournamentsPlayed());
        assertEquals(10, result.sessionResults().size());
    }

    @Test
    void runBatch_freshPlayersEachTournament_priorEliminationDoesNotCarryOver() {
        // If playerFactory reused the same busted Player objects, a second
        // tournament could start with a pre-eliminated seat 1 and immediately
        // fail GameState's live-player checks.
        List<PokerAgent> agents = List.of(new AllInAgent(), new AllInAgent());

        assertDoesNotThrow(() -> TournamentBatchRunner.runFreezeoutBatch(
                () -> makePlayers(2, 150), agents, 5, 10, incrementingSeeds(), 20));
    }

    @Test
    void runBatch_conservesChipsWithinEachTournament() {
        List<PokerAgent> agents = List.of(new AllInAgent(), new AllInAgent());
        int startingStack = 200;

        TournamentBatchResult result = TournamentBatchRunner.runFreezeoutBatch(
                () -> makePlayers(2, startingStack), agents, 5, 10, incrementingSeeds(), 15);

        for (SessionResult session : result.sessionResults()) {
            int total = session.finalPlayers().stream().mapToInt(Player::getStack).sum();
            assertEquals(2 * startingStack, total, "no chips may be created or destroyed within a tournament");
        }
    }

    // -------------------------------------------------------------------------
    // TournamentBatchResult -- aggregation
    // -------------------------------------------------------------------------

    @Test
    void winCountsBySeat_onlyCountsDecisiveTournaments() {
        List<Player> decisive = makePlayers(2, 200);
        decisive.get(1).eliminate(); // seat 0 is the lone survivor
        SessionResult decisiveResult = new SessionResult(5, decisive);

        List<Player> inconclusive = makePlayers(2, 200); // both still live
        SessionResult inconclusiveResult = new SessionResult(100, inconclusive);

        TournamentBatchResult result = new TournamentBatchResult(List.of(decisiveResult, inconclusiveResult));

        int[] counts = result.winCountsBySeat(2);
        assertEquals(1, counts[0]);
        assertEquals(0, counts[1]);
        assertEquals(1, result.inconclusiveCount());
    }

    @Test
    void winRatesBySeat_dividesByTotalTournamentsIncludingInconclusive() {
        List<Player> decisive = makePlayers(2, 200);
        decisive.get(1).eliminate();
        SessionResult decisiveResult = new SessionResult(5, decisive);

        List<Player> inconclusive = makePlayers(2, 200);
        SessionResult inconclusiveResult = new SessionResult(100, inconclusive);

        TournamentBatchResult result = new TournamentBatchResult(
                List.of(decisiveResult, inconclusiveResult));

        double[] rates = result.winRatesBySeat(2);
        assertEquals(0.5, rates[0], 1e-9);
        assertEquals(0.0, rates[1], 1e-9);
    }

    @Test
    void averageHandsPlayed_computesMeanAcrossTournaments() {
        SessionResult a = new SessionResult(10, makePlayers(2, 200));
        SessionResult b = new SessionResult(20, makePlayers(2, 200));

        TournamentBatchResult result = new TournamentBatchResult(List.of(a, b));

        assertEquals(15.0, result.averageHandsPlayed(), 1e-9);
    }

    @Test
    void sessionResults_isDefensivelyCopied() {
        List<SessionResult> mutable = new ArrayList<>();
        mutable.add(new SessionResult(10, makePlayers(2, 200)));
        TournamentBatchResult result = new TournamentBatchResult(mutable);

        mutable.add(new SessionResult(20, makePlayers(2, 200)));

        assertEquals(1, result.sessionResults().size(),
                "mutating the input list after construction must not affect the result");
    }

    // -------------------------------------------------------------------------
    // Sensecheck: identical bots across a heads-up batch should split near 50/50
    // -------------------------------------------------------------------------

    @Test
    void sensecheck_identicalBotsProduceRoughlyEvenWinSplitHeadsUp() {
        List<PokerAgent> agents = List.of(new AllInAgent(), new AllInAgent());

        TournamentBatchResult result = TournamentBatchRunner.runBatch(
                () -> makePlayers(2, 200), agents,
                BlindSchedule.constant(5, 10),
                BustHandler.ELIMINATE,
                TournamentEndCondition.LAST_PLAYER_STANDING.orAfter(30),
                incrementingSeeds(),
                HandLogger.NO_OP,
                200);

        double[] rates = result.winRatesBySeat(2);
        assertTrue(rates[0] > 0.3 && rates[0] < 0.7,
                "identical bots should split wins roughly evenly, got seat0 rate=" + rates[0]);
        assertTrue(rates[1] > 0.3 && rates[1] < 0.7,
                "identical bots should split wins roughly evenly, got seat1 rate=" + rates[1]);
    }

    // -------------------------------------------------------------------------
    // Inconclusive tournaments
    // -------------------------------------------------------------------------

    @Test
    void runBatch_allTournamentsInconclusiveWhenHandCapPreventsElimination() {
        // FoldingAgent vs FoldingAgent: fold-out pots stay small and dealer/blind
        // rotation keeps net drift near zero, so nobody busts within the cap.
        List<PokerAgent> agents = List.of(new FoldingAgent(), new FoldingAgent());

        TournamentBatchResult result = TournamentBatchRunner.runBatch(
                () -> makePlayers(2, 1000), agents,
                BlindSchedule.constant(5, 10),
                BustHandler.ELIMINATE,
                TournamentEndCondition.LAST_PLAYER_STANDING.orAfter(10),
                incrementingSeeds(),
                HandLogger.NO_OP,
                5);

        assertEquals(5, result.inconclusiveCount());
        assertArrayEquals(new int[]{0, 0}, result.winCountsBySeat(2));
    }
}