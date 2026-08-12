package mage.bench;

import mage.constants.RangeOfInfluence;
import mage.player.ai.ComputerPlayer;
import mage.player.ai.ComputerPlayer7;
import mage.player.ai.ComputerPlayerMCTS;
import mage.player.ai.commander.ComputerPlayerCommander;
import mage.player.ai.commander.ComputerPlayerLearner;
import mage.player.ai.commander.score.CommanderEvalParams;
import mage.players.Player;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PlayerFactoryTest {

    @Test
    public void createsEachKnownType() {
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
            assertTrue(e.getMessage().contains("cp7"));
        }
    }

    // --- DARRELLBEST-FORK: tuned evaluator weights ---

    private static CommanderEvalParams tuned() {
        return CommanderEvalParams.DEFAULT.toBuilder().handCardScore(150).build();
    }

    @Test
    public void commanderAndLearner_receiveTheExactParamsInstance() {
        CommanderEvalParams params = tuned();

        ComputerPlayerCommander commander = (ComputerPlayerCommander)
                PlayerFactory.create("commander", "A", RangeOfInfluence.ONE, 6, params);
        ComputerPlayerLearner learner = (ComputerPlayerLearner)
                PlayerFactory.create("learner", "B", RangeOfInfluence.ONE, 6, params);

        // same instance, not a copy: CommanderEvalParams is immutable and shared by reference, so
        // this also pins that the factory did not quietly build its own
        assertSame(params, commander.getEvalParams());
        assertSame(params, learner.getEvalParams());
        assertEquals(150, commander.getEvalParams().getHandCardScore());
    }

    @Test
    public void nullParams_leavesTheBotOnItsStockWeights() {
        ComputerPlayerCommander commander = (ComputerPlayerCommander)
                PlayerFactory.create("commander", "A", RangeOfInfluence.ONE, 6, null);

        // TUNED, not DEFAULT: ComputerPlayerCommander's no-params constructor is what config.xml
        // reaches by reflection, so it is what LIVE games play with, and it deliberately carries the
        // measured handCardScore=150 plus the commander-damage term.
        //
        // Consequence for benchmarking: a run with no --paramsA is therefore NOT the historical
        // baseline. Compare against an explicit DEFAULT params file when that is what you want.
        assertSame(CommanderEvalParams.TUNED, commander.getEvalParams());
    }

    @Test
    public void paramsOnABotThatCannotUseThem_isAnErrorRatherThanIgnored() {
        // Asking for tuned weights on a bot that scores positions some other way means the run is
        // not measuring what the caller thinks. Every one of these must refuse.
        // cp7 is included deliberately: the bench's "cp7" is mage.player.ai.ComputerPlayer7 from the
        // MAD plugin, a different class from the commander fork's ComputerPlayer7, and it carries
        // its own scoring code that a CommanderEvalParams cannot reach.
        for (String type : new String[]{"cp7", "mcts", "base"}) {
            try {
                PlayerFactory.create(type, "A", RangeOfInfluence.ONE, 6, tuned());
                fail("expected IllegalArgumentException for eval params on '" + type + "'");
            } catch (IllegalArgumentException e) {
                String message = e.getMessage();
                assertTrue("should name the type: " + message, message.contains(type));
                assertTrue("should say the params would be ignored: " + message,
                        message.contains("silently"));
                assertTrue("should name the types that do accept params: " + message,
                        message.contains("commander"));
            }
        }
    }

    @Test
    public void withoutParams_everyTypeStillBuilds() {
        // the legacy 4-arg overload must keep working for every type, params feature or not
        for (String type : new String[]{"cp7", "mcts", "base", "commander", "learner"}) {
            assertNotNullPlayer(type, PlayerFactory.create(type, "A", RangeOfInfluence.ONE, 6));
        }
    }

    private static void assertNotNullPlayer(String type, Player player) {
        assertTrue("type '" + type + "' should build a player", player != null);
    }
}
