package org.mage.test.cards.single.trk;

import mage.abilities.keyword.TrampleAbility;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class GowronEagerForBattleTest extends CardTestPlayerBase {

    // Whenever another creature dies, put a +1/+1 counter on Gowron.
    // Sacrifice another creature: Gowron gains trample until end of turn. If you sacrificed a Klingon this way,
    // you draw a card and lose 1 life.
    private static final String GOWRON = "Gowron, Eager for Battle";

    @Test
    public void test_SacrificeKlingonDraws() {
        addCard(Zone.BATTLEFIELD, playerA, GOWRON);
        addCard(Zone.BATTLEFIELD, playerA, "Targ Wrangler"); // Klingon Warrior

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Sacrifice another creature");
        setChoice(playerA, "Targ Wrangler");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertGraveyardCount(playerA, "Targ Wrangler", 1);
        assertCounterCount(playerA, GOWRON, CounterType.P1P1, 1); // the sacrificed creature died
        assertAbility(playerA, GOWRON, TrampleAbility.getInstance(), true);
        assertHandCount(playerA, 1);
        assertLife(playerA, 20 - 1);
    }

    @Test
    public void test_SacrificeNonKlingonNoDraw() {
        addCard(Zone.BATTLEFIELD, playerA, GOWRON);
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Sacrifice another creature");
        setChoice(playerA, "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertAbility(playerA, GOWRON, TrampleAbility.getInstance(), true);
        assertHandCount(playerA, 0);
        assertLife(playerA, 20);
    }
}
