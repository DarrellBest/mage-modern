package org.mage.test.commander;

import mage.constants.RangeOfInfluence;
import mage.player.ai.commander.ComputerPlayer6;
import mage.player.ai.commander.ComputerPlayer7;
import mage.player.ai.commander.ComputerPlayerCommander;
import mage.player.ai.commander.ComputerPlayerControllableProxy;
import mage.player.ai.commander.ComputerPlayerLearner;
import mage.player.ai.commander.score.CommanderEvalParams;
import org.junit.Assert;
import org.junit.Test;

/**
 * DARRELLBEST-FORK: proves that injected evaluation weights survive {@code copy()} on every player in
 * the commander AI chain.
 * <p>
 * <b>Why this test is not paranoia.</b> {@code ComputerPlayer6}'s copy constructor copies maxDepth,
 * currentScore, combat, actions, targets, choices and actionCache -- and silently omits {@code maxNodes}
 * and {@code maxThinkTimeSecs}. That omission is inherited from upstream and is live in the code today:
 * a player copy quietly reverts those two to whatever the constructor set. It is a working
 * demonstration of the exact trap this test guards, because the search does not use the original
 * player object for most of its work -- it copies. So a weight that does not survive a copy is a
 * weight the search never sees, while {@code getEvalParams()} on the original still cheerfully reports
 * the tuned value. There is no exception, no log line, and no compiler error; the bot simply plays
 * with stock weights while appearing to accept a tuned config, and every tuning experiment run against
 * it silently measures nothing.
 * <p>
 * <b>What would make this test fail.</b> Sabotaging any link in the chain -- {@code ComputerPlayer6}'s
 * copy constructor assigning {@code CommanderEvalParams.DEFAULT} instead of {@code player.evalParams},
 * or any subclass's copy constructor routing through the fresh-player constructor instead of
 * {@code super(player)}. Both were tried against this test while writing it; both turn it red.
 * (Outright DELETING the assignment does not compile, because {@code evalParams} is final -- that is
 * deliberate belt-and-braces, not a reason to skip the test, since assigning the WRONG value compiles
 * perfectly well.)
 * <p>
 * The tuned params below differ from the defaults in EVERY field, so a partially-propagated params
 * object fails just as loudly as a wholly missing one.
 *
 * @author Darrell Best
 */
public class CommanderEvalParamsCopyTest {

    private static final int SKILL = 4;

    /**
     * Deliberately absurd: every value differs from its default, and none is a plausible typo of one.
     * The life table is also a different LENGTH from the default's, so {@code getMaxTabulatedLife()}
     * (which is derived from it) moves too.
     */
    private static CommanderEvalParams tuned() {
        return CommanderEvalParams.builder()
                .lifeScores(0, 7, 14, 21)
                .lifeAboveMultiplier(101)
                .handCardScore(6)
                .baseCardValue(4)
                .landBaseMultiplier(51)
                .landPerManaSymbol(52)
                .nonLandBaseMultiplier(102)
                .manaValuePenaltyPerPip(21)
                .cardPowerToughnessMultiplier(11)
                .rarityMultiplier(31)
                .permanentOnBattlefieldBonus(301)
                .equipmentPermanentBonus(103)
                .chargeCounterScore(32)
                .levelCounterScore(33)
                .damageMarkedPenalty(3)
                .creaturePowerMultiplier(302)
                .creatureToughnessMultiplier(201)
                .abilityScorePowerOffset(2)
                .abilityScoreDivisor(3)
                .attachedEnchantmentOutcomeMultiplier(104)
                .attachedEquipmentOutcomeMultiplier(53)
                .cannotAttackPenalty(-101)
                .cannotBlockPenalty(-31)
                .tappedCreaturePenalty(-102)
                .tappedLandPenalty(-22)
                .tappedOtherPenalty(-4)
                .detrimentalOwnAuraPenalty(-1001)
                .build();
    }

    // ---- one test per copy constructor in the chain -------------------------------------------
    //
    // Separate tests rather than one loop, so a failure names the exact class whose copy constructor
    // dropped the field instead of just "something in the hierarchy did".

    @Test
    public void computerPlayer6KeepsTunedParamsAcrossCopy() {
        CommanderEvalParams params = tuned();
        ComputerPlayer6 original = new ComputerPlayer6("cp6", RangeOfInfluence.ALL, SKILL, params);
        assertParamsSurviveCopy(params, original, original.copy());
    }

    @Test
    public void computerPlayer7KeepsTunedParamsAcrossCopy() {
        CommanderEvalParams params = tuned();
        ComputerPlayer7 original = new ComputerPlayer7("cp7", RangeOfInfluence.ALL, SKILL, params);
        assertParamsSurviveCopy(params, original, original.copy());
    }

