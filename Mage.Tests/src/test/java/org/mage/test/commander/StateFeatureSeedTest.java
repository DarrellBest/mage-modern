package org.mage.test.commander;

import mage.player.ai.commander.learn.OnlineTDLearner;
import mage.player.ai.commander.learn.StateFeatures;
import mage.player.ai.commander.score.CommanderEvalParams;
import mage.player.ai.commander.score.EvalBreakdown;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DARRELLBEST-FORK: pins down the contract between the hand-tuned evaluator and the learned model.
 * <p>
 * These are plain unit tests, not games, for the same reason as {@link FederatedLearningTest}: the
 * failures being guarded against are silent ones. The bug this file exists for produced no
 * exception, no log line and no visibly broken bot -- {@code seedFromParams} wrote
 * {@code w[i] = param * LOGIT_SCALE}, which is the right answer only if the model computes
 * {@code sum(w[i] * f[i])}. {@link OnlineTDLearner} actually computes
 * {@code sum(w[i] * f[i] / scale[i])} with scales from 1 to 60, so every seeded weight was quietly
 * divided by a different constant. The "seed the model as the tuned evaluator" claim -- the entire
 * justification for the design -- was false, and the relative importance of the terms was scrambled
 * by up to 60x.
 * <p>
 * Nothing guarded that, because the only check that existed compared ARRAY LENGTHS. Lengths agreeing
 * says the two lists have the same number of entries; it says nothing about whether the numbers in
 * them mean the same thing. What follows asserts the semantics instead: for every feature, the
 * EFFECTIVE coefficient the model applies to the raw feature value must be exactly
 * {@code param * LOGIT_SCALE}, whatever normaliser sits in between.
 *
 * @author Darrell Best
 */
public class StateFeatureSeedTest {

    /**
     * Comfortably tighter than any real error: the quantities compared are exact products of
     * doubles, so the only slack needed is for the sigmoid round trip in
     * {@link #seedSurvivesTheRoundTripThroughTheLearner}.
     */
    private static final double EPSILON = 1e-9;

    /**
     * The intended correspondence between each feature and the tuned evaluator, restated here
     * INDEPENDENTLY of the table that produces it.
     * <p>
     * Deliberately a duplicate. A test that asked StateFeatures what it seeds and then checked that
     * StateFeatures seeds that would pass no matter what the table said. Writing the mapping out a
     * second time is what makes this a test of the intent rather than of the implementation.
     * <p>
     * An entry of 0 means "no honest tuned counterpart, seed to nothing and learn it".
     */
    private static Map<String, Integer> intendedParams(CommanderEvalParams p) {
        Map<String, Integer> e = new LinkedHashMap<>();
        e.put("life_diff", p.getLifeAboveMultiplier());
        e.put("hand_size_diff", p.getHandCardScore());
        e.put("creature_count_diff", p.getPermanentOnBattlefieldBonus());
        e.put("creature_power_diff", p.getCreaturePowerMultiplier());
        e.put("creature_toughness_diff", p.getCreatureToughnessMultiplier());
        e.put("land_count_diff", p.getLandBaseMultiplier());
        e.put("untapped_land_diff", -p.getTappedLandPenalty());
        e.put("artifact_count_diff", p.getPermanentOnBattlefieldBonus());
        e.put("enchantment_count_diff", p.getPermanentOnBattlefieldBonus());
        e.put("planeswalker_count_diff", p.getPermanentOnBattlefieldBonus());
        e.put("commander_on_battlefield_diff", p.getCommanderPermanentBonus());
        e.put("library_size_diff", 0);
        e.put("turn_number", 0);
        e.put("deployed_mana_value_diff", p.getDeployedManaValueWeight());
        e.put("unspent_mana_own_turn", p.getManaSourceValue() - p.getUnspentManaPenalty());
        e.put("draw_engine_count_diff", p.getDrawEngineBonus());
        e.put("my_life", 0);
        e.put("min_opp_life", 0);
        // 21 commander damage is lethal, so the weight -- which prices the whole clock -- is worth
        // weight/21 per point to a linear feature over 0..21. Integer division, matching the table.
        e.put("commander_damage_out", p.getCommanderDamageWeight() / 21);
        e.put("commander_damage_in", -(p.getCommanderDamageWeight() / 21));
        e.put("evasive_power_diff", 0);
        e.put("tapped_creature_diff", p.getTappedCreaturePenalty());
        e.put("graveyard_size_diff", 0);
        e.put("lethality", 0);
        e.put("life_danger", 0);
        e.put("commander_cast_count", 0);

        // The nine tunable evaluator terms that had no feature until now. Each is restated from the
        // evaluator SOURCE rather than from StateFeatures, which is the whole point of this map --
        // several of these are places where the parameter's name and the code's behaviour differ,
        // and a mapping copied from the table under test would agree with the table by construction
        // and catch nothing.
        //
        // stack_objects: GameStateEvaluator2 charges stackObjectWeight only for stack objects that
        // are NOT spells; a spell is priced at its card-definition score instead.
        e.put("stack_objects", p.getStackObjectWeight());
        e.put("equipment_permanents", p.getEquipmentPermanentBonus());
        e.put("charge_counters", p.getChargeCounterScore());
        e.put("level_counters", p.getLevelCounterScore());
        // damageMarkedPenalty is SUBTRACTED per point of marked damage, so the coefficient on a
        // damage count is its negation -- the same sign EvalBreakdown.weightsOf gives it.
        e.put("damage_marked", -p.getDamageMarkedPenalty());
        // These three are already negative in the params and are ADDED by the evaluator, so they
        // carry over unchanged.
        e.put("cannot_attack", p.getCannotAttackPenalty());
        e.put("cannot_block", p.getCannotBlockPenalty());
        e.put("tapped_other", p.getTappedOtherPenalty());
        e.put("detrimental_own_auras", p.getDetrimentalOwnAuraPenalty());

        // Concepts with no tuned counterpart: seed 0 and learn them.
        e.put("board_texture", 0);
        e.put("untapped_blockers_diff", 0);
        e.put("evasive_lethality", 0);
        e.put("planeswalker_loyalty_diff", 0);
        // The exception in that group. ArtificialScoringSystem charges tappedCreaturePenalty through
        // !canTap(), which for a creature is "tapped OR summoning-sick" -- tapped_creature_diff only
        // ever covered the first half, and this is the second half at the same weight.
        e.put("summoning_sick_diff", p.getTappedCreaturePenalty());
        e.put("hand_land_count", 0);
        e.put("hand_castable_now", 0);
        e.put("hand_avg_mana_value", 0);
        e.put("interaction_held", 0);
        // Spells on the stack are priced by getCardDefinitionScore, which is five parameters
        // combined, not a scalar -- so there is nothing honest to copy.
        e.put("stack_spell_diff", 0);

        // The three terms appended with these features. All three seed NEGATIVE, restated from the
        // evaluator source rather than from the feature table: GameStateEvaluator2 subtracts
        // overextension and the commander recast tax from the PLAYER's side, and adds must-answer to
        // the OPPONENT's side, and getTotalScore is player minus opponent -- so all three reduce the
        // score, which is the sign EvalBreakdown.weightsOf also gives them. A feature seeded with the
        // wrong sign here would start the model believing an opposing Rhystic Study is good for us.
        e.put("opp_must_answer", -p.getMustAnswerBonus());
        e.put("overextension", -p.getOverextensionPenalty());
        e.put("commander_recast_tax", -p.getCommanderRecastPenalty());
        return e;
    }

