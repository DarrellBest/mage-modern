package mage.bench;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class BenchConfigTest {

    @Test
    public void defaults_areUsedWhenNoArgsGiven() {
        BenchConfig config = BenchConfig.parse(new String[]{});
        assertEquals(20, config.games);
        assertEquals(12345L, config.baseSeed);
        assertEquals("RB Aggro.dck", config.deckA);
        assertEquals("RB Aggro.dck", config.deckB);
        assertEquals("commander", config.playerA);
        assertEquals("cp7", config.playerB);
        assertEquals(6, config.skill);
        assertEquals(50, config.turnCap);
        assertEquals("bench-results.jsonl", config.out);
        assertEquals("twoplayer", config.gameType);
        assertEquals(null, config.trackCards);
        assertEquals(null, config.paramsA);
        assertEquals(null, config.paramsB);
    }

    @Test
    public void paramsAB_areNullByDefault_andSetIndependentlyWhenGiven() {
        // both optional, and independently so: the common sweep shape is one tuned side against the
        // stock baseline, which means exactly one of these is given
        BenchConfig both = BenchConfig.parse(new String[]{
                "--paramsA=/tmp/a.properties", "--paramsB=/tmp/b.properties"});
        assertEquals("/tmp/a.properties", both.paramsA);
        assertEquals("/tmp/b.properties", both.paramsB);

        BenchConfig onlyA = BenchConfig.parse(new String[]{"--paramsA=/tmp/a.properties"});
        assertEquals("/tmp/a.properties", onlyA.paramsA);
        assertEquals(null, onlyA.paramsB);

        BenchConfig onlyB = BenchConfig.parse(new String[]{"--paramsB=/tmp/b.properties"});
        assertEquals(null, onlyB.paramsA);
        assertEquals("/tmp/b.properties", onlyB.paramsB);
    }

    @Test
    public void misspeltParamsOption_failsClearly() {
        // --params=... (no side) is the likely typo, and it must not be swallowed
        try {
            BenchConfig.parse(new String[]{"--params=/tmp/a.properties"});
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals(true, e.getMessage().contains("params"));
        }
    }

    @Test
    public void trackCards_isNullByDefault_andSetWhenGiven() {
        assertEquals(null, BenchConfig.parse(new String[]{}).trackCards);

        BenchConfig config = BenchConfig.parse(new String[]{"--trackCards=/tmp/report.txt"});
        assertEquals("/tmp/report.txt", config.trackCards);
    }

    @Test
    public void namedArgs_overrideDefaults() {
        BenchConfig config = BenchConfig.parse(new String[]{
                "--games=5", "--seed=99", "--playerA=cp7", "--playerB=mcts",
                "--turnCap=10", "--out=x.jsonl", "--skill=4",
                "--deckA=UW Control.dck", "--deckB=Power Hungry.dck", "--gameType=commander"
        });
        assertEquals(5, config.games);
        assertEquals(99L, config.baseSeed);
        assertEquals("cp7", config.playerA);
        assertEquals("mcts", config.playerB);
        assertEquals(10, config.turnCap);
        assertEquals("x.jsonl", config.out);
        assertEquals(4, config.skill);
        assertEquals("UW Control.dck", config.deckA);
        assertEquals("Power Hungry.dck", config.deckB);
        assertEquals("commander", config.gameType);
    }

    @Test
    public void unknownGameType_failsClearly() {
        try {
            BenchConfig.parse(new String[]{"--gameType=freeforall"});
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals(true, e.getMessage().contains("--gameType"));
            assertEquals(true, e.getMessage().contains("freeforall"));
        }
    }

    @Test
    public void unknownArg_failsClearly() {
        try {
            BenchConfig.parse(new String[]{"--nonsense=1"});
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals(true, e.getMessage().contains("nonsense"));
        }
    }

    @Test
    public void nonNumericGames_failsClearly() {
        try {
            BenchConfig.parse(new String[]{"--games=lots"});
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals(true, e.getMessage().contains("--games"));
        }
    }
}
