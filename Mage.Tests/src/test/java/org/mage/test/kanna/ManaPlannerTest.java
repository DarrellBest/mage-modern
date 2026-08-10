package org.mage.test.kanna;

import mage.player.ai.kanna.ManaPlanner;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ManaPlannerTest {

    @Test
    public void spendingAColorNotNeededScoresHigherThanSpendingOneThatIs() {
        List<String> needed = Arrays.asList("R", "R");
        int spendGreen = ManaPlanner.scorePayment(Arrays.asList("G"), needed);
        int spendRed = ManaPlanner.scorePayment(Arrays.asList("R"), needed);
        assertTrue("must prefer keeping red available", spendGreen > spendRed);
    }

    @Test
    public void scoreIsZeroImpactWhenNothingIsNeeded() {
        assertEquals(ManaPlanner.scorePayment(Arrays.asList("R"), Collections.<String>emptyList()),
                ManaPlanner.scorePayment(Arrays.asList("G"), Collections.<String>emptyList()));
    }

    @Test
    public void scarcerNeededColorIsProtectedMore() {
        // one red needed, three green needed: spending the single red hurts more
        List<String> needed = Arrays.asList("R", "G", "G", "G");
        int spendRed = ManaPlanner.scorePayment(Arrays.asList("R"), needed);
        int spendGreen = ManaPlanner.scorePayment(Arrays.asList("G"), needed);
        assertTrue(spendGreen > spendRed);
    }

    @Test
    public void preferredOrderPutsUnneededColorsFirst() {
        List<String> order = ManaPlanner.preferredOrder(
                Arrays.asList("R", "G", "U"), Arrays.asList("R", "R"));
        assertTrue("red is needed, so it must not be spent first", order.indexOf("R") > 0);
    }

    @Test
    public void preferredOrderKeepsEveryAvailableColor() {
        List<String> available = Arrays.asList("R", "G", "U", "W");
        List<String> order = ManaPlanner.preferredOrder(available, Arrays.asList("R"));
        assertEquals(4, order.size());
        assertTrue(order.containsAll(available));
    }

    @Test
    public void preferredOrderIsStableForEqualPreference() {
        List<String> available = Arrays.asList("R", "G", "U");
        List<String> first = ManaPlanner.preferredOrder(available, Collections.<String>emptyList());
        List<String> second = ManaPlanner.preferredOrder(available, Collections.<String>emptyList());
        assertEquals(first, second);
    }

    @Test
    public void emptyAvailableColorsYieldsEmptyOrder() {
        assertTrue(ManaPlanner.preferredOrder(Collections.<String>emptyList(),
                Arrays.asList("R")).isEmpty());
    }
}