    /**
     * The features that already have trained models on disk, in the order they must keep forever.
     * Archived weight files and the offline fitting tooling key off these positions, so a reorder
     * re-points every trained weight at the wrong feature -- which throws nothing and looks like the
     * model having quietly got worse.
     * <p>
     * This listed only the original 16 while indices 16..25 were still new. They are no longer new:
     * models have been trained and archived in that 26-wide format, so all 26 are now wire format
     * and are pinned here. The rule for the entries beyond them is the same one, on a delay -- an
     * appended feature is free to move until the first model is trained with it, and frozen after.
     */
    private static final String[] FROZEN_PREFIX = {
            "life_diff",
            "hand_size_diff",
            "creature_count_diff",
            "creature_power_diff",
            "creature_toughness_diff",
            "land_count_diff",
            "untapped_land_diff",
            "artifact_count_diff",
            "enchantment_count_diff",
            "planeswalker_count_diff",
            "commander_on_battlefield_diff",
            "library_size_diff",
            "turn_number",
            "deployed_mana_value_diff",
            "unspent_mana_own_turn",
            "draw_engine_count_diff",
            "my_life",
            "min_opp_life",
            "commander_damage_out",
            "commander_damage_in",
            "evasive_power_diff",
            "tapped_creature_diff",
            "graveyard_size_diff",
            "lethality",
            "life_danger",
            "commander_cast_count",
    };

