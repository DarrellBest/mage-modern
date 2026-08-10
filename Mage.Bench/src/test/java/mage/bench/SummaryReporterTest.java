package mage.bench;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SummaryReporterTest {

    private GameResult game(int index, String winner, Termination termination, long wallMs) {
        return new GameResult(index, index, winner, 10, wallMs, termination, null, index % 2 == 1, LlmStats.empty());
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
    public void formatMentionsBothPlayersAndTheCapCount() {
        RunSummary summary = SummaryReporter.summarize(games(6, 4, 3, 2), "kanna");
        String text = SummaryReporter.format(summary, "kanna", "cp7");
        assertTrue(text.contains("kanna"));
        assertTrue(text.contains("cp7"));
        assertTrue(text.contains("3"));
    }
}
