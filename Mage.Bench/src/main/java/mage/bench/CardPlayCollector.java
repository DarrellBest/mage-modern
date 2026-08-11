package mage.bench;

import mage.collectors.services.EmptyDataCollector;
import mage.game.Game;
import org.jsoup.Jsoup;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-game {@link mage.collectors.DataCollector}: parses {@code onGameLog} messages to record
 * which cards each seat cast during one benchmark game. One instance per game -- registered via
 * {@code DataCollectorServices.register} right before {@code game.start()} and unregistered
 * right after, by {@link BenchGame}. {@link CardPlayTracker} owns folding many games' worth of
 * these snapshots into a run-wide report.
 * <p>
 * Attribution is by exact substring match against the one and only place in the engine that
 * emits the literal text " casts " ({@code Spell.getActivatedMessage}, called from
 * {@code PlayerImpl.cast()} with {@code this.getLogName()} as the prefix -- i.e. always the
 * actual caster, including "you may cast" effects granted to another player, since {@code cast()}
 * runs on that player's own {@code PlayerImpl} instance). Verified by grepping the engine source
 * for other emitters of that literal string: there are none. See {@code CardPlayCollectorTest}
 * for the real captured log lines this pattern was built and tested against, including two real
 * negative cases (a triggered ability's reminder text and a stack description both contain the
 * word "casts" with no object-id bracket after it in the line, and correctly do not match).
 * <p>
 * Deliberately scoped to spell casts only, not land drops: land plays are logged as
 * "puts X from hand onto the Battlefield", but that exact phrase is also used by unrelated
 * effects (fetch lands, tutors, reanimation, blink) with nothing to distinguish "the deck's own
 * land drop" from "something else put this onto the battlefield" -- see the project's own
 * card-tracking-report.md for the fuller reasoning this was scoped out.
 *
 * @author Darrell Best
 */
public final class CardPlayCollector extends EmptyDataCollector {

    public static final String SERVICE_CODE = "cardPlayTracking-bench";

    // Player-name group excludes ':' so a triggered-ability line like
    // "Seat1 - Ability triggers: Rhystic Study [ac0] - Whenever an opponent casts a spell, ..."
    // can't have its embedded "casts" matched by starting the player-name capture after that
    // colon; combined with requiring a "[hex]" object-id bracket immediately after the card
    // name, this pattern only matches the engine's one real "casts" log line shape (see class
    // javadoc), not incidental uses of the word "casts" inside reminder/rules text.
    private static final Pattern CAST_PATTERN = Pattern.compile(
            "^([^:]+?) casts (?:a copied )?(.+?) \\[[0-9a-fA-F]+\\]");

    // seat label ("Seat1"/"Seat2", per the names BenchGame constructs its players with) -> card
    // name -> number of times cast so far this game
    private final Map<String, Map<String, Integer>> castsBySeat = new LinkedHashMap<>();

    @Override
    public String getServiceCode() {
        return SERVICE_CODE;
    }

    @Override
    public void onGameLog(Game game, String message) {
        // never let a parsing bug break a benchmark game -- DataCollector's own contract
        // (see mage.collectors.DataCollector javadoc) requires collectors be safe to ignore
        try {
            if (message == null) {
                return;
            }
            String plain = Jsoup.parse(message).text();
            Matcher matcher = CAST_PATTERN.matcher(plain);
            if (!matcher.find()) {
                return;
            }
            String seat = matcher.group(1).trim();
            String cardName = matcher.group(2).trim();
            if (seat.isEmpty() || cardName.isEmpty()) {
                return;
            }
            Map<String, Integer> cardCounts = castsBySeat.computeIfAbsent(seat, k -> new LinkedHashMap<>());
            cardCounts.merge(cardName, 1, Integer::sum);
        } catch (RuntimeException e) {
            // swallow: a benchmark run must never fail because of this optional instrumentation
        }
    }

    /**
     * Seat label -> card name -> times cast so far this game. Returned map is live, not a copy;
     * callers that need a stable snapshot after the game ends should copy it themselves.
     */
    public Map<String, Map<String, Integer>> snapshot() {
        return castsBySeat;
    }
}