    /**
     * Which feature carries each tunable evaluator term. Term names are
     * {@link EvalBreakdown#TERMS}; feature names are {@link StateFeatures#NAMES}.
     * <p>
     * This is the COVERAGE contract, and it is the one the test file did not previously state. The
     * seed invariant below asks "is each feature seeded from the right parameter"; it cannot ask
     * "is every parameter represented by some feature", because a weight with no feature at all does
     * not appear in the vector to be checked. That was the real gap: nine of the twenty-two terms
     * had no feature, so the model could not express the tuned bot's opinion about them at any
     * weight, and every measured learner-versus-evaluator difference was partly just missing
     * vocabulary rather than genuine disagreement.
     * <p>
     * Two terms map onto one feature: manaSourceValue and unspentManaPenalty are the same axis with
     * opposite signs, and unspent_mana_own_turn seeds from their difference. Four terms map onto
     * features whose relationship to the parameter is not the identity -- tapped_lands is carried by
     * untapped_land_diff at the negated weight, commander_damage by a linear feature over the 0..21
     * clock, and permanents_on_battlefield by whichever of the three type counts is nearest -- which
     * is why the seed values themselves are asserted separately, from {@link #intendedParams}.
     */
    private static final String[][] TERM_TO_FEATURE = {
            {"life_above", "life_diff"},
            {"hand_cards", "hand_size_diff"},
            {"commander_damage", "commander_damage_out"},
            {"stack_objects", "stack_objects"},
            {"commander_permanents", "commander_on_battlefield_diff"},
            {"mana_sources", "unspent_mana_own_turn"},
            {"unspent_mana", "unspent_mana_own_turn"},
            {"deployed_mana_value", "deployed_mana_value_diff"},
            {"draw_engines", "draw_engine_count_diff"},
            {"permanents_on_battlefield", "creature_count_diff"},
            {"equipment_permanents", "equipment_permanents"},
            {"creature_power", "creature_power_diff"},
            {"creature_toughness", "creature_toughness_diff"},
            {"charge_counters", "charge_counters"},
            {"level_counters", "level_counters"},
            {"damage_marked", "damage_marked"},
            {"cannot_attack", "cannot_attack"},
            {"cannot_block", "cannot_block"},
            {"tapped_creatures", "tapped_creature_diff"},
            {"tapped_lands", "untapped_land_diff"},
            {"tapped_other", "tapped_other"},
            {"detrimental_own_auras", "detrimental_own_auras"},
            // Appended with the terms themselves. Each of these three is a one-to-one term/feature
            // pair computing the identical quantity on both sides -- which is the easiest kind to
            // get right and the easiest to let rot, since nothing but this table says they must
            // stay in step.
            {"must_answer", "opp_must_answer"},
            {"overextension", "overextension"},
            {"commander_recast", "commander_recast_tax"},
    };

    @Test
    public void nameScaleAndSeedStayOneTable() {
        Assert.assertEquals("NAMES must be derived from the same table as the scales",
                StateFeatures.SIZE, StateFeatures.NAMES.length);
        Assert.assertEquals("scales() must be derived from the same table as the names",
                StateFeatures.SIZE, StateFeatures.scales().length);
        Assert.assertEquals("the seed vector must cover every feature",
                StateFeatures.SIZE, StateFeatures.seedFromParams(CommanderEvalParams.TUNED).length);
    }

    @Test
    public void everyScaleIsUsableAsADivisor() {
        double[] scales = StateFeatures.scales();
        for (int i = 0; i < scales.length; i++) {
            Assert.assertTrue(StateFeatures.NAMES[i] + " has a non-positive scale, and the model "
                    + "divides by it", scales[i] > 0.0);
        }
    }

    @Test
    public void scalesCannotBeMutatedByACaller() {
        double[] first = StateFeatures.scales();
        first[0] = -999.0;
        Assert.assertNotEquals("scales() must hand out a copy, not the table itself",
                -999.0, StateFeatures.scales()[0], 0.0);
    }

    @Test
    public void frozenFeaturePrefixKeepsItsOrder() {
        Assert.assertTrue("the table may only grow, never shrink below the frozen prefix",
                StateFeatures.SIZE >= FROZEN_PREFIX.length);
        for (int i = 0; i < FROZEN_PREFIX.length; i++) {
            Assert.assertEquals("index " + i + " is a wire-format position and must not move",
                    FROZEN_PREFIX[i], StateFeatures.NAMES[i]);
        }
    }

    @Test
    public void everyFeatureHasADeclaredIntent() {
        Map<String, Integer> intended = intendedParams(CommanderEvalParams.TUNED);
        for (String name : StateFeatures.NAMES) {
            Assert.assertTrue("feature '" + name + "' was added to StateFeatures without saying "
                            + "here what it should seed from -- add it to intendedParams",
                    intended.containsKey(name));
        }
        Assert.assertEquals("intendedParams names a feature that no longer exists: "
                        + Arrays.toString(StateFeatures.NAMES),
                StateFeatures.SIZE, intended.size());
    }

