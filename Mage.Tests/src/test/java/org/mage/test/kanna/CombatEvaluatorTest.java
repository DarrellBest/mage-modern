package org.mage.test.kanna;

import mage.player.ai.kanna.AttackOutcome;
import mage.player.ai.kanna.CombatEvaluator;
import mage.player.ai.kanna.CreatureView;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CombatEvaluatorTest {

    private CreatureView plain(String id, String name, int p, int t) {
        return new CreatureView(id, name, p, t, false, false, false, false, false, false, false, false);
    }

    private CreatureView with(String id, String name, int p, int t,
                              boolean flying, boolean reach, boolean menace, boolean deathtouch,
                              boolean firstStrike, boolean doubleStrike, boolean trample) {
        return new CreatureView(id, name, p, t, flying, reach, menace, deathtouch,
                firstStrike, doubleStrike, trample, false);
    }

    // ---- blocking legality ----

    @Test
    public void groundCreatureCannotBlockFlier() {
        CreatureView flier = with("a", "Serra Angel", 4, 4, true, false, false, false, false, false, false);
        assertFalse(CombatEvaluator.canBlock(plain("b", "Hill Giant", 3, 3), flier));
    }

    @Test
    public void reachCanBlockFlier() {
        CreatureView flier = with("a", "Serra Angel", 4, 4, true, false, false, false, false, false, false);
        CreatureView spider = with("b", "Giant Spider", 2, 4, false, true, false, false, false, false, false);
        assertTrue(CombatEvaluator.canBlock(spider, flier));
    }

    @Test
    public void flierCanBlockGroundCreature() {
        CreatureView flier = with("b", "Serra Angel", 4, 4, true, false, false, false, false, false, false);
        assertTrue(CombatEvaluator.canBlock(flier, plain("a", "Hill Giant", 3, 3)));
    }

    @Test
    public void tappedCreatureCannotBlock() {
        CreatureView tapped = new CreatureView("b", "Hill Giant", 3, 3,
                false, false, false, false, false, false, false, true);
        assertFalse(CombatEvaluator.canBlock(tapped, plain("a", "Bear", 2, 2)));
    }

    @Test
    public void menaceNeedsTwoBlockers() {
        CreatureView menacer = with("a", "Boggart", 3, 3, false, false, true, false, false, false, false);
        List<CreatureView> one = new ArrayList<CreatureView>();
        one.add(plain("b", "Bear", 2, 2));
        assertTrue(CombatEvaluator.legalBlockers(menacer, one).isEmpty());

        List<CreatureView> two = Arrays.asList(plain("b", "Bear", 2, 2), plain("c", "Bear2", 2, 2));
        assertEquals(2, CombatEvaluator.legalBlockers(menacer, two).size());
    }

    // ---- damage math ----

    @Test
    public void unblockedDealsFullDamage() {
        AttackOutcome o = CombatEvaluator.evaluateUnblocked(plain("a", "Bear", 2, 2));
        assertEquals(2, o.damageThrough);
        assertFalse(o.attackerDies);
        assertTrue(o.unblocked);
    }

    @Test
    public void evenTradeKillsBoth() {
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(plain("a", "Bear", 2, 2),
                Arrays.asList(plain("b", "Bear2", 2, 2)));
        assertTrue(o.attackerDies);
        assertEquals(Arrays.asList("Bear2"), o.blockersThatDie);
        assertEquals(0, o.damageThrough);
    }

    @Test
    public void biggerBlockerKillsAttackerAndSurvives() {
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(plain("a", "Bear", 2, 2),
                Arrays.asList(plain("b", "Hill Giant", 3, 3)));
        assertTrue(o.attackerDies);
        assertTrue(o.blockersThatDie.isEmpty());
    }

    @Test
    public void firstStrikeKillsBlockerWithoutDying() {
        CreatureView fs = with("a", "White Knight", 2, 2, false, false, false, false, true, false, false);
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(fs, Arrays.asList(plain("b", "Bear", 2, 2)));
        assertFalse("first striker kills before taking damage", o.attackerDies);
        assertEquals(Arrays.asList("Bear"), o.blockersThatDie);
    }

    @Test
    public void firstStrikeDoesNotSaveAttackerFromBiggerBlocker() {
        CreatureView fs = with("a", "White Knight", 2, 2, false, false, false, false, true, false, false);
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(fs, Arrays.asList(plain("b", "Wall", 0, 5)));
        assertFalse(o.attackerDies);
        assertTrue(o.blockersThatDie.isEmpty());
    }

    @Test
    public void doubleStrikeKillsBiggerBlockerButStillDies() {
        CreatureView ds = with("a", "Ronin", 2, 2, false, false, false, false, false, true, false);
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(ds, Arrays.asList(plain("b", "Bear3", 3, 3)));
        // 2 in the first-strike step leaves the 3/3 alive (needs 3); 2 more in the regular
        // step brings the total to 4 and kills it -- but the 3/3 strikes back in that same
        // step for 3, which kills the 2/2. Both die.
        assertEquals(Arrays.asList("Bear3"), o.blockersThatDie);
        assertTrue("the 3/3 survives to strike back in the regular step", o.attackerDies);
    }

    @Test
    public void deathtouchKillsAnySizeBlocker() {
        CreatureView dt = with("a", "Adder", 1, 1, false, false, false, true, false, false, false);
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(dt, Arrays.asList(plain("b", "Wall", 0, 8)));
        assertEquals(Arrays.asList("Wall"), o.blockersThatDie);
    }

    @Test
    public void deathtouchBlockerKillsBigAttacker() {
        CreatureView dtBlocker = with("b", "Adder", 1, 1, false, false, false, true, false, false, false);
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(plain("a", "Giant", 6, 6),
                Arrays.asList(dtBlocker));
        assertTrue(o.attackerDies);
    }

    @Test
    public void trampleSpillsExcessDamage() {
        CreatureView tr = with("a", "Rhino", 5, 5, false, false, false, false, false, false, true);
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(tr, Arrays.asList(plain("b", "Bear", 2, 2)));
        assertEquals("5 power minus 2 toughness tramples over", 3, o.damageThrough);
    }

    @Test
    public void noTrampleMeansNoDamageThrough() {
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(plain("a", "Rhino", 5, 5),
                Arrays.asList(plain("b", "Bear", 2, 2)));
        assertEquals(0, o.damageThrough);
    }

    @Test
    public void multipleBlockersGangUp() {
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(plain("a", "Giant", 4, 4),
                Arrays.asList(plain("b", "Bear", 2, 2), plain("c", "Bear2", 2, 2)));
        assertTrue(o.attackerDies);
    }

    // ---- likely outcome and lethal ----

    @Test
    public void evaluateLikelyReportsUnblockedWhenNoLegalBlockerExists() {
        CreatureView flier = with("a", "Serra Angel", 4, 4, true, false, false, false, false, false, false);
        AttackOutcome o = CombatEvaluator.evaluateLikely(flier,
                Arrays.asList(plain("b", "Hill Giant", 3, 3)));
        assertTrue(o.unblocked);
        assertEquals(4, o.damageThrough);
    }

    @Test
    public void isLethalComparesDamageToLife() {
        assertTrue(CombatEvaluator.isLethal(5, 5));
        assertTrue(CombatEvaluator.isLethal(6, 5));
        assertFalse(CombatEvaluator.isLethal(4, 5));
    }

    @Test
    public void summaryIsNonEmptyAndMentionsTheAttacker() {
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(plain("a", "Bear", 2, 2),
                Arrays.asList(plain("b", "Bear2", 2, 2)));
        assertTrue(o.summary.contains("Bear"));
        assertFalse(o.summary.trim().isEmpty());
    }
}
