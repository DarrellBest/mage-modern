package mage.player.ai.kanna;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Orders legal actions so the model sees the few that matter first.
 * <p>
 * Deliberately coarse. Its job is to anchor attention and cut prompt size, not
 * to decide the game -- that is the model's half of the work. The full
 * catalog always stays reachable via show_all_actions, and the hidden count
 * is always stated, so the shortlist can never quietly bury a winning line
 * that is actually in the catalog.
 * <p>
 * DARRELLBEST-FORK: that invariant covers the catalog's contents, not every
 * legal ability in the game -- ComputerPlayerKanna.priority() excludes mana
 * abilities (tap-for-mana) from the catalog entirely, upstream of this class
 * and upstream of ranking, so they are never listed by show_all_actions and
 * never counted in the hidden total. That exclusion is a deliberate design
 * choice made at the filter site (see the comment there), not something
 * ActionRanker enforces or is even aware of.
 *
 * @author Darrell Best
 */
public final class ActionRanker {

    private static final int SCORE_LAND = 100;
    private static final int SCORE_REMOVAL = 90;
    private static final int SCORE_CREATURE = 80;
    private static final int SCORE_PASS = 0;
    // DARRELLBEST-FORK: deliberately BELOW SCORE_PASS, not just below the recognised
    // buckets above it. This ranker only recognises lands/removal/"cast " by substring
    // match -- everything else (any activated ability whose label doesn't match one of
    // those) lands here. With this above SCORE_PASS (it used to be 50), once no land or
    // spell was on offer an arbitrary, unevaluated activated ability became the
    // top-ranked suggestion every single turn, and the model took the headline
    // recommendation -- this is what drove Kanna to activate Jar of Eyeballs with 0
    // eyeball counters, burning {3}, five times in one game. A tie with SCORE_PASS is
    // not enough either: Pass is always added to the catalog last, so a stable sort
    // would keep the unrecognised ability sorted ahead of it on insertion order alone.
    // Strictly negative is what actually makes "I have no opinion" rank behind "do
    // nothing" by default, leaving the model to weigh it on the oracle text in the
    // prompt rather than on its position in this list.
    private static final int SCORE_OTHER = -10;

    private ActionRanker() {
    }

    public static List<RankedAction> rank(ActionCatalog catalog) {
        List<RankedAction> ranked = new ArrayList<RankedAction>();
        List<String> ids = catalog.ids();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            String label = catalog.labelFor(id);
            int score = score(label);
            ranked.add(new RankedAction(id, label, reason(label, score), score));
        }
        // stable sort: equal scores keep insertion order, so ranking is reproducible
        Collections.sort(ranked, new Comparator<RankedAction>() {
            @Override
            public int compare(RankedAction a, RankedAction b) {
                return Integer.compare(b.score, a.score);
            }
        });
        return ranked;
    }

    public static List<RankedAction> shortlist(List<RankedAction> ranked, int limit) {
        if (ranked.size() <= limit) {
            return new ArrayList<RankedAction>(ranked);
        }
        return new ArrayList<RankedAction>(ranked.subList(0, limit));
    }

    public static String render(List<RankedAction> shortlist, int totalCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shortlist.size(); i++) {
            RankedAction action = shortlist.get(i);
            sb.append(String.format("%2d. %-8s %s", i + 1, action.id, action.label));
            if (action.reason != null && !action.reason.isEmpty()) {
                sb.append("  (").append(action.reason).append(')');
            }
            sb.append(System.lineSeparator());
        }
        int hidden = totalCount - shortlist.size();
        if (hidden > 0) {
            sb.append("... ").append(hidden)
                    .append(" more options available: call show_all_actions")
                    .append(System.lineSeparator());
        }
        return sb.toString();
    }

    private static int score(String label) {
        String lower = label == null ? "" : label.toLowerCase();
        if (lower.startsWith("pass")) {
            return SCORE_PASS;
        }
        if (lower.contains("play ") && isLandName(lower)) {
            return SCORE_LAND;
        }
        if (lower.contains("bolt") || lower.contains("destroy") || lower.contains("damage")
                || lower.contains("slash") || lower.contains("shock") || lower.contains("kill")) {
            return SCORE_REMOVAL;
        }
        if (lower.startsWith("cast ")) {
            return SCORE_CREATURE;
        }
        return SCORE_OTHER;
    }

    private static boolean isLandName(String lower) {
        return lower.contains("mountain") || lower.contains("forest") || lower.contains("island")
                || lower.contains("swamp") || lower.contains("plains") || lower.contains("land");
    }

    private static String reason(String label, int score) {
        if (score == SCORE_LAND) {
            return "land drop, adds mana this turn";
        }
        if (score == SCORE_REMOVAL) {
            return "removal";
        }
        if (score == SCORE_CREATURE) {
            return "board presence";
        }
        if (score == SCORE_PASS) {
            return "take no action";
        }
        if (score == SCORE_OTHER) {
            // Honest, not a real evaluation: this ranker cannot judge an arbitrary
            // activated ability, so it says so rather than staying silent (silence read
            // as "nothing to add" rather than "not evaluated", which invited the model
            // to trust the ranking anyway).
            return "unscored -- judge from the oracle text above, not this ranking";
        }
        return "";
    }
}