    /**
     * Every tunable evaluator term must be representable by the model.
     * <p>
     * Fails in two directions on purpose. A new weight added to {@link EvalBreakdown#TERMS} with no
     * feature behind it fails here, which is the case that previously went unnoticed nine times
     * over. A feature declared as carrying a term but seeding to nothing under the weights the bot
     * actually plays with also fails, because "the feature exists" is not coverage if the seed does
     * not put the evaluator's opinion into it.
     */
    @Test
    public void everyTunableEvaluatorTermHasAFeatureCarryingIt() {
        Map<String, Integer> intended = intendedParams(CommanderEvalParams.TUNED);
        Map<String, String> byTerm = new LinkedHashMap<>();
        for (String[] pair : TERM_TO_FEATURE) {
            byTerm.put(pair[0], pair[1]);
        }

        for (String term : EvalBreakdown.TERMS) {
            String feature = byTerm.get(term);
            Assert.assertNotNull("evaluator term '" + term + "' has no feature declared for it -- "
                    + "the learner cannot represent what the evaluator does with it, at any weight. "
                    + "Add a feature and name it in TERM_TO_FEATURE", feature);
            Assert.assertTrue("TERM_TO_FEATURE points term '" + term + "' at feature '" + feature
                            + "', which is not in StateFeatures.NAMES",
                    Arrays.asList(StateFeatures.NAMES).contains(feature));
            Assert.assertNotEquals("feature '" + feature + "' is declared to carry evaluator term '"
                            + term + "' but seeds to 0 under TUNED, so the tuned opinion about that "
                            + "term is not in the seeded model",
                    Integer.valueOf(0), intended.get(feature));
        }
        Assert.assertEquals("TERM_TO_FEATURE names a term EvalBreakdown no longer has",
                EvalBreakdown.SIZE, byTerm.size());
    }

    @Test
    public void seedLandsInLogitSpaceForTunedParams() {
        assertEffectiveCoefficients(CommanderEvalParams.TUNED);
    }

    /**
     * Repeated against DEFAULT because most of its weights are 0, so a seed lambda that ignored its
     * argument and returned a constant would pass against TUNED alone.
     */
    @Test
    public void seedLandsInLogitSpaceForDefaultParams() {
        assertEffectiveCoefficients(CommanderEvalParams.DEFAULT);
    }

    private void assertEffectiveCoefficients(CommanderEvalParams params) {
        double[] seed = StateFeatures.seedFromParams(params);
        double[] scales = StateFeatures.scales();
        Map<String, Integer> intended = intendedParams(params);

        for (int i = 0; i < StateFeatures.SIZE; i++) {
            String name = StateFeatures.NAMES[i];
            double expected = intended.get(name) * StateFeatures.LOGIT_SCALE;
            // This is the invariant that was violated. The model divides the weight by the scale, so
            // the coefficient it actually applies to the raw feature value is seed[i] / scale[i] --
            // NOT seed[i]. Before the fix this ratio was expected/scale[i], i.e. off by 40x for
            // life_diff and 60x for library_size_diff, and correct only for the one feature whose
            // scale happened to be 1.
            double effective = seed[i] / scales[i];
            Assert.assertEquals("feature '" + name + "' (index " + i + ") must apply "
                            + "param * LOGIT_SCALE to its raw value",
                    expected, effective, EPSILON);
        }
    }

    /**
     * The same invariant, but measured through {@link OnlineTDLearner} instead of asserted about
     * {@link StateFeatures} in isolation.
     * <p>
     * This is the test that would actually have caught the bug. The two classes each looked
     * self-consistent; what was wrong was the assumption one made about the other, and only running
     * a vector through the real model exposes that. A unit-one feature vector makes the learner's
     * logit equal to that one feature's effective coefficient, which the sigmoid can be inverted to
     * recover.
     */
    @Test
    public void seedSurvivesTheRoundTripThroughTheLearner() {
        for (CommanderEvalParams params : new CommanderEvalParams[]{
                CommanderEvalParams.TUNED, CommanderEvalParams.DEFAULT}) {

            OnlineTDLearner learner = new OnlineTDLearner(StateFeatures.seedFromParams(params), 0.0);
            Map<String, Integer> intended = intendedParams(params);

            for (int i = 0; i < StateFeatures.SIZE; i++) {
                double[] oneHot = new double[StateFeatures.SIZE];
                oneHot[i] = 1.0;

                double probability = learner.predict(oneHot);
                double logit = Math.log(probability / (1.0 - probability));

                String name = StateFeatures.NAMES[i];
                Assert.assertEquals("one unit of '" + name + "' must move the model's logit by "
                                + "exactly param * LOGIT_SCALE",
                        intended.get(name) * StateFeatures.LOGIT_SCALE, logit, EPSILON);
            }
        }
    }

    /**
     * A weight vector of the wrong length used to run off the end of the scales inside the model,
     * which is what happened every time the two feature lists disagreed. There is now one list, so
     * this can only arise from a caller passing something hand-built.
     */
    @Test(expected = IllegalArgumentException.class)
    public void learnerRefusesAWeightVectorOfTheWrongLength() {
        new OnlineTDLearner(new double[StateFeatures.SIZE - 1], 0.0);
    }
}
