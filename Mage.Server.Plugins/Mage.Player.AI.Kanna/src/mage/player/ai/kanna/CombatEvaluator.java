package mage.player.ai.kanna;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact combat arithmetic. Pure: takes CreatureView values, returns an
 * AttackOutcome, touches nothing else.
 * <p>
 * One evaluator serves three consumers, which is what keeps the heuristic and
 * model halves complementary rather than duplicated: it annotates the prompt,
 * it drives ranking, and it is the fallback when the model fails.
 *
 * @author Darrell Best
 */
public final class CombatEvaluator {

    private CombatEvaluator() {
    }

    public static boolean canBlock(CreatureView blocker, CreatureView attacker) {
        if (blocker.tapped) {
            return false;
        }
        if (attacker.flying && !(blocker.flying || blocker.reach)) {
            return false;
        }
        return true;
    }

    /**
     * @return the candidates that could legally block this attacker, or an empty
     * list when no legal assignment exists (menace with only one able blocker).
     */
    public static List<CreatureView> legalBlockers(CreatureView attacker, List<CreatureView> candidates) {
        List<CreatureView> able = new ArrayList<CreatureView>();
        for (CreatureView candidate : candidates) {
            if (canBlock(candidate, attacker)) {
                able.add(candidate);
            }
        }
        if (attacker.menace && able.size() < 2) {
            return new ArrayList<CreatureView>();
        }
        return able;
    }

    public static AttackOutcome evaluateUnblocked(CreatureView attacker) {
        int damage = attacker.power * (attacker.doubleStrike ? 2 : 1);
        String summary = attacker.name + " is unblocked and deals " + damage + " damage";
        return new AttackOutcome(false, new ArrayList<String>(), damage, true, summary);
    }

    public static AttackOutcome evaluateBlockedBy(CreatureView attacker, List<CreatureView> blockers) {
        if (blockers.isEmpty()) {
            return evaluateUnblocked(attacker);
        }

        boolean attackerStrikesFirst = attacker.firstStrike || attacker.doubleStrike;
        int firstStrikeDamage = attackerStrikesFirst ? attacker.power : 0;
        int regularDamage = attacker.doubleStrike ? attacker.power
                : (attacker.firstStrike ? 0 : attacker.power);
        int totalDamage = firstStrikeDamage + regularDamage;

        // A blocker only escapes striking back if the first-strike step alone killed it.
        List<String> deadAfterFirstStrike = killedBy(attacker, blockers, firstStrikeDamage);
        List<String> dead = killedBy(attacker, blockers, totalDamage);

        boolean anyBlockerStrikesFirst = anyStrikesFirst(blockers);
        int damageBack = 0;
        for (CreatureView blocker : blockers) {
            boolean killedBeforeItStruck = attackerStrikesFirst && !anyBlockerStrikesFirst
                    && deadAfterFirstStrike.contains(blocker.name);
            if (!killedBeforeItStruck) {
                damageBack += blocker.power * (blocker.doubleStrike ? 2 : 1);
                if (blocker.deathtouch && blocker.power > 0) {
                    damageBack = Math.max(damageBack, attacker.toughness);
                }
            }
        }
        boolean attackerDies = damageBack >= attacker.toughness;

        int damageThrough = 0;
        if (attacker.trample) {
            int soak = 0;
            for (CreatureView blocker : blockers) {
                soak += attacker.deathtouch ? 1 : blocker.toughness;
            }
            damageThrough = Math.max(0, totalDamage - soak);
        }

        StringBuilder summary = new StringBuilder();
        summary.append(attacker.name).append(" blocked by ").append(blockers.size())
                .append(blockers.size() == 1 ? " creature" : " creatures").append(": ");
        summary.append(attackerDies ? attacker.name + " dies" : attacker.name + " survives");
        if (!dead.isEmpty()) {
            summary.append(", kills ").append(join(dead));
        }
        if (damageThrough > 0) {
            summary.append(", ").append(damageThrough).append(" tramples through");
        }

        return new AttackOutcome(attackerDies, dead, damageThrough, false, summary.toString());
    }

    /**
     * Which blockers die if the attacker assigns exactly this much damage, in order.
     * Deathtouch makes 1 damage lethal to any blocker.
     */
    private static List<String> killedBy(CreatureView attacker, List<CreatureView> blockers, int damage) {
        List<String> dead = new ArrayList<String>();
        int remaining = damage;
        for (CreatureView blocker : blockers) {
            if (remaining <= 0) {
                break;
            }
            int needed = attacker.deathtouch ? 1 : blocker.toughness;
            if (remaining >= needed) {
                dead.add(blocker.name);
                remaining -= needed;
            } else {
                remaining = 0;
            }
        }
        return dead;
    }

    /**
     * Best guess at what happens if this creature attacks: unblocked when no legal
     * block exists, otherwise the defender's most damaging single block.
     */
    public static AttackOutcome evaluateLikely(CreatureView attacker, List<CreatureView> availableBlockers) {
        List<CreatureView> able = legalBlockers(attacker, availableBlockers);
        if (able.isEmpty()) {
            return evaluateUnblocked(attacker);
        }
        AttackOutcome worst = null;
        for (CreatureView blocker : able) {
            List<CreatureView> single = new ArrayList<CreatureView>();
            single.add(blocker);
            AttackOutcome outcome = evaluateBlockedBy(attacker, single);
            if (worst == null || rank(outcome) < rank(worst)) {
                worst = outcome;
            }
        }
        return worst;
    }

    public static boolean isLethal(int damageThrough, int defenderLife) {
        return damageThrough >= defenderLife;
    }

    /** Higher is better for the attacker. Used only to pick the defender's best block. */
    private static int rank(AttackOutcome outcome) {
        return (outcome.attackerDies ? -10 : 0) + outcome.blockersThatDie.size() * 5 + outcome.damageThrough;
    }

    private static boolean anyStrikesFirst(List<CreatureView> creatures) {
        for (CreatureView creature : creatures) {
            if (creature.firstStrike || creature.doubleStrike) {
                return true;
            }
        }
        return false;
    }

    private static String join(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(name);
        }
        return sb.toString();
    }
}
