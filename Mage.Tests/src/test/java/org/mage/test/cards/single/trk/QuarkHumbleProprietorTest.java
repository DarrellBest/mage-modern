package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class QuarkHumbleProprietorTest extends CardTestPlayerBase {

    // You can't sacrifice Treasure tokens.
    // At the beginning of each upkeep, create a Treasure token.
    private static final String QUARK = "Quark, Humble Proprietor";

    @Test
    public void test_CreatesTreasureEachUpkeep() {
        addCard(Zone.BATTLEFIELD, playerA, QUARK);

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.PRECOMBAT_MAIN);
        execute();

        // upkeeps of turns 1 (A), 2 (B) and 3 (A)
        assertPermanentCount(playerA, "Treasure Token", 3);
    }

    @Test
    public void test_TreasureCantBeSacrificedForMana() {
        addCard(Zone.BATTLEFIELD, playerA, QUARK);
        addCard(Zone.HAND, playerA, "Lightning Bolt");

        // the only mana source is the Treasure made in turn 1's upkeep
        checkPermanentCount("treasure made", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Treasure Token", 1);
        checkPlayableAbility("can't crack it", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Lightning Bolt", false);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Treasure Token", 1);
        assertHandCount(playerA, "Lightning Bolt", 1);
    }

    @Test
    public void test_TreasureCanBeSacrificedOnceQuarkIsGone() {
        addCard(Zone.BATTLEFIELD, playerA, QUARK);
        addCard(Zone.HAND, playerA, "Lightning Bolt");
        addCard(Zone.HAND, playerB, "Shock");
        addCard(Zone.BATTLEFIELD, playerB, "Mountain");

        // B kills Quark (1/2) in its own turn; the Treasures stay behind
        castSpell(2, PhaseStep.PRECOMBAT_MAIN, playerB, "Shock", QUARK);
        // turn 3: no Quark, so the two Treasures (turns 1 and 2) can be cracked again
        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);
        setChoice(playerA, "Red"); // Treasure: add one mana of any color

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.END_TURN);
        execute();

        assertGraveyardCount(playerA, QUARK, 1);
        assertGraveyardCount(playerA, "Lightning Bolt", 1);
        assertPermanentCount(playerA, "Treasure Token", 1);
        assertLife(playerB, 20 - 3);
    }
}
