package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * Also covers the fork's "face a dilemma" core (700.17): you face one as you choose modes for a modal spell or
 * ability; non-modal spells don't count; a countered spell still counts.
 */
public class MelkotianAdjudicatorTest extends CardTestPlayerBase {

    // Whenever you face a dilemma, put a +1/+1 counter on this creature.
    private static final String MELK = "Melkotian Adjudicator";
    private static final String SALVE = "Healing Salve"; // {W} Choose one -- target player gains 3 life; or prevent 3

    @Test
    public void test_ModalSpellIsADilemma() {
        addCard(Zone.BATTLEFIELD, playerA, MELK);
        addCard(Zone.BATTLEFIELD, playerA, "Plains");
        addCard(Zone.HAND, playerA, SALVE);

        setModeChoice(playerA, "1");
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, SALVE, playerA);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertCounterCount(playerA, MELK, CounterType.P1P1, 1);
        assertLife(playerA, 23);
    }

    @Test
    public void test_NonModalSpellIsNot() {
        addCard(Zone.BATTLEFIELD, playerA, MELK);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain");
        addCard(Zone.HAND, playerA, "Lightning Bolt");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertCounterCount(playerA, MELK, CounterType.P1P1, 0);
    }

    @Test
    public void test_CounteredModalSpellStillCounts() {
        addCard(Zone.BATTLEFIELD, playerA, MELK);
        addCard(Zone.BATTLEFIELD, playerA, "Plains");
        addCard(Zone.HAND, playerA, SALVE);
        addCard(Zone.BATTLEFIELD, playerB, "Island", 2);
        addCard(Zone.HAND, playerB, "Counterspell");

        setModeChoice(playerA, "1");
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, SALVE, playerA);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerB, "Counterspell", SALVE, SALVE);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertGraveyardCount(playerA, SALVE, 1);
        assertLife(playerA, 20);
        assertCounterCount(playerA, MELK, CounterType.P1P1, 1);
    }

    @Test
    public void test_OpponentsDilemmaDoesntCount() {
        addCard(Zone.BATTLEFIELD, playerA, MELK);
        addCard(Zone.BATTLEFIELD, playerB, "Plains");
        addCard(Zone.HAND, playerB, SALVE);

        setModeChoice(playerB, "1");
        castSpell(2, PhaseStep.PRECOMBAT_MAIN, playerB, SALVE, playerB);

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        assertCounterCount(playerA, MELK, CounterType.P1P1, 0);
    }
}
