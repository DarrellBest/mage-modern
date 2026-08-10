package mage.bench;

import mage.cards.decks.DeckCardLists;
import mage.cards.repository.CardScanner;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Covers the loadDeckList invariant check: importer messages alone must not fail a deck, but a
 * deck that actually lost cards during import must. Fixtures live in Mage.Tests/benchdecks/
 * (referenced here via a relative path, since Maven Surefire's working directory for this
 * module is Mage.Bench/) -- real user decks for the "substituted printing" cases, and two
 * small synthetic fixtures (fixture-unresolvable-card.dck, fixture-too-small.dck) added
 * alongside them for the failure cases, so no real deck had to be mutated to exercise them.
 *
 * @author Darrell Best
 */
public class BenchGameTest {

    private static final String BENCHDECKS_DIR = ".." + java.io.File.separator
            + "Mage.Tests" + java.io.File.separator + "benchdecks";

    @BeforeClass
    public static void scanCards() {
        // idempotent, guarded by its own static flag -- see BenchGame.run()'s own comment on
        // why this is required before any deck import/resolution can find any card at all
        CardScanner.scan();
    }

    @Test
    public void deckWithSubstitutedPrinting_loadsSuccessfully() {
        // Edgar-RWB-EDH.dck is a real user deck containing "1 [SLD:2560] Sol Ring" -- a Secret
        // Lair collector number this build's card DB doesn't have under that exact number, so
        // the importer substitutes a different printing of Sol Ring and emits an informational
        // message about it. That message must not fail the load: every declared card (99
        // maindeck, 1 sideboard commander) must still be present in the result.
        DeckCardLists list = BenchGame.loadDeckList(BENCHDECKS_DIR, "Edgar-RWB-EDH.dck");
        assertEquals(99, list.getCards().size());
        assertEquals(1, list.getSideboard().size());
    }

    @Test
    public void alreadyPassingDecks_continueToLoad() {
        // Kairi.dck and Krenko-R-EDH.dck also contain Secret Lair lines and already loaded
        // successfully before this fix (their specific SLD numbers happen to resolve
        // directly); this pins that they still load after the invariant-based rewrite.
        DeckCardLists kairi = BenchGame.loadDeckList(BENCHDECKS_DIR, "Kairi.dck");
        assertEquals(99, kairi.getCards().size());
        assertEquals(1, kairi.getSideboard().size());

        DeckCardLists krenko = BenchGame.loadDeckList(BENCHDECKS_DIR, "Krenko-R-EDH.dck");
        assertEquals(99, krenko.getCards().size());
        assertEquals(1, krenko.getSideboard().size());
    }

    @Test
    public void deckWithGenuinelyUnresolvableCard_failsNamingCounts() {
        // fixture-unresolvable-card.dck declares 3 maindeck cards, one of which
        // ("Totally Not A Real Magic Card") cannot be resolved by number or by name -- the
        // exact failure mode the old "ignore a throwaway StringBuilder" code let through
        // silently. The importer drops that line entirely rather than substituting anything,
        // so the parsed list comes up one card short of the file's own declared total, and
        // that mismatch -- not the presence of an importer message -- is what must fail the
        // load, naming both the expected and actual counts.
        try {
            BenchGame.loadDeckList(BENCHDECKS_DIR, "fixture-unresolvable-card.dck");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            assertTrue("message should name the deck: " + message, message.contains("fixture-unresolvable-card.dck"));
            assertTrue("message should name the expected maindeck count: " + message, message.contains("expected 3 maindeck"));
            assertTrue("message should name the actual maindeck count: " + message, message.contains("but only 2 maindeck"));
        }
    }

    @Test
    public void tooSmallDeck_stillFailsTheMinimumSizeCheck() {
        // fixture-too-small.dck imports cleanly (its single declared card, 10x Plains,
        // resolves and the counts match) but is far below MIN_MAINDECK_TWOPLAYER -- proving
        // the invariant-check rewrite in loadDeckList did not weaken or bypass the separate
        // minimum-deck-size check in addPlayer, which this test intentionally does not touch.
        BenchConfig config = BenchConfig.parse(new String[]{
                "--games=1",
                "--playerA=base",
                "--playerB=base",
                "--turnCap=5",
                "--deckDir=" + BENCHDECKS_DIR,
                "--deckA=fixture-too-small.dck",
                "--deckB=fixture-too-small.dck"
        });
        GameResult result = BenchGame.run(config, 0, config.baseSeed, false);
        assertEquals(Termination.ERROR, result.termination);
        assertTrue("error message should mention the minimum-size check: " + result.errorMessage,
                result.errorMessage != null && result.errorMessage.contains("minimum 40"));
    }
}
