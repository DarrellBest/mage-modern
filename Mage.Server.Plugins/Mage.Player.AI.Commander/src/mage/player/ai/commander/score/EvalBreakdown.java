package mage.player.ai.commander.score;

/**
 * DARRELLBEST-FORK: the evaluator's score, split into the UNWEIGHTED quantity behind each tunable
 * weight, so that
 *
 * <pre>   score == sum over terms of (weight[i] * quantity[i])</pre>
 *
 * <b>Why.</b> The learner used sixteen hand-picked features that only approximated what the
 * evaluator actually computes, so it could only learn a subset of the tuning and three of its seeds
 * were rough stand-ins. With one feature per weight, learning a weight IS learning the evaluator
 * parameter of the same name -- the model becomes a strict superset of the hand-tuned bot rather
 * than a parallel approximation of it, and a learned value reads directly as "this tuned number is
 * too high or too low".
 *
 * <b>What is deliberately absent.</b> Seven parameters are behavioural switches, not weights:
 * {@code attackAggression}, {@code modeSelectionMode}, {@code blockTradeMode}, {@code smartMulligan},
 * {@code multiplayerAttackSplit}, {@code declineLosingManaPayments} and {@code opponentSelectionMode}.
 * They select CODE PATHS -- whether to attack into a favourable trade, which mode to choose, whether
 * a block must beat not blocking -- and no linear weight over board features can express them. They
 * have to be A/B'd, and pretending otherwise would mean a model quietly learning a number that
 * changes nothing. {@code maxTabulatedLife} is structural (a table bound) for the same reason.
 *
 * <b>The invariant is testable, and is tested.</b> {@code EvalBreakdownTest} asserts the
 * reconstruction equals the real score across many random positions. That matters because the
 * breakdown is easy to get subtly wrong -- miss one term and the model trains against a feature
 * vector that does not describe the thing it is predicting, which would look exactly like slow
 * learning rather than a bug.
 */
public final class EvalBreakdown {

    /**
     * Term order. This is a WIRE FORMAT: weight files and trained models are keyed by position, so
     * appending is safe and reordering silently mismatches every previously trained vector against
     * the wrong term. Same rule as {@code StateFeatures.NAMES}, and the same trap that cost a whole
     * training run when {@code OnlineTDLearner.SCALE} drifted out of step with it.
     */
    public static final String[] TERMS = {
            "life_above",                    // lifeAboveMultiplier
            "hand_cards",                    // handCardScore
            "commander_damage",              // commanderDamageWeight
            "stack_objects",                 // stackObjectWeight
            "commander_permanents",          // commanderPermanentBonus
            "mana_sources",                  // manaSourceValue
            "unspent_mana",                  // unspentManaPenalty
            "deployed_mana_value",           // deployedManaValueWeight
            "draw_engines",                  // drawEngineBonus
            "permanents_on_battlefield",     // permanentOnBattlefieldBonus
            "equipment_permanents",          // equipmentPermanentBonus
            "creature_power",                // creaturePowerMultiplier
            "creature_toughness",            // creatureToughnessMultiplier
            "charge_counters",               // chargeCounterScore
            "level_counters",                // levelCounterScore
            "damage_marked",                 // damageMarkedPenalty
            "cannot_attack",                 // cannotAttackPenalty
            "cannot_block",                  // cannotBlockPenalty
            "tapped_creatures",              // tappedCreaturePenalty
            "tapped_lands",                  // tappedLandPenalty
            "tapped_other",                  // tappedOtherPenalty
            "detrimental_own_auras",         // detrimentalOwnAuraPenalty
    };

    public static final int SIZE = TERMS.length;

    private final double[] quantities = new double[SIZE];

    /** Add {@code amount} to the quantity behind term {@code index}. */
    public void add(int index, double amount) {
        quantities[index] += amount;
    }

    public double get(int index) {
        return quantities[index];
    }

    public double[] toArray() {
        return quantities.clone();
    }

    /** Subtract another player's breakdown, giving the differential features the learner wants. */
    public double[] minus(EvalBreakdown other) {
        double[] out = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            out[i] = this.quantities[i] - other.quantities[i];
        }
        return out;
    }

    /** The weight for each term, in TERMS order, so a caller can reconstruct the score. */
    public static int[] weightsOf(CommanderEvalParams p) {
        return new int[]{
                p.getLifeAboveMultiplier(),
                p.getHandCardScore(),
                p.getCommanderDamageWeight(),
                p.getStackObjectWeight(),
                p.getCommanderPermanentBonus(),
                p.getManaSourceValue(),
                -p.getUnspentManaPenalty(),
                p.getDeployedManaValueWeight(),
                p.getDrawEngineBonus(),
                p.getPermanentOnBattlefieldBonus(),
                p.getEquipmentPermanentBonus(),
                p.getCreaturePowerMultiplier(),
                p.getCreatureToughnessMultiplier(),
                p.getChargeCounterScore(),
                p.getLevelCounterScore(),
                -p.getDamageMarkedPenalty(),
                p.getCannotAttackPenalty(),
                p.getCannotBlockPenalty(),
                p.getTappedCreaturePenalty(),
                p.getTappedLandPenalty(),
                p.getTappedOtherPenalty(),
                p.getDetrimentalOwnAuraPenalty(),
        };
    }

    /** Reconstructed score: sum of weight * quantity. Used by the invariant test. */
    public double reconstruct(CommanderEvalParams p) {
        int[] w = weightsOf(p);
        double total = 0;
        for (int i = 0; i < SIZE; i++) {
            total += w[i] * quantities[i];
        }
        return total;
    }
}
