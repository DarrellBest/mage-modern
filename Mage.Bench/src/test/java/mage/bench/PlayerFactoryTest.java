package mage.bench;

import mage.constants.RangeOfInfluence;
import mage.player.ai.ComputerPlayer;
import mage.player.ai.ComputerPlayer7;
import mage.player.ai.ComputerPlayerMCTS;
import mage.player.ai.kanna.ComputerPlayerKanna;
import mage.players.Player;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PlayerFactoryTest {

    @Test
    public void createsEachKnownType() {
        assertTrue(PlayerFactory.create("kanna", "A", RangeOfInfluence.ONE, 6) instanceof ComputerPlayerKanna);
        assertTrue(PlayerFactory.create("cp7", "A", RangeOfInfluence.ONE, 6) instanceof ComputerPlayer7);
        assertTrue(PlayerFactory.create("mcts", "A", RangeOfInfluence.ONE, 6) instanceof ComputerPlayerMCTS);
        assertTrue(PlayerFactory.create("base", "A", RangeOfInfluence.ONE, 6) instanceof ComputerPlayer);
    }

    @Test
    public void setsThePlayerName() {
        Player player = PlayerFactory.create("cp7", "PlayerB", RangeOfInfluence.ONE, 6);
        assertEquals("PlayerB", player.getName());
    }

    @Test
    public void unknownType_failsWithHelpfulMessage() {
        try {
            PlayerFactory.create("wizard", "A", RangeOfInfluence.ONE, 6);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("wizard"));
            assertTrue(e.getMessage().contains("kanna"));
        }
    }
}
