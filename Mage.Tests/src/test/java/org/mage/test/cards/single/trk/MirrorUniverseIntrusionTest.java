package org.mage.test.cards.single.trk;

import mage.abilities.keyword.MenaceAbility;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class MirrorUniverseIntrusionTest extends CardTestPlayerBase {

    @Test
    public void test_UnkickedCopiesOne() {
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 1);
        addCard(Zone.HAND, playerA, "Mirror Universe Intrusion");
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant");
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Mirror Universe Intrusion");
        setChoice(playerA, false); // not kicked
        addTarget(playerA, "Hill Giant");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Hill Giant", 1);
        assertAbility(playerA, "Hill Giant", new MenaceAbility(false), true);
        assertPermanentCount(playerA, "Grizzly Bears", 0);
    }

    @Test
    public void test_KickedCopiesAll() {
        addCard(Zone.BATTLEFIELD, playerA, "Island", 5);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 4);
        addCard(Zone.HAND, playerA, "Mirror Universe Intrusion");
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant");
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Mirror Universe Intrusion");
        setChoice(playerA, true); // kicked
        addTarget(playerA, playerB);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Hill Giant", 1);
        assertPermanentCount(playerA, "Grizzly Bears", 1);
    }
}
