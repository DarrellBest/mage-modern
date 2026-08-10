package mage.bench;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ResultWriterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private GameResult result(int index, String winner, Termination termination) {
        int winnerSeat = winner == null ? 0 : 1;
        return new GameResult(index, 100L + index, winner, winnerSeat, 12, 3400L, termination, null, index % 2 == 1, LlmStats.empty());
    }

    @Test
    public void roundTripsResultsThroughJsonl() throws Exception {
        File out = new File(folder.getRoot(), "r.jsonl");
        ResultWriter writer = new ResultWriter(out.getAbsolutePath());
        writer.append(result(0, "kanna", Termination.WIN));
        writer.append(result(1, "cp7", Termination.WIN));
        writer.close();

        List<GameResult> read = ResultWriter.read(out.getAbsolutePath());
        assertEquals(2, read.size());
        assertEquals(0, read.get(0).gameIndex);
        assertEquals("kanna", read.get(0).winner);
        assertEquals(Termination.WIN, read.get(0).termination);
        assertEquals(false, read.get(0).seatSwapped);
        assertEquals(101L, read.get(1).seed);
        assertEquals(true, read.get(1).seatSwapped);
    }

    @Test
    public void writesOneLinePerResult() throws Exception {
        File out = new File(folder.getRoot(), "lines.jsonl");
        ResultWriter writer = new ResultWriter(out.getAbsolutePath());
        writer.append(result(0, "kanna", Termination.WIN));
        writer.append(result(1, null, Termination.CAP));
        writer.append(result(2, null, Termination.ERROR));
        writer.close();

        List<String> lines = java.nio.file.Files.readAllLines(out.toPath());
        assertEquals(3, lines.size());
    }

    @Test
    public void dataSurvivesWithoutClose_becauseEachAppendFlushes() throws Exception {
        File out = new File(folder.getRoot(), "crash.jsonl");
        ResultWriter writer = new ResultWriter(out.getAbsolutePath());
        writer.append(result(0, "kanna", Termination.WIN));
        // deliberately NOT closed: simulates a killed run
        List<GameResult> read = ResultWriter.read(out.getAbsolutePath());
        assertEquals(1, read.size());
        writer.close();
    }

    @Test
    public void nullWinnerRoundTripsAsNull() throws Exception {
        File out = new File(folder.getRoot(), "draw.jsonl");
        ResultWriter writer = new ResultWriter(out.getAbsolutePath());
        writer.append(result(0, null, Termination.CAP));
        writer.close();
        assertNull(ResultWriter.read(out.getAbsolutePath()).get(0).winner);
    }
}
