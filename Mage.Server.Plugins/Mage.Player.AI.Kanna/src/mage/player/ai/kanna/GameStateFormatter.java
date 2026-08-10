package mage.player.ai.kanna;

import java.util.List;

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
