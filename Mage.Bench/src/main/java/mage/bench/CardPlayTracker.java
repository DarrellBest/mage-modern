package mage.bench;

import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-game, cross-run aggregator for {@code --trackCards}. Owned by {@link BenchRunner}, fed
 * one game's worth of cast counts at a time by {@link BenchGame}, keyed by the deck file name
 * that was actually on that seat for that particular game -- not by the raw "Seat1"/"Seat2" log
 * label, which flips decks between seats on seat-swapped (odd-indexed) games. Keying by deck
 * name instead means {@link BenchGame} must translate seat label to deck name before calling
 * {@link #recordGame}; this class assumes that translation already happened and just keeps
 * per-deck numbers separate (see {@code SummaryReporter}'s own seat-vs-key comment for the same
 * underlying trap solved the same way).
 * <p>
 * Deck names that happen to be identical for both seats (a mirror match) are a deliberate
 * exception: aggregating both seats' cast stats under one deck name is the correct behavior
 * there, since it is genuinely the same decklist being measured regardless of which seat it sat
 * in for a given game.
 *
 * @author Darrell Best
 */
public final class CardPlayTracker {

    private static final class CardStats {
        int gamesCastIn = 0;
        int totalCasts = 0;
    }

    // deck file name -> maindeck card names, in the order the deck file declared them
    private final Map<String, List<String>> maindeckByDeck = new LinkedHashMap<>();
    // deck file name -> number of games recorded for that deck (any outcome, not just ones with casts)
    private final Map<String, Integer> gamesPlayedByDeck = new LinkedHashMap<>();
    // deck file name -> card name -> stats, aggregated across every recorded game
    private final Map<String, Map<String, CardStats>> statsByDeck = new LinkedHashMap<>();

    /**
     * Records a deck's maindeck card list once, so {@link #render} can diff "cast" against
     * "never cast". Safe to call more than once for the same deck name (e.g. deckA == deckB in
     * a mirror match) -- later calls simply overwrite with the same list.
     */
    public void recordDeck(String deckName, DeckCardLists deck) {
        List<String> names = new ArrayList<>();
        for (DeckCardInfo info : deck.getCards()) {
            names.add(info.getCardName());
        }
        maindeckByDeck.put(deckName, names);
    }

    /**
     * Folds one game's worth of cast counts -- card name to times cast in that single game --
     * into the running total for {@code deckName}. Called once per seat per finished game, with
     * an empty map if that seat cast nothing.
     */
    public void recordGame(String deckName, Map<String, Integer> castsThisGame) {
        gamesPlayedByDeck.merge(deckName, 1, Integer::sum);
        Map<String, CardStats> deckStats = statsByDeck.computeIfAbsent(deckName, k -> new LinkedHashMap<>());
        for (Map.Entry<String, Integer> entry : castsThisGame.entrySet()) {
            CardStats stats = deckStats.computeIfAbsent(entry.getKey(), k -> new CardStats());
            stats.totalCasts += entry.getValue();
            stats.gamesCastIn += 1;
        }
    }

    public void writeReport(String path) throws IOException {
        Files.write(Paths.get(path), render().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Renders the full report text: one section per deck recorded via {@link #recordDeck}, each
     * with the most-cast cards ranked first and, most importantly, the never-cast list.
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        Set<String> allDeckNames = new LinkedHashSet<>();
        allDeckNames.addAll(maindeckByDeck.keySet());
        allDeckNames.addAll(statsByDeck.keySet());
        for (String deckName : allDeckNames) {
            appendDeckSection(sb, deckName);
        }
        return sb.toString();
    }

    private void appendDeckSection(StringBuilder sb, String deckName) {
        int gamesPlayed = gamesPlayedByDeck.getOrDefault(deckName, 0);
        Map<String, CardStats> deckStats = statsByDeck.getOrDefault(deckName, Collections.emptyMap());
        List<String> maindeck = maindeckByDeck.get(deckName);

        sb.append("=== ").append(deckName).append(" ===\n");
        sb.append("Games played: ").append(gamesPlayed).append("\n\n");

        List<Map.Entry<String, CardStats>> ranked = new ArrayList<>(deckStats.entrySet());
        ranked.sort(Comparator
                .<Map.Entry<String, CardStats>>comparingInt(e -> e.getValue().totalCasts).reversed()
                .thenComparing(Map.Entry::getKey));

        sb.append("Most cast:\n");
        if (ranked.isEmpty()) {
            sb.append("  (nothing cast)\n");
        }
        for (Map.Entry<String, CardStats> entry : ranked) {
            sb.append(String.format("  %d games / %d casts  %s%n",
                    entry.getValue().gamesCastIn, entry.getValue().totalCasts, entry.getKey()));
        }

        if (maindeck == null) {
            // recordGame was called for a deck that was never passed to recordDeck -- shouldn't
            // happen given how BenchRunner wires this, but the cast data is still real, so it's
            // shown above rather than silently dropped; there's just nothing to diff against.
            sb.append("\n");
            return;
        }

        List<String> neverCast = new ArrayList<>();
        for (String cardName : new LinkedHashSet<>(maindeck)) {
            if (!deckStats.containsKey(cardName)) {
                neverCast.add(cardName);
            }
        }
        Collections.sort(neverCast);
        sb.append(String.format("%nNever cast (%d of %d maindeck cards):%n", neverCast.size(), maindeck.size()));
        for (String cardName : neverCast) {
            sb.append("  ").append(cardName).append("\n");
        }

        // Cast events that couldn't be matched to a maindeck card name -- tokens, copies, MDFC
        // back faces, or (should the parser ever regress) a genuine naming mismatch. Surfaced
        // rather than silently dropped: a wrong cut list from a silent mismatch is worse than an
        // extra line in the report.
        List<String> unmatched = new ArrayList<>();
        Set<String> maindeckSet = new LinkedHashSet<>(maindeck);
        for (String cardName : deckStats.keySet()) {
            if (!maindeckSet.contains(cardName)) {
                unmatched.add(cardName);
            }
        }
        if (!unmatched.isEmpty()) {
            Collections.sort(unmatched);
            sb.append(String.format("%nCast but not found in maindeck list (%d) -- tokens, copies, "
                    + "or a naming mismatch:%n", unmatched.size()));
            for (String cardName : unmatched) {
                sb.append("  ").append(cardName).append("\n");
            }
        }
        sb.append("\n");
    }
}
