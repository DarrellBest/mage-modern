package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class SpockVoiceOfReasonTest extends CardTestPlayerBase {

    // {2}, {T}: Draw a card for each nontoken permanent you control that entered this turn.
    private static final String SPOCK = "Spock, Voice of Reason";

    @Test
    public void test_DrawsForEachNontokenPermanentThatEntered() {
        addCard(Zone.BATTLEFIELD, playerA, SPOCK);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 4);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(Zone.HAND, playerA, "Grizzly Bears");
        addCard(Zone.HAND, playerA, "Raise the Alarm"); // two Soldier tokens: tokens don't count

        // turn 3, so nothing set up at game start counts as "entered this turn"
        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Grizzly Bears");
        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Raise the Alarm");
        activateAbility(3, PhaseStep.POSTCOMBAT_MAIN, playerA, "{2}, {T}: Draw");

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.END_TURN);
        execute();

        // turn 3 draw + 1 for Grizzly Bears (not the two tokens)
        assertPermanentCount(playerA, "Soldier Token", 2);
        assertHandCount(playerA, 1 + 1);
    }
}
