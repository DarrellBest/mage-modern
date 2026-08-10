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

        // --- first-strike step ---
        // Only blockers with first or double strike deal damage in this step.
        int firstStrikeDamageBack = 0;
        boolean lethalInFirstStrikeStep = false;
        for (CreatureView blocker : blockers) {
            if (blocker.firstStrike || blocker.doubleStrike) {
                firstStrikeDamageBack += blocker.power;
                if (blocker.deathtouch && blocker.power > 0) {
                    lethalInFirstStrikeStep = true;
                }
            }
        }
        List<String> deadAfterFirstStrike = killedBy(attacker, blockers, firstStrikeDamage);
        boolean attackerDiesInFirstStrikeStep =
                lethalInFirstStrikeStep || firstStrikeDamageBack >= attacker.toughness;

        // --- regular step ---
        // An attacker killed in the first-strike step is removed from combat and deals nothing more.
        List<String> dead;
        int totalDealt;
        if (attackerDiesInFirstStrikeStep) {
            dead = deadAfterFirstStrike;
            totalDealt = firstStrikeDamage;
        } else {
            totalDealt = firstStrikeDamage + regularDamage;
            dead = killedBy(attacker, blockers, totalDealt);
        }

        int regularDamageBack = 0;
        boolean lethalInRegularStep = false;
        if (!attackerDiesInFirstStrikeStep) {
            for (CreatureView blocker : blockers) {
                // A blocker that died in the first-strike step never reaches this step.
                // A first-strike-only blocker already dealt its damage and does not deal again.
                boolean alreadyDead = deadAfterFirstStrike.contains(blocker.name);
                boolean strikesAgain = blocker.doubleStrike || !blocker.firstStrike;
                if (!alreadyDead && strikesAgain) {
                    regularDamageBack += blocker.power;
                    if (blocker.deathtouch && blocker.power > 0) {
                        lethalInRegularStep = true;
                    }
                }
            }
        }

        int damageBack = attackerDiesInFirstStrikeStep
                ? firstStrikeDamageBack
                : firstStrikeDamageBack + regularDamageBack;
        boolean attackerDies = attackerDiesInFirstStrikeStep || lethalInRegularStep
                || damageBack >= attacker.toughness;

        int damageThrough = 0;
        if (attacker.trample) {
            int soak = 0;
            for (CreatureView blocker : blockers) {
                soak += attacker.deathtouch ? 1 : blocker.toughness;
            }
            damageThrough = Math.max(0, totalDealt - soak);
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
        if (attacker.menace) {
            // legalBlockers guarantees able.size() >= 2 here; a lone blocker is not a legal
            // assignment against menace, so evaluating one would model an impossible block.
            for (int i = 0; i < able.size(); i++) {
                for (int j = i + 1; j < able.size(); j++) {
                    List<CreatureView> pair = new ArrayList<CreatureView>();
                    pair.add(able.get(i));
                    pair.add(able.get(j));
                    AttackOutcome outcome = evaluateBlockedBy(attacker, pair);
                    if (worst == null || rank(outcome) < rank(worst)) {
                        worst = outcome;
                    }
                }
            }
        } else {
            for (CreatureView blocker : able) {
                List<CreatureView> single = new ArrayList<CreatureView>();
                single.add(blocker);
                AttackOutcome outcome = evaluateBlockedBy(attacker, single);
                if (worst == null || rank(outcome) < rank(worst)) {
                    worst = outcome;
                }
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
