package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class JeanLucMonCapitaineTest extends CardTestPlayerBase {

    @Test
    public void test_FaerieEntersWithFederationCounters() {
        addCard(Zone.BATTLEFIELD, playerA, "Jean-Luc, Mon Capitaine"); // Human Officer
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");           // Bear
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2);
        addCard(Zone.HAND, playerA, "Spellstutter Sprite");            // Faerie Wizard

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Spellstutter Sprite");

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // Human, Officer, Bear: the entering Faerie itself isn't "already controlled"
        assertCounterCount(playerA, "Spellstutter Sprite", CounterType.P1P1, 3);
    }
}
