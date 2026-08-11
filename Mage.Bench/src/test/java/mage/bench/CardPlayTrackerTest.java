package mage.bench;

import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CardPlayTrackerTest {

    private static DeckCardLists deckOf(String... cardNames) {
        DeckCardLists deck = new DeckCardLists();
        List<DeckCardInfo> cards = new ArrayList<>();
        for (String name : cardNames) {
            cards.add(new DeckCardInfo(name, "1", "SET"));
        }
        deck.setCards(cards);
        return deck;
    }

    private static Map<String, Integer> casts(Object... nameCountPairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < nameCountPairs.length; i += 2) {
            map.put((String) nameCountPairs[i], (Integer) nameCountPairs[i + 1]);
        }
        return map;
    }

    @Test
    public void neverCastCards_areTheDeckListMinusEverythingThatWasCast() {
        CardPlayTracker tracker = new CardPlayTracker();
        tracker.recordDeck("Kairi.dck", deckOf("Sol Ring", "Counterspell", "Academy Ruins"));

        tracker.recordGame("Kairi.dck", casts("Sol Ring", 1));
        tracker.recordGame("Kairi.dck", casts("Sol Ring", 1, "Counterspell", 2));

        String report = tracker.render();
        assertTrue(report.contains("Never cast (1 of 3 maindeck cards):"));
        assertTrue(report.contains("Academy Ruins"));
        // cards that WERE cast must not show up in the never-cast section's card list
        int neverCastIdx = report.indexOf("Never cast (1 of 3");
        String neverCastSection = report.substring(neverCastIdx);
        assertFalse(neverCastSection.contains("Sol Ring"));
    }

    @Test
    public void gamesCastIn_countsGamesNotIndividualCastEvents() {
        CardPlayTracker tracker = new CardPlayTracker();
        tracker.recordDeck("Kairi.dck", deckOf("Sol Ring"));

        // Sol Ring cast 3 times in game 1, once in game 2: totalCasts=4, gamesCastIn=2
        tracker.recordGame("Kairi.dck", casts("Sol Ring", 3));
        tracker.recordGame("Kairi.dck", casts("Sol Ring", 1));

        String report = tracker.render();
        assertTrue("expected '2 games / 4 casts' for Sol Ring, got:\n" + report,
                report.contains("2 games /   4 casts  Sol Ring")
                        || report.matches("(?s).*2 games /\\s*4 casts\\s+Sol Ring.*"));
    }

    @Test
    public void twoDifferentDecks_getSeparateSectionsAndDoNotMixCounts() {
        // regression guard for the seat-swap trap: two different decks must never have their
        // cast counts merged just because they happened to occupy the same "Seat1"/"Seat2" label
        // in different games (BenchGame.run is responsible for the seat->deck translation before
        // calling recordGame; this test pins that CardPlayTracker itself keeps decks separate
        // once given already-correct per-deck data).
        CardPlayTracker tracker = new CardPlayTracker();
        tracker.recordDeck("Kairi.dck", deckOf("Sol Ring", "Counterspell"));
        tracker.recordDeck("Krenko-R-EDH.dck", deckOf("Sol Ring", "Fireball"));

        tracker.recordGame("Kairi.dck", casts("Counterspell", 1));
        tracker.recordGame("Krenko-R-EDH.dck", casts("Fireball", 1));

        String report = tracker.render();
        int kairiIdx = report.indexOf("=== Kairi.dck ===");
        int krenkoIdx = report.indexOf("=== Krenko-R-EDH.dck ===");
        assertTrue(kairiIdx >= 0 && krenkoIdx >= 0);
        String kairiSection = report.substring(kairiIdx, krenkoIdx > kairiIdx ? krenkoIdx : report.length());
        // Kairi never cast Sol Ring in this test data; Krenko's Fireball cast must not leak in
        assertFalse(kairiSection.contains("Fireball"));
        assertTrue(kairiSection.contains("Never cast (1 of 2 maindeck cards):"));
        assertTrue(kairiSection.contains("Sol Ring"));
    }

    @Test
    public void castCardNotInMaindeckList_isSurfacedNotDropped() {
        // tokens, copies, MDFC back faces, or a genuine naming mismatch -- must be visible, not
        // silently discarded, so a real mismatch is caught instead of producing a wrong cut list
        CardPlayTracker tracker = new CardPlayTracker();
        tracker.recordDeck("Kairi.dck", deckOf("Sol Ring"));
        tracker.recordGame("Kairi.dck", casts("Sol Ring", 1, "Some Token Copy", 1));

        String report = tracker.render();
        assertTrue(report.contains("Cast but not found in maindeck list"));
        assertTrue(report.contains("Some Token Copy"));
    }

    @Test
    public void writeReport_writesRenderedTextToFile() throws IOException {
        CardPlayTracker tracker = new CardPlayTracker();
        tracker.recordDeck("Kairi.dck", deckOf("Sol Ring"));
        tracker.recordGame("Kairi.dck", casts("Sol Ring", 1));

        Path tmp = Files.createTempFile("card-play-report", ".txt");
        try {
            tracker.writeReport(tmp.toString());
            String written = new String(Files.readAllBytes(tmp), StandardCharsets.UTF_8);
            assertEquals(tracker.render(), written);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void deckWithNothingCastAtAll_reportsEveryCardAsNeverCast() {
        CardPlayTracker tracker = new CardPlayTracker();
        tracker.recordDeck("Kairi.dck", deckOf("Sol Ring", "Counterspell"));
        tracker.recordGame("Kairi.dck", casts());

        String report = tracker.render();
        assertTrue(report.contains("Never cast (2 of 2 maindeck cards):"));
    }
}
