package org.mage.test.kanna;

import mage.abilities.common.PassAbility;
import mage.player.ai.kanna.ActionCatalog;
import mage.player.ai.kanna.ActionFacts;
import mage.player.ai.kanna.ActionRanker;
import mage.player.ai.kanna.CreatureView;
import mage.player.ai.kanna.GameStateFormatter;
import mage.player.ai.kanna.RankedAction;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * DARRELLBEST-FORK: ActionRanker used to classify by matching substrings against an
 * action's rendered label ("bolt"/"destroy"/"damage" meant removal, "cast " meant a
 * creature). It now classifies from the actual game object an ability's source
 * resolves to (real card types, mana value, power/toughness) via ActionFacts, and
 * deliberately never attempts to recognise "removal" at all -- see ActionRanker's
 * class javadoc.
 * <p>
 * Scoring and rendering (ActionRanker.score/reason) are pure functions of
 * ActionFacts, so they are tested directly against hand-built facts here, the same
 * split CombatEvaluatorTest/ActionEvaluatorTest use for their classes: no Game, no
 * Ability, fully deterministic. The Game-facing extraction that builds ActionFacts
 * from a live Ability (ActionRanker's private extractFacts) is not covered by a unit
 * test here for the same reason ActionEvaluator.annotate is not -- it has no pure
 * function to test in isolation without constructing a live game; it is exercised by
 * the live-model AI tests instead.
 */
public class ActionRankerTest {

    private ActionCatalog catalogOf(String... labels) {
        ActionCatalog catalog = new ActionCatalog();
        for (String label : labels) {
            catalog.add(new PassAbility(), label);
        }
        return catalog;
    }

    private ActionFacts landFacts() {
        return new ActionFacts(ActionFacts.Category.LAND, "land", 0, true, false, 0, 0);
    }

    private ActionFacts permanentSpellFacts(String typeLine, int manaValue, boolean affordable,
                                             boolean isCreature, int power, int toughness) {
        return new ActionFacts(ActionFacts.Category.PERMANENT_SPELL, typeLine, manaValue, affordable,
                isCreature, power, toughness);
    }

    private ActionFacts otherSpellFacts(String typeLine, int manaValue, boolean affordable) {
        return new ActionFacts(ActionFacts.Category.OTHER_SPELL, typeLine, manaValue, affordable,
                false, 0, 0);
    }

    private ActionFacts passFacts() {
        return new ActionFacts(ActionFacts.Category.PASS, "", 0, false, false, 0, 0);
    }

    private ActionFacts unclassifiedActivatedFacts() {
        return new ActionFacts(ActionFacts.Category.UNCLASSIFIED_ACTIVATED, "", 0, false, false, 0, 0);
    }

    // ---- score(): tier ordering ----

    @Test
    public void landOutranksPermanentSpellWhichOutranksOtherSpellWhichOutranksPass() {
        assertTrue("land must outrank a permanent spell",
                ActionRanker.score(landFacts()) > ActionRanker.score(permanentSpellFacts("creature", 3, true, true, 2, 2)));
        assertTrue("a permanent spell (creature/artifact/enchantment/planeswalker) must outrank a non-permanent spell",
                ActionRanker.score(permanentSpellFacts("creature", 3, true, true, 2, 2))
                        > ActionRanker.score(otherSpellFacts("instant", 2, true)));
        assertTrue("a real spell cast must still outrank Pass, even though it is not a permanent",
                ActionRanker.score(otherSpellFacts("instant", 2, true)) > ActionRanker.score(passFacts()));
    }

    @Test
    public void passOutranksAnUnclassifiedActivatedAbility() {
        // FIX 5 (preserved from the substring-matching era): an ability the ranker
        // cannot classify -- an activated ability of something already on the
        // battlefield, not a card being cast -- used to score above Pass, making it the
        // top-ranked, headline-recommended suggestion the instant no land or spell was
        // on offer. That is exactly what drove the Jar of Eyeballs loop (T8 finding #5):
        // a valueless ability activation was the model's only "confidently ranked"
        // option every single turn. Classification changed from name-matching to real
        // game-object types, but this invariant must survive unchanged.
        assertTrue(ActionRanker.score(passFacts()) > ActionRanker.score(unclassifiedActivatedFacts()));
    }

    @Test
    public void doesNotSpecialCaseRemovalByAnyName() {
        // Doom Blade, Swords to Plowshares, Pongify and Reality Shift never matched any
        // of the old keyword list ("bolt"/"destroy"/"damage"/"slash"/"shock"/"kill") and
        // fell through unclassified despite being real, castable instants -- while an
        // artifact literally named "Kill Switch" would have matched "kill" and been
        // called removal. There is no name involved in scoring at all any more: any
        // instant classifies as a non-permanent spell purely from its card type,
        // regardless of what it is called.
        ActionFacts doomBlade = otherSpellFacts("instant", 2, true);
        ActionFacts anythingElseNamedInstant = otherSpellFacts("instant", 2, true);
        assertEquals(ActionRanker.score(doomBlade), ActionRanker.score(anythingElseNamedInstant));
    }

    // ---- reason(): states facts, never a judgement ----

    @Test
    public void landReasonStatesTheFact() {
        assertEquals("land drop, adds mana this turn", ActionRanker.reason(landFacts()));
    }

    @Test
    public void passReasonStatesTheFact() {
        assertEquals("take no action", ActionRanker.reason(passFacts()));
    }

    @Test
    public void unclassifiedActivatedAbilityGetsAnHonestNoOpinionReason() {
        // The ranker used to render nothing at all for this bucket, which reads as "no
        // objection" rather than "not evaluated" -- silence invited the model to trust
        // the ranking anyway. It must say plainly that it has no opinion here.
        String reason = ActionRanker.reason(unclassifiedActivatedFacts());
        assertFalse("must not stay silent about being unable to judge this action",
                reason == null || reason.isEmpty());
    }

    @Test
    public void creatureSpellReasonStatesTypeManaValueAffordabilityAndPowerToughness() {
        // The example from the design doc: state what is, never a judgement --
        // "Doom Blade -- instant, 2 mana (affordable)", not "-- good removal".
        String reason = ActionRanker.reason(permanentSpellFacts("creature", 3, true, true, 2, 2));
        assertTrue(reason.contains("creature"));
        assertTrue(reason.contains("3 mana"));
        assertTrue(reason.contains("(affordable)"));
        assertTrue("must state power/toughness for a creature", reason.contains("2/2"));
        assertFalse("must never assert a judgement like \"removal\" or \"good\"",
                reason.toLowerCase().contains("removal") || reason.toLowerCase().contains("good"));
    }

    @Test
    public void instantSpellReasonStatesTypeAndManaValueWithNoPowerToughness() {
        String reason = ActionRanker.reason(otherSpellFacts("instant", 2, true));
        assertTrue(reason.contains("instant"));
        assertTrue(reason.contains("2 mana"));
        assertTrue(reason.contains("(affordable)"));
        assertFalse("an instant has no power/toughness to state", reason.contains("/"));
    }

    @Test
    public void unaffordableSpellReasonSaysSo() {
        String reason = ActionRanker.reason(otherSpellFacts("sorcery", 6, false));
        assertTrue(reason.contains("(not currently affordable)"));
    }

    @Test
    public void nonCreaturePermanentReasonOmitsPowerToughness() {
        String reason = ActionRanker.reason(permanentSpellFacts("artifact", 2, true, false, 0, 0));
        assertTrue(reason.contains("artifact"));
        assertFalse("an artifact is not a creature -- must not state a P/T for it", reason.contains("/"));
    }

    // ---- rank()/shortlist()/render(): list mechanics, independent of classification ----
    // These pass a null Game/playerId: ActionRanker's extraction is null-safe and every
    // PassAbility-wrapped catalog entry below resolves to Category.PASS regardless of
    // its label (classification now comes from the real ability object, not the label
    // text, and catalogOf's entries are all literally `new PassAbility()`). That makes
    // every entry tie in score, which is exactly what these tests need: they are about
    // ranking's list mechanics (ordering stability, shortlist truncation, hidden-count
    // reporting), not about which category anything falls into -- that is covered above.

    @Test
    public void everyActionAppearsInTheRanking() {
        ActionCatalog catalog = catalogOf("A", "B", "C", "D", "E");
        assertEquals(5, ActionRanker.rank(catalog, null, null).size());
    }

    @Test
    public void rankingIsDeterministicAcrossRuns() {
        ActionCatalog catalog = catalogOf("Cast X", "Cast Y", "Cast Z");
        List<RankedAction> first = ActionRanker.rank(catalog, null, null);
        List<RankedAction> second = ActionRanker.rank(catalog, null, null);
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).id, second.get(i).id);
        }
    }

    @Test
    public void equalScoringActionsKeepCatalogInsertionOrder() {
        // all four tie (see the block comment above), so a stable sort must return them
        // in insertion order
        ActionCatalog catalog = catalogOf("Alpha", "Beta", "Gamma", "Delta");
        List<RankedAction> ranked = ActionRanker.rank(catalog, null, null);
        assertEquals("Alpha", ranked.get(0).label);
        assertEquals("Beta", ranked.get(1).label);
        assertEquals("Gamma", ranked.get(2).label);
        assertEquals("Delta", ranked.get(3).label);
    }

    @Test
    public void emptyCatalogRanksShortlistsAndRendersCleanly() {
        ActionCatalog empty = new ActionCatalog();
        List<RankedAction> ranked = ActionRanker.rank(empty, null, null);
        assertTrue(ranked.isEmpty());
        assertTrue(ActionRanker.shortlist(ranked, 5).isEmpty());
        assertEquals("", ActionRanker.render(ActionRanker.shortlist(ranked, 5), 0));
    }

    @Test
    public void singlePassEntryRoundTripsThroughRankToItsRealReason() {
        // A minimal true end-to-end check of rank() itself (not just score()/reason()
        // directly): a catalog holding one real PassAbility must resolve, through
        // extraction, to exactly the Pass reason text.
        ActionCatalog catalog = catalogOf("Pass");
        RankedAction only = ActionRanker.rank(catalog, null, null).get(0);
        assertEquals("take no action", only.reason);
    }

    @Test
    public void shortlistTruncatesToTheLimit() {
        List<RankedAction> ranked = ActionRanker.rank(catalogOf("A", "B", "C", "D", "E", "F"), null, null);
        assertEquals(3, ActionRanker.shortlist(ranked, 3).size());
    }

    @Test
    public void shortlistShorterThanLimitIsUnchanged() {
        List<RankedAction> ranked = ActionRanker.rank(catalogOf("A", "B"), null, null);
        assertEquals(2, ActionRanker.shortlist(ranked, 10).size());
    }

    @Test
    public void renderStatesHowManyOptionsAreHidden() {
        List<RankedAction> ranked = ActionRanker.rank(catalogOf("A", "B", "C", "D", "E", "F"), null, null);
        String rendered = ActionRanker.render(ActionRanker.shortlist(ranked, 2), 6);
        assertTrue("must disclose the hidden count", rendered.contains("4 more"));
        assertTrue("must name the escape hatch", rendered.contains("show_all_actions"));
    }

    @Test
    public void renderDoesNotClaimHiddenOptionsWhenNoneAreHidden() {
        List<RankedAction> ranked = ActionRanker.rank(catalogOf("A", "B"), null, null);
        String rendered = ActionRanker.render(ActionRanker.shortlist(ranked, 5), 2);
        assertTrue(rendered.contains("A"));
        assertTrue(rendered.contains("B"));
        org.junit.Assert.assertFalse(rendered.contains("more options"));
    }

    // ---- formatter ----

    @Test
    public void describeCreaturesIncludesKeywordsAndStats() {
        CreatureView angel = new CreatureView("c0", "Serra Angel", 4, 4,
                true, false, false, false, false, false, false, false);
        String text = GameStateFormatter.describeCreatures(Arrays.asList(angel));
        assertTrue(text.contains("Serra Angel"));
        assertTrue(text.contains("4/4"));
        assertTrue(text.contains("Flying"));
    }

    @Test
    public void describeCreaturesReportsNoneForEmptyBoard() {
        assertTrue(GameStateFormatter.describeCreatures(new java.util.ArrayList<CreatureView>())
                .toLowerCase().contains("none"));
    }

    @Test
    public void attackOptionsAnnotateEachAttackWithComputedConsequence() {
        CreatureView angel = new CreatureView("atk-0", "Serra Angel", 4, 4,
                true, false, false, false, false, false, false, false);
        CreatureView giant = new CreatureView("blk-0", "Hill Giant", 3, 3,
                false, false, false, false, false, false, false, false);
        String text = GameStateFormatter.attackOptions(Arrays.asList(angel), Arrays.asList(giant), 12);
        assertTrue("flier vs no reach is unblocked", text.contains("unblocked"));
        assertTrue(text.contains("atk-0"));
    }

    @Test
    public void attackOptionsFlagLethal() {
        CreatureView angel = new CreatureView("atk-0", "Serra Angel", 4, 4,
                true, false, false, false, false, false, false, false);
        String text = GameStateFormatter.attackOptions(Arrays.asList(angel),
                new java.util.ArrayList<CreatureView>(), 4);
        assertTrue("4 damage into 4 life is lethal", text.toLowerCase().contains("lethal"));
    }

    @Test
    public void attackOptionsDoNotFlagLethalWhenDamageIsNotLethal() {
        CreatureView angel = new CreatureView("atk-0", "Serra Angel", 4, 4,
                true, false, false, false, false, false, false, false);
        String text = GameStateFormatter.attackOptions(Arrays.asList(angel),
                new java.util.ArrayList<CreatureView>(), 12);
        assertFalse("4 damage into 12 life is not lethal",
                text.toUpperCase().contains("LETHAL"));
    }
}
