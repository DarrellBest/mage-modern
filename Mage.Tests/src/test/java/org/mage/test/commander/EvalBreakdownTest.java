package org.mage.test.commander;

import mage.player.ai.commander.score.CommanderEvalParams;
import mage.player.ai.commander.score.EvalBreakdown;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DARRELLBEST-FORK: pins the {@link EvalBreakdown#TERMS} / {@link EvalBreakdown#weightsOf}
 * correspondence -- that term i really is priced by the parameter term i is named after.
 * <p>
 * <b>Why this file exists.</b> {@code EvalBreakdown}'s own javadoc used to claim that a test asserted
 * its reconstruction against real evaluator scores. No such test existed anywhere in the repo, and it
 * could not have: nothing populates a breakdown, so {@code add}/{@code reconstruct} have no producer
 * to be checked against. What DOES need guarding is the thing an append can silently break. TERMS is
 * a wire format and {@code weightsOf} is a parallel array literal beside it; adding a name to one and
 * forgetting the other shifts every later weight onto the wrong term. That throws nothing, compiles
 * cleanly, and shows up only as a model that quietly got worse -- the exact failure mode the
 * StateFeatures NAMES/SCALE drift produced before those two were merged into one table.
 * <p>
 * The mapping below is written out a SECOND time rather than read from {@code weightsOf}, for the
 * same reason {@code StateFeatureSeedTest.intendedParams} is: a test that asked the code what it does
 * and then asserted it does that would pass whatever the code said.
 *
 * @author Darrell Best
 */
public class EvalBreakdownTest {

    /**
     * For each term, the same params with EXACTLY the parameter that term is named for moved by one.
     * <p>
     * One step is enough and is deliberately not larger: the assertion is about WHICH weight moves,
     * not by how much, and a step of one cannot accidentally collide with another weight's value.
     */
    private static Map<String, CommanderEvalParams> bumpedByTerm() {
        CommanderEvalParams p = CommanderEvalParams.TUNED;
        Map<String, CommanderEvalParams> m = new LinkedHashMap<>();
        m.put("life_above", p.toBuilder().lifeAboveMultiplier(p.getLifeAboveMultiplier() + 1).build());
        m.put("hand_cards", p.toBuilder().handCardScore(p.getHandCardScore() + 1).build());
        m.put("commander_damage", p.toBuilder().commanderDamageWeight(p.getCommanderDamageWeight() + 1).build());
        m.put("stack_objects", p.toBuilder().stackObjectWeight(p.getStackObjectWeight() + 1).build());
        m.put("commander_permanents", p.toBuilder().commanderPermanentBonus(p.getCommanderPermanentBonus() + 1).build());
        m.put("mana_sources", p.toBuilder().manaSourceValue(p.getManaSourceValue() + 1).build());
        m.put("unspent_mana", p.toBuilder().unspentManaPenalty(p.getUnspentManaPenalty() + 1).build());
        m.put("deployed_mana_value", p.toBuilder().deployedManaValueWeight(p.getDeployedManaValueWeight() + 1).build());
        m.put("draw_engines", p.toBuilder().drawEngineBonus(p.getDrawEngineBonus() + 1).build());
        m.put("permanents_on_battlefield", p.toBuilder().permanentOnBattlefieldBonus(p.getPermanentOnBattlefieldBonus() + 1).build());
        m.put("equipment_permanents", p.toBuilder().equipmentPermanentBonus(p.getEquipmentPermanentBonus() + 1).build());
        m.put("creature_power", p.toBuilder().creaturePowerMultiplier(p.getCreaturePowerMultiplier() + 1).build());
        m.put("creature_toughness", p.toBuilder().creatureToughnessMultiplier(p.getCreatureToughnessMultiplier() + 1).build());
        m.put("charge_counters", p.toBuilder().chargeCounterScore(p.getChargeCounterScore() + 1).build());
        m.put("level_counters", p.toBuilder().levelCounterScore(p.getLevelCounterScore() + 1).build());
        m.put("damage_marked", p.toBuilder().damageMarkedPenalty(p.getDamageMarkedPenalty() + 1).build());
        m.put("cannot_attack", p.toBuilder().cannotAttackPenalty(p.getCannotAttackPenalty() + 1).build());
        m.put("cannot_block", p.toBuilder().cannotBlockPenalty(p.getCannotBlockPenalty() + 1).build());
        m.put("tapped_creatures", p.toBuilder().tappedCreaturePenalty(p.getTappedCreaturePenalty() + 1).build());
        m.put("tapped_lands", p.toBuilder().tappedLandPenalty(p.getTappedLandPenalty() + 1).build());
        m.put("tapped_other", p.toBuilder().tappedOtherPenalty(p.getTappedOtherPenalty() + 1).build());
        m.put("detrimental_own_auras", p.toBuilder().detrimentalOwnAuraPenalty(p.getDetrimentalOwnAuraPenalty() + 1).build());
        m.put("must_answer", p.toBuilder().mustAnswerBonus(p.getMustAnswerBonus() + 1).build());
        m.put("overextension", p.toBuilder().overextensionPenalty(p.getOverextensionPenalty() + 1).build());
        m.put("commander_recast", p.toBuilder().commanderRecastPenalty(p.getCommanderRecastPenalty() + 1).build());
        return m;
    }

    @Test
    public void everyTermHasAWeight() {
        Assert.assertEquals("weightsOf must produce one weight per term, in TERMS order -- a term "
                        + "appended without a matching entry shifts every later weight",
                EvalBreakdown.SIZE, EvalBreakdown.weightsOf(CommanderEvalParams.TUNED).length);
    }

    @Test
    public void everyTermIsNamedInThisTestsOwnMapping() {
        Map<String, CommanderEvalParams> bumped = bumpedByTerm();
        for (String term : EvalBreakdown.TERMS) {
            Assert.assertTrue("term '" + term + "' was appended to EvalBreakdown.TERMS without "
                            + "saying here which parameter prices it -- add it to bumpedByTerm",
                    bumped.containsKey(term));
        }
        Assert.assertEquals("bumpedByTerm names a term EvalBreakdown no longer has: "
                        + Arrays.toString(EvalBreakdown.TERMS),
                EvalBreakdown.SIZE, bumped.size());
    }

    /**
     * The alignment assertion. Moving one parameter must move the weight at that parameter's own
     * term index and NOTHING else -- which fails loudly both when a term's entry is missing (no
     * weight moves) and when the two lists have drifted out of step (the wrong index moves).
     */
    @Test
    public void bumpingOneParameterMovesExactlyItsOwnWeight() {
        int[] base = EvalBreakdown.weightsOf(CommanderEvalParams.TUNED);
        Map<String, CommanderEvalParams> bumped = bumpedByTerm();

        for (int i = 0; i < EvalBreakdown.SIZE; i++) {
            String term = EvalBreakdown.TERMS[i];
            int[] moved = EvalBreakdown.weightsOf(bumped.get(term));
            for (int j = 0; j < EvalBreakdown.SIZE; j++) {
                if (i == j) {
                    Assert.assertNotEquals("moving the parameter behind term '" + term + "' did not "
                                    + "move the weight at its own index " + i
                                    + " -- weightsOf is missing that term or has it in another slot",
                            base[j], moved[j]);
                } else {
                    Assert.assertEquals("moving the parameter behind term '" + term + "' also moved "
                                    + "the weight for term '" + EvalBreakdown.TERMS[j] + "' (index "
                                    + j + ") -- TERMS and weightsOf have drifted out of step",
                            base[j], moved[j]);
                }
            }
        }
    }

    /**
     * The reconstruction identity the class is built around: score == sum of weight * quantity.
     * <p>
     * Checked against an independently summed total rather than against a real evaluator score,
     * honestly, because nothing populates a breakdown from a real evaluation yet. This pins the
     * arithmetic and the indexing so that a caller wiring one up later inherits a class that adds up.
     */
    @Test
    public void reconstructIsTheWeightedSumOfTheQuantities() {
        CommanderEvalParams params = CommanderEvalParams.TUNED;
        int[] weights = EvalBreakdown.weightsOf(params);

        EvalBreakdown breakdown = new EvalBreakdown();
        double expected = 0;
        for (int i = 0; i < EvalBreakdown.SIZE; i++) {
            // Deliberately uneven and partly negative: an off-by-one in the indexing survives a
            // uniform quantity vector, and a sign error survives an all-positive one.
            double quantity = (i % 3 == 0 ? -1 : 1) * (i + 1) * 0.5;
            breakdown.add(i, quantity);
            expected += weights[i] * quantity;
        }

        Assert.assertEquals("reconstruct must be the weighted sum of the quantities",
                expected, breakdown.reconstruct(params), 1e-9);
        Assert.assertEquals("toArray must hand back one quantity per term, in TERMS order",
                EvalBreakdown.SIZE, breakdown.toArray().length);
    }

    @Test
    public void addAccumulatesAndMinusGivesTheDifferential() {
        EvalBreakdown mine = new EvalBreakdown();
        EvalBreakdown theirs = new EvalBreakdown();
        mine.add(0, 2.0);
        mine.add(0, 3.0);
        theirs.add(0, 1.5);

        Assert.assertEquals("add must accumulate rather than replace", 5.0, mine.get(0), 1e-9);
        double[] differential = mine.minus(theirs);
        Assert.assertEquals("minus must subtract term by term", 3.5, differential[0], 1e-9);
        Assert.assertEquals("untouched terms stay 0", 0.0, differential[EvalBreakdown.SIZE - 1], 1e-9);
    }

    /** The quantities must not be reachable through the array the breakdown hands out. */
    @Test
    public void toArrayIsACopy() {
        EvalBreakdown breakdown = new EvalBreakdown();
        breakdown.add(1, 7.0);
        breakdown.toArray()[1] = -999.0;
        Assert.assertEquals("toArray must hand out a copy, not the live quantities",
                7.0, breakdown.get(1), 1e-9);
    }
}
