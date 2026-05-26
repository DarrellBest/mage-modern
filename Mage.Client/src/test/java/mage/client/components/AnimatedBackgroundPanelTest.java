package mage.client.components;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AnimatedBackgroundPanelTest {

    @Test
    public void wrapReentersFromOppositeEdge() {
        // below min -> comes back in from max side
        assertEquals(95f, AnimatedBackgroundPanel.wrap(-5f, 0f, 100f), 0.001f);
        // above max -> comes back in from min side
        assertEquals(5f, AnimatedBackgroundPanel.wrap(105f, 0f, 100f), 0.001f);
        // inside range -> unchanged
        assertEquals(50f, AnimatedBackgroundPanel.wrap(50f, 0f, 100f), 0.001f);
    }

    @Test
    public void wrapHandlesNonPositiveSpan() {
        // degenerate span -> value returned as-is (no infinite loop)
        assertEquals(7f, AnimatedBackgroundPanel.wrap(7f, 10f, 10f), 0.001f);
    }
}
