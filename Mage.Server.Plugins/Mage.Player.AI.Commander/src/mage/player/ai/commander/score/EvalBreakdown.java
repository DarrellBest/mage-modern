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
 * changes nothing. {@code maxTabulatedLife} is structural (a table bound) for the same reason, and
 * so is {@code overextensionMargin}: it is a THRESHOLD on a creature count, so it multiplies nothing
 * and no linear weight can represent it.
 *
 * <b>What is tested, precisely.</b> {@code EvalBreakdownTest} pins the {@link #TERMS} /
 * {@link #weightsOf} correspondence: every term has a weight, and bumping one parameter moves
 * exactly the one weight named after it. That is the invariant an append can silently break --
 * adding a name here and forgetting the matching entry in {@code weightsOf} shifts every later
 * weight onto the wrong term, throws nothing, and looks like the model quietly getting worse.
 * {@code StateFeatureSeedTest.everyTunableEvaluatorTermHasAFeatureCarryingIt} pins the other half:
 * that some learner feature actually carries each term.
 * <p>
 * DARRELLBEST-FORK: this paragraph used to claim that a test asserted the RECONSTRUCTION equals the
 * real evaluator score across many random positions. No such test existed, and it could not have --
 * nothing in the repo ever populates a breakdown, so {@link #add} and {@link #reconstruct} have no
 * producer to check against. The class is, today, a term registry with reconstruction plumbing kept
 * ready for a caller. Saying so is worth more than a comment that describes coverage nobody has.
 */
public final class EvalBreakdown {

    /**
     * Term order. This is a WIRE FORMAT: weight files and trained models are keyed by position, so
     * appending is safe and reordering silently mismatches every previously trained vector against
     * the wrong term. Same rule as {@code StateFeatures.NAMES}, and the same trap that cost a whole
     * training run when {@code OnlineTDLearner}'s parallel scale array drifted out of step with it.
     * <p>
     * DARRELLBEST-FORK: that particular trap no longer exists on the StateFeatures side -- name,
     * scale and seed are one table there now, so they cannot be appended to separately. This array
     * is still a lone list and still has to be edited in step with the terms it describes.
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

            // ---------------------------------------------------------------------------------
            // DARRELLBEST-FORK: APPENDED. Everything above keeps its position forever; see the
            // wire-format note. Each of these is a concept the evaluator could not state at all
            // before, not a re-tuning of one it could.
            // ---------------------------------------------------------------------------------

            // mustAnswerBonus. Quantity is a COUNT OF SIGNALS across the opponent's permanents (a
            // permanent can show several), not a count of permanents, and it is charged on the
            // opponent's side -- so its contribution to getTotalScore is NEGATIVE for us and is
            // recovered by removing the permanent. The feature carrying it is seeded accordingly.
            "must_answer",                   // mustAnswerBonus
            // overextensionPenalty. Quantity is own creatures beyond (their creatures + margin);
            // the margin itself is structural and has no term, like maxTabulatedLife.
            "overextension",                 // overextensionPenalty
            // commanderRecastPenalty. Quantity is the part of the commander tax the player cannot
            // currently pay, and is zero whenever the commander is on the battlefield.
            "commander_recast",              // commanderRecastPenalty
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
                // Negated: the quantity counts signals on the OPPONENT's permanents, which the
                // evaluator adds to the opponent's side, so its effect on the differential score is
                // minus the weight per signal. Same treatment as unspent_mana and damage_marked
                // above -- the sign lives here, with the term, not in the quantity.
                -p.getMustAnswerBonus(),
                -p.getOverextensionPenalty(),
                -p.getCommanderRecastPenalty(),
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
