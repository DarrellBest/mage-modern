package org.mage.test.kanna;

import mage.abilities.common.PassAbility;
import mage.player.ai.kanna.ActionCatalog;
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

public class ActionRankerTest {

    private ActionCatalog catalogOf(String... labels) {
        ActionCatalog catalog = new ActionCatalog();
        for (String label : labels) {
            catalog.add(new PassAbility(), label);
        }
        return catalog;
    }

    @Test
    public void landDropOutranksCreatureWhichOutranksPass() {
        List<RankedAction> ranked = ActionRanker.rank(
                catalogOf("Pass", "Cast Grizzly Bears", "Play Mountain"));
        assertEquals("Play Mountain", ranked.get(0).label);
        assertEquals("Cast Grizzly Bears", ranked.get(1).label);
        assertEquals("Pass", ranked.get(2).label);
    }

    @Test
    public void removalOutranksCreature() {
        List<RankedAction> ranked = ActionRanker.rank(
                catalogOf("Cast Grizzly Bears", "Cast Lightning Bolt"));
        assertEquals("Cast Lightning Bolt", ranked.get(0).label);
    }

    @Test
    public void everyActionAppearsInTheRanking() {
        ActionCatalog catalog = catalogOf("A", "B", "C", "D", "E");
        assertEquals(5, ActionRanker.rank(catalog).size());
    }

    @Test
    public void rankingIsDeterministicAcrossRuns() {
        ActionCatalog catalog = catalogOf("Cast X", "Cast Y", "Cast Z");
        List<RankedAction> first = ActionRanker.rank(catalog);
        List<RankedAction> second = ActionRanker.rank(catalog);
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).id, second.get(i).id);
        }
    }

    @Test
    public void equalScoringActionsKeepCatalogInsertionOrder() {
        // all four score the same, so a stable sort must return them in insertion order
        ActionCatalog catalog = catalogOf("Cast Alpha", "Cast Beta", "Cast Gamma", "Cast Delta");
        List<RankedAction> ranked = ActionRanker.rank(catalog);
        assertEquals("Cast Alpha", ranked.get(0).label);
        assertEquals("Cast Beta", ranked.get(1).label);
        assertEquals("Cast Gamma", ranked.get(2).label);
        assertEquals("Cast Delta", ranked.get(3).label);
    }

    @Test
    public void emptyCatalogRanksShortlistsAndRendersCleanly() {
        ActionCatalog empty = new ActionCatalog();
        List<RankedAction> ranked = ActionRanker.rank(empty);
        assertTrue(ranked.isEmpty());
        assertTrue(ActionRanker.shortlist(ranked, 5).isEmpty());
        assertEquals("", ActionRanker.render(ActionRanker.shortlist(ranked, 5), 0));
    }

    @Test
    public void shortlistTruncatesToTheLimit() {
        List<RankedAction> ranked = ActionRanker.rank(catalogOf("A", "B", "C", "D", "E", "F"));
        assertEquals(3, ActionRanker.shortlist(ranked, 3).size());
    }

    @Test
    public void shortlistShorterThanLimitIsUnchanged() {
        List<RankedAction> ranked = ActionRanker.rank(catalogOf("A", "B"));
        assertEquals(2, ActionRanker.shortlist(ranked, 10).size());
    }

    @Test
    public void renderStatesHowManyOptionsAreHidden() {
        List<RankedAction> ranked = ActionRanker.rank(catalogOf("A", "B", "C", "D", "E", "F"));
        String rendered = ActionRanker.render(ActionRanker.shortlist(ranked, 2), 6);
        assertTrue("must disclose the hidden count", rendered.contains("4 more"));
        assertTrue("must name the escape hatch", rendered.contains("show_all_actions"));
    }

    @Test
    public void renderDoesNotClaimHiddenOptionsWhenNoneAreHidden() {
        List<RankedAction> ranked = ActionRanker.rank(catalogOf("A", "B"));
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
