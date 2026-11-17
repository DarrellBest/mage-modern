package org.mage.test.cards.single.trc;

import mage.constants.CardType;
import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TheBestOfBothWorldsTest extends CardTestPlayerBase {

    // I -- Assimilate target nonartifact creature an opponent controls.
    // II -- Artifact creatures you control get +2/+2 until end of turn.
    // III -- Exile the Borg assimilated with this Saga, then return it to the battlefield under its owner's control.
    private static final String SAGA = "The Best of Both Worlds";

    @Test
    public void test_ChapterOneAssimilates() {
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 5);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 1);
        addCard(Zone.HAND, playerA, SAGA);
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, SAGA);
        addTarget(playerA, "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Grizzly Bears", 1);
        assertSubtype("Grizzly Bears", SubType.BORG);
        assertCounterCount(playerA, "Grizzly Bears", CounterType.P1P1, 1);
    }

    @Test
    public void test_ChapterThreeReturnsItToItsOwner() {
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 5);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 1);
        addCard(Zone.HAND, playerA, SAGA);
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, SAGA);
        addTarget(playerA, "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(5, PhaseStep.END_TURN); // chapter II on turn 3, III on turn 5
        execute();

        assertPermanentCount(playerB, "Grizzly Bears", 1);
        assertPermanentCount(playerA, "Grizzly Bears", 0);
        assertType("Grizzly Bears", CardType.ARTIFACT, false); // a new object: the assimilation is over
        assertSubtype("Grizzly Bears", SubType.BEAR);
        assertCounterCount(playerB, "Grizzly Bears", CounterType.P1P1, 0);
        assertGraveyardCount(playerA, SAGA, 1);
    }
}