    @Test
    public void computerPlayerCommanderKeepsTunedParamsAcrossCopy() {
        CommanderEvalParams params = tuned();
        ComputerPlayerCommander original = new ComputerPlayerCommander("cmd", RangeOfInfluence.ALL, SKILL, params);
        assertParamsSurviveCopy(params, original, original.copy());
    }

    @Test
    public void computerPlayerLearnerKeepsTunedParamsAcrossCopy() {
        // The learner blends a learned score with the hand-tuned one, so its hand-tuned half is
        // computed from exactly these params -- losing them here would corrupt half of every score
        // it produces, which is even harder to notice than losing them outright.
        CommanderEvalParams params = tuned();
        ComputerPlayerLearner original = new ComputerPlayerLearner("learner", RangeOfInfluence.ALL, SKILL, params);
        assertParamsSurviveCopy(params, original, original.copy());
    }

    @Test
    public void computerPlayerControllableProxyKeepsTunedParamsAcrossCopy() {
        CommanderEvalParams params = tuned();
        ComputerPlayerControllableProxy original =
                new ComputerPlayerControllableProxy("proxy", RangeOfInfluence.ALL, SKILL, params);
        assertParamsSurviveCopy(params, original, original.copy());
    }

    // ---- properties of the mechanism itself --------------------------------------------------

    /**
     * The search copies copies. A chain that survives one hop but not five would still lose the
     * weights in a real game, and would still look fine to a single-hop test.
     */
    @Test
    public void paramsSurviveARepeatedlyCopiedPlayer() {
        CommanderEvalParams params = tuned();
        ComputerPlayer6 player = new ComputerPlayerCommander("deep", RangeOfInfluence.ALL, SKILL, params);
        for (int hop = 0; hop < 5; hop++) {
            player = player.copy();
            Assert.assertEquals("weights lost at copy hop " + (hop + 1),
                    params.getHandCardScore(), player.getEvalParams().getHandCardScore());
        }
        assertAllFieldsEqual(params, player.getEvalParams());
    }

    /**
     * Params are shared by reference, not cloned -- they are immutable, and the search makes thousands
     * of player copies per decision, so per-copy allocation would be pure waste. This is the same
     * treatment {@code ComputerPlayerLearner} gives its {@code federation} and {@code session}.
     */
    @Test
    public void copiesShareOneParamsInstanceRatherThanCloningIt() {
        CommanderEvalParams params = tuned();
        ComputerPlayerCommander original = new ComputerPlayerCommander("shared", RangeOfInfluence.ALL, SKILL, params);
        Assert.assertSame("the player must hold the caller's instance", params, original.getEvalParams());
        Assert.assertSame("copies must share it, not clone it", params, original.copy().getEvalParams());
    }

    /**
     * The legacy three-argument constructors are what config.xml and the bench harness call, so what
     * they hand back IS what live games play with. Every class except the deployed Commander bot
     * must keep producing the historical behaviour.
     */
    @Test
    public void constructorsWithoutParamsGetTheDefaults() {
        Assert.assertSame(CommanderEvalParams.DEFAULT,
                new ComputerPlayer6("a", RangeOfInfluence.ALL, SKILL).getEvalParams());
        Assert.assertSame(CommanderEvalParams.DEFAULT,
                new ComputerPlayer7("b", RangeOfInfluence.ALL, SKILL).getEvalParams());
        Assert.assertSame(CommanderEvalParams.DEFAULT,
                new ComputerPlayerLearner("d", RangeOfInfluence.ALL, SKILL).getEvalParams());
        Assert.assertSame(CommanderEvalParams.DEFAULT,
                new ComputerPlayerControllableProxy("e", RangeOfInfluence.ALL, SKILL).getEvalParams());
    }

