package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class ZeframCochraneWarpPioneerTest extends CardTestPlayerBase {

    @Test
    public void test_CastArtifactFromTop() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, "Zefram Cochrane, Warp Pioneer");
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 3);
        addCard(Zone.LIBRARY, playerA, "Bottle Gnomes"); // top

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Bottle Gnomes");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Bottle Gnomes", 1);
    }

    @Test
    public void test_SpacecraftEnterWithTwoMoreChargeCounters() {
        addCard(Zone.BATTLEFIELD, playerA, "Zefram Cochrane, Warp Pioneer");
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);
        addCard(Zone.HAND, playerA, "Transwarp Hub");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Transwarp Hub");

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertCounterCount(playerA, "Transwarp Hub", CounterType.CHARGE, 2);
    }
}
