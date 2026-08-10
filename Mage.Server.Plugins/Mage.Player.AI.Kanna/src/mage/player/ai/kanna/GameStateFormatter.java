package mage.player.ai.kanna;

import mage.counters.Counter;
import mage.counters.CounterType;
import mage.counters.Counters;
import mage.game.Game;
import mage.game.permanent.Permanent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders board state and computed combat consequences into the text the model
 * reads. Every annotation here comes from CombatEvaluator -- the model is never
 * shown a claim the heuristics did not compute.
 *
 * @author Darrell Best
 */
public final class GameStateFormatter {

    private GameStateFormatter() {
    }

    /**
     * Suffix like " [2 +1/+1 counters]" or " [0 eyeball counters]" for an activated
     * ability's source permanent, or "" when there is nothing counter-related to say.
     * <p>
     * DARRELLBEST-FORK: same root shape as the mana-ability exclusion in
     * ComputerPlayerKanna.priority() -- a legal action offered to the model without the
     * state that makes it good or bad. Jar of Eyeballs ({3}, {T}, Remove all eyeball
     * counters: look at X, X = counters removed) was activated 5 times in one game with
     * X=0 every time, because the shortlist showed its oracle text but never how many
     * eyeball counters it actually had. Permanent.getCounters(game) alone is not enough
     * here: a counter type is only present as a map entry once at least one has been
     * added, and removeCounter deletes the entry again once the count returns to zero
     * (Counters.removeCounter) -- so "no entry" cannot be told apart from "definitely
     * zero, and that number matters" by presence alone. Any counter type actually named
     * in the ability's own oracle text (e.g. "...eyeball counters...") is therefore
     * always reported, current count included, even when that count is zero; counter
     * types the permanent merely happens to carry but the ability text never mentions
     * are reported too (so a +1/+1 count is never hidden), but nothing is invented for
     * an ability that is not counter-related at all -- that is what keeps this from
     * bloating every line.
     */
    public static String counterAnnotation(Permanent permanent, String abilityText, Game game) {
        if (permanent == null) {
            return "";
        }
        Counters counters = permanent.getCounters(game);
        String lowerText = abilityText == null ? "" : abilityText.toLowerCase();
        List<String> parts = new ArrayList<String>();
        Set<String> reported = new HashSet<String>();
        for (Counter counter : counters.values()) {
            parts.add(describeCounter(counter.getName(), counter.getCount()));
            reported.add(counter.getName());
        }
        for (CounterType type : CounterType.values()) {
            String name = type.getName();
            if (name == null || reported.contains(name)) {
                continue;
            }
            if (lowerText.contains(name.toLowerCase() + " counter")) {
                parts.add(describeCounter(name, 0));
                reported.add(name);
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" [");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(parts.get(i));
        }
        return sb.append(']').toString();
    }

    private static String describeCounter(String name, int count) {
        return count + " " + name + " counter" + (count == 1 ? "" : "s");
    }

    public static String describeCreatures(List<CreatureView> creatures) {
        if (creatures.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (CreatureView creature : creatures) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(creature.describe());
        }
        return sb.toString();
    }

    /**
     * One line per possible attacker, annotated with what the attack actually does.
     */
    public static String attackOptions(List<CreatureView> attackers,
                                       List<CreatureView> defenderBlockers,
                                       int defenderLife) {
        StringBuilder sb = new StringBuilder();
        for (CreatureView attacker : attackers) {
            AttackOutcome outcome = CombatEvaluator.evaluateLikely(attacker, defenderBlockers);
            sb.append("- ").append(attacker.id).append(": ").append(attacker.describe())
                    .append("  -> ").append(outcome.summary);
            if (outcome.damageThrough > 0 && CombatEvaluator.isLethal(outcome.damageThrough, defenderLife)) {
                sb.append("  *** LETHAL ***");
            }
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }
}
