package mage.bench;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SummaryReporterTest {

    private GameResult game(int index, String winner, Termination termination, long wallMs) {
        boolean swapped = index % 2 == 1;
        int winnerSeat;
        if (winner == null) {
            winnerSeat = 0;
        } else {
            // "kanna" is always playerA in these tests; resolve it to whichever seat
            // playerA occupies for this game (seat 2 when swapped) so seat-based
            // attribution in SummaryReporter reproduces the same result these tests
            // were written to check by key
            boolean isPlayerA = "kanna".equals(winner);
            int seatA = swapped ? 2 : 1;
            int seatB = swapped ? 1 : 2;
            winnerSeat = isPlayerA ? seatA : seatB;
        }
        return new GameResult(index, index, winner, winnerSeat, 10, wallMs, termination, null, swapped, LlmStats.empty());
    }

    private List<GameResult> games(int kannaWins, int cp7Wins, int caps, int errors) {
        List<GameResult> results = new ArrayList<>();
        int index = 0;
        for (int i = 0; i < kannaWins; i++) {
            results.add(game(index++, "kanna", Termination.WIN, 1000L));
        }
        for (int i = 0; i < cp7Wins; i++) {
            results.add(game(index++, "cp7", Termination.WIN, 1000L));
        }
        for (int i = 0; i < caps; i++) {
            results.add(game(index++, null, Termination.CAP, 1000L));
        }
        for (int i = 0; i < errors; i++) {
            results.add(game(index++, null, Termination.ERROR, 1000L));
        }
        return results;
    }

    @Test
    public void countsWinsCapsAndErrorsSeparately() {
        RunSummary summary = SummaryReporter.summarize(games(6, 4, 3, 2), "kanna");
        assertEquals(15, summary.total);
        assertEquals(10, summary.decisive);
        assertEquals(6, summary.winsA);
        assertEquals(4, summary.winsB);
        assertEquals(3, summary.caps);
        assertEquals(2, summary.errors);
    }

    @Test
    public void winRateExcludesCapsAndErrorsFromTheDenominator() {
        // 6 wins of 10 decisive games is 60%, NOT 6/15 = 40%
        RunSummary summary = SummaryReporter.summarize(games(6, 4, 3, 2), "kanna");
        assertEquals(0.60, summary.winRateA, 0.0001);
    }

    @Test
    public void wilsonInterval_fiftyPercentOfTen() {
        RunSummary summary = SummaryReporter.summarize(games(5, 5, 0, 0), "kanna");
        assertEquals(0.50, summary.winRateA, 0.0001);
        assertEquals(0.23659, summary.wilsonLowerA, 0.001);
        assertEquals(0.76341, summary.wilsonUpperA, 0.001);
    }

    @Test
    public void wilsonInterval_zeroWins_lowerBoundIsZeroNotNegative() {
        RunSummary summary = SummaryReporter.summarize(games(0, 10, 0, 0), "kanna");
        assertEquals(0.0, summary.winRateA, 0.0001);
        assertEquals(0.0, summary.wilsonLowerA, 0.001);
        assertEquals(0.27754, summary.wilsonUpperA, 0.001);
    }

    @Test
    public void wilsonInterval_allWins_upperBoundIsOneNotAboveOne() {
        RunSummary summary = SummaryReporter.summarize(games(10, 0, 0, 0), "kanna");
        assertEquals(1.0, summary.winRateA, 0.0001);
        assertEquals(0.72246, summary.wilsonLowerA, 0.001);
        assertEquals(1.0, summary.wilsonUpperA, 0.001);
    }

    @Test
    public void noDecisiveGames_doesNotDivideByZero() {
        RunSummary summary = SummaryReporter.summarize(games(0, 0, 4, 1), "kanna");
        assertEquals(0, summary.decisive);
        assertEquals(0.0, summary.winRateA, 0.0001);
        assertEquals(0.0, summary.wilsonLowerA, 0.0001);
        assertEquals(0.0, summary.wilsonUpperA, 0.0001);
    }

    @Test
    public void emptyResults_summarizeCleanly() {
        RunSummary summary = SummaryReporter.summarize(new ArrayList<GameResult>(), "kanna");
        assertEquals(0, summary.total);
        assertEquals(0, summary.decisive);
    }

    @Test
    public void identicalKeyMatchup_attributesBySeatNotByKey() {
        // cp7 vs cp7 control run: the key is identical on both seats, so attribution by
        // key would (incorrectly) book every decisive game as a win for A. Seat 1 wins 5,
        // seat 2 wins 5, none swapped -- must come out 50/50, not 100/0.
        List<GameResult> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(new GameResult(i, i, "cp7", 1, 10, 1000L, Termination.WIN, null, false, LlmStats.empty()));
        }
        for (int i = 5; i < 10; i++) {
            results.add(new GameResult(i, i, "cp7", 2, 10, 1000L, Termination.WIN, null, false, LlmStats.empty()));
        }

        RunSummary summary = SummaryReporter.summarize(results, "cp7");
        assertEquals(10, summary.decisive);
        assertEquals(5, summary.winsA);
        assertEquals(5, summary.winsB);
        assertEquals(0.50, summary.winRateA, 0.0001);
    }

    @Test
    public void drawsAreCountedSeparatelyAndExcludedFromDecisive() {
        List<GameResult> results = new ArrayList<>();
        results.add(new GameResult(0, 0, "kanna", 1, 10, 1000L, Termination.WIN, null, false, LlmStats.empty()));
        results.add(new GameResult(1, 1, null, 0, 8, 1000L, Termination.DRAW, null, false, LlmStats.empty()));
        results.add(new GameResult(2, 2, null, 0, 50, 1000L, Termination.CAP, null, false, LlmStats.empty()));

        RunSummary summary = SummaryReporter.summarize(results, "kanna");
        assertEquals(3, summary.total);
        assertEquals(1, summary.decisive);
        assertEquals(1, summary.draws);
        assertEquals(1, summary.caps);
    }

    @Test
    public void formatMentionsBothPlayersAndTheCapCount() {
        RunSummary summary = SummaryReporter.summarize(games(6, 4, 3, 2), "kanna");
        String text = SummaryReporter.format(summary, "kanna", "cp7");
        assertTrue(text.contains("kanna"));
        assertTrue(text.contains("cp7"));
        assertTrue(text.contains("3"));
    }
}