    /**
     * ComputerPlayerCommander is the one class config.xml names, so its no-params constructor is
     * what the live server actually builds. It deliberately uses TUNED rather than DEFAULT.
     * <p>
     * Asserted separately and explicitly because a well-meaning "make it consistent with the others"
     * edit would silently revert the only tuning result we have measured evidence for, and nothing
     * else in the build would notice.
     */
    @Test
    public void deployedCommanderBotUsesTunedWeights() {
        CommanderEvalParams live = new ComputerPlayerCommander("c", RangeOfInfluence.ALL, SKILL).getEvalParams();
        Assert.assertSame("the deployed bot must use TUNED", CommanderEvalParams.TUNED, live);
        // handCardScore history, kept explicit because the number moved for two different reasons:
        //   5 -> 150  measured 57.8% over 42 games (p=0.058) -- but measured BEFORE the evaluator
        //             scored the stack, when casting a spell looked like losing a card outright, so
        //             part of what 150 bought was compensating for that bug.
        //   150 -> 60 stack scoring removed that bias, and a later isolated A/B put 150 against 40
        //             at 52.9% over 34 games: no detectable strength difference either way. 60 is
        //             chosen inside that indifference band to bias toward deploying cards rather
        //             than holding them, which is a deliberate play-feel choice and NOT a measured
        //             strength win. If a future measurement separates them, this is the line to revisit.
        Assert.assertEquals("TUNED biases toward deploying (see the history note above)",
                60, live.getHandCardScore());
        Assert.assertEquals("TUNED attacks on favourable trades, not only when safe",
                2, live.getAttackAggression());
        Assert.assertEquals("TUNED enables the commander-damage death clock",
                8000, live.getCommanderDamageWeight());
        Assert.assertEquals("TUNED evaluates modal abilities instead of taking the first legal mode",
                1, live.getModeSelectionMode());
        Assert.assertEquals("TUNED must differ from DEFAULT in exactly these nine settings",
                CommanderEvalParams.DEFAULT.toBuilder()
                        .handCardScore(60)
                        .commanderDamageWeight(8000)
                        .modeSelectionMode(1)
                        .attackAggression(2)
                        .multiplayerAttackSplit(1)
                        .declineLosingManaPayments(1)
                        .smartMulligan(1)
                        .stackObjectWeight(150)
                        .drawEngineBonus(400)
                        .build().toString(),
                CommanderEvalParams.TUNED.toString());
    }

    /**
     * Pins the historical weights. If someone "cleans up" a default, every equivalence claim made
     * about this refactor stops being true, and this is the only place that would notice.
     */
    @Test
    public void defaultsAreTheHistoricalHandTunedValues() {
        CommanderEvalParams d = CommanderEvalParams.DEFAULT;
        int[] expectedLife = {0, 1000, 2000, 3000, 4000, 4500, 5000, 5500, 6000, 6500, 7000,
                7400, 7800, 8200, 8600, 9000, 9200, 9400, 9600, 9800, 10000};
        Assert.assertEquals("maxTabulatedLife is derived from the table length",
                expectedLife.length - 1, d.getMaxTabulatedLife());
        for (int life = 0; life < expectedLife.length; life++) {
            Assert.assertEquals("life score at " + life, expectedLife[life], d.getLifeScoreAt(life));
        }
        Assert.assertEquals(100, d.getLifeAboveMultiplier());
        Assert.assertEquals(5, d.getHandCardScore());
        Assert.assertEquals(3, d.getBaseCardValue());
        Assert.assertEquals(50, d.getLandBaseMultiplier());
        Assert.assertEquals(50, d.getLandPerManaSymbol());
        Assert.assertEquals(100, d.getNonLandBaseMultiplier());
        Assert.assertEquals(20, d.getManaValuePenaltyPerPip());
        Assert.assertEquals(10, d.getCardPowerToughnessMultiplier());
        Assert.assertEquals(30, d.getRarityMultiplier());
        Assert.assertEquals(300, d.getPermanentOnBattlefieldBonus());
        Assert.assertEquals(100, d.getEquipmentPermanentBonus());
        Assert.assertEquals(30, d.getChargeCounterScore());
        Assert.assertEquals(30, d.getLevelCounterScore());
        Assert.assertEquals(2, d.getDamageMarkedPenalty());
        Assert.assertEquals(300, d.getCreaturePowerMultiplier());
        Assert.assertEquals(200, d.getCreatureToughnessMultiplier());
        Assert.assertEquals(1, d.getAbilityScorePowerOffset());
        Assert.assertEquals(2, d.getAbilityScoreDivisor());
        Assert.assertEquals(100, d.getAttachedEnchantmentOutcomeMultiplier());
        Assert.assertEquals(50, d.getAttachedEquipmentOutcomeMultiplier());
        Assert.assertEquals(-100, d.getCannotAttackPenalty());
        Assert.assertEquals(-30, d.getCannotBlockPenalty());
        Assert.assertEquals(-100, d.getTappedCreaturePenalty());
        Assert.assertEquals(-20, d.getTappedLandPenalty());
        Assert.assertEquals(-2, d.getTappedOtherPenalty());
        Assert.assertEquals(-1000, d.getDetrimentalOwnAuraPenalty());
    }

    /** The params object must not be reachable-and-mutable through the array it was built from. */
    @Test
    public void lifeScoreTableIsDefensivelyCopied() {
        int[] table = {0, 11, 22};
        CommanderEvalParams params = CommanderEvalParams.builder().lifeScores(table).build();
        table[1] = 999;
        Assert.assertEquals("mutating the caller's array must not reach into the params",
                11, params.getLifeScoreAt(1));
    }

    // ---- helpers -----------------------------------------------------------------------------

