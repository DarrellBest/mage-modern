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
        assertEquals("kanna", config.playerA);
        assertEquals("cp7", config.playerB);
        assertEquals(6, config.skill);
        assertEquals("xmage-ai-qwen3.6:latest", config.model);
        assertEquals(50, config.turnCap);
        assertEquals("bench-results.jsonl", config.out);
        assertEquals("twoplayer", config.gameType);
        assertEquals(null, config.trackCards);
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
                "--turnCap=10", "--out=x.jsonl", "--model=m", "--skill=4",
                "--deckA=UW Control.dck", "--deckB=Power Hungry.dck", "--gameType=commander"
        });
        assertEquals(5, config.games);
        assertEquals(99L, config.baseSeed);
        assertEquals("cp7", config.playerA);
        assertEquals("mcts", config.playerB);
        assertEquals(10, config.turnCap);
        assertEquals("x.jsonl", config.out);
        assertEquals("m", config.model);
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