    private void assertParamsSurviveCopy(CommanderEvalParams expected, ComputerPlayer6 original, ComputerPlayer6 copy) {
        Assert.assertNotSame("copy() must return a new player", original, copy);
        Assert.assertEquals("copy() must preserve the runtime type",
                original.getClass(), copy.getClass());
        Assert.assertNotNull("a copy must always have params", copy.getEvalParams());
        Assert.assertNotSame("the copy must NOT have fallen back to the stock weights",
                CommanderEvalParams.DEFAULT, copy.getEvalParams());
        assertAllFieldsEqual(expected, copy.getEvalParams());
    }

    /**
     * Every field, not a spot check: a copy constructor that rebuilt the params from some but not all
     * of the original's values would pass a spot check and still be wrong.
     */
    private void assertAllFieldsEqual(CommanderEvalParams expected, CommanderEvalParams actual) {
        Assert.assertEquals("maxTabulatedLife", expected.getMaxTabulatedLife(), actual.getMaxTabulatedLife());
        for (int life = 0; life <= expected.getMaxTabulatedLife(); life++) {
            Assert.assertEquals("lifeScoreAt(" + life + ')', expected.getLifeScoreAt(life), actual.getLifeScoreAt(life));
        }
        Assert.assertEquals("lifeAboveMultiplier", expected.getLifeAboveMultiplier(), actual.getLifeAboveMultiplier());
        Assert.assertEquals("handCardScore", expected.getHandCardScore(), actual.getHandCardScore());
        Assert.assertEquals("baseCardValue", expected.getBaseCardValue(), actual.getBaseCardValue());
        Assert.assertEquals("landBaseMultiplier", expected.getLandBaseMultiplier(), actual.getLandBaseMultiplier());
        Assert.assertEquals("landPerManaSymbol", expected.getLandPerManaSymbol(), actual.getLandPerManaSymbol());
        Assert.assertEquals("nonLandBaseMultiplier", expected.getNonLandBaseMultiplier(), actual.getNonLandBaseMultiplier());
        Assert.assertEquals("manaValuePenaltyPerPip", expected.getManaValuePenaltyPerPip(), actual.getManaValuePenaltyPerPip());
        Assert.assertEquals("cardPowerToughnessMultiplier", expected.getCardPowerToughnessMultiplier(), actual.getCardPowerToughnessMultiplier());
        Assert.assertEquals("rarityMultiplier", expected.getRarityMultiplier(), actual.getRarityMultiplier());
        Assert.assertEquals("permanentOnBattlefieldBonus", expected.getPermanentOnBattlefieldBonus(), actual.getPermanentOnBattlefieldBonus());
        Assert.assertEquals("equipmentPermanentBonus", expected.getEquipmentPermanentBonus(), actual.getEquipmentPermanentBonus());
        Assert.assertEquals("chargeCounterScore", expected.getChargeCounterScore(), actual.getChargeCounterScore());
        Assert.assertEquals("levelCounterScore", expected.getLevelCounterScore(), actual.getLevelCounterScore());
        Assert.assertEquals("damageMarkedPenalty", expected.getDamageMarkedPenalty(), actual.getDamageMarkedPenalty());
        Assert.assertEquals("creaturePowerMultiplier", expected.getCreaturePowerMultiplier(), actual.getCreaturePowerMultiplier());
        Assert.assertEquals("creatureToughnessMultiplier", expected.getCreatureToughnessMultiplier(), actual.getCreatureToughnessMultiplier());
        Assert.assertEquals("abilityScorePowerOffset", expected.getAbilityScorePowerOffset(), actual.getAbilityScorePowerOffset());
        Assert.assertEquals("abilityScoreDivisor", expected.getAbilityScoreDivisor(), actual.getAbilityScoreDivisor());
        Assert.assertEquals("attachedEnchantmentOutcomeMultiplier", expected.getAttachedEnchantmentOutcomeMultiplier(), actual.getAttachedEnchantmentOutcomeMultiplier());
        Assert.assertEquals("attachedEquipmentOutcomeMultiplier", expected.getAttachedEquipmentOutcomeMultiplier(), actual.getAttachedEquipmentOutcomeMultiplier());
        Assert.assertEquals("cannotAttackPenalty", expected.getCannotAttackPenalty(), actual.getCannotAttackPenalty());
        Assert.assertEquals("cannotBlockPenalty", expected.getCannotBlockPenalty(), actual.getCannotBlockPenalty());
        Assert.assertEquals("tappedCreaturePenalty", expected.getTappedCreaturePenalty(), actual.getTappedCreaturePenalty());
        Assert.assertEquals("tappedLandPenalty", expected.getTappedLandPenalty(), actual.getTappedLandPenalty());
        Assert.assertEquals("tappedOtherPenalty", expected.getTappedOtherPenalty(), actual.getTappedOtherPenalty());
        Assert.assertEquals("detrimentalOwnAuraPenalty", expected.getDetrimentalOwnAuraPenalty(), actual.getDetrimentalOwnAuraPenalty());
    }
}
