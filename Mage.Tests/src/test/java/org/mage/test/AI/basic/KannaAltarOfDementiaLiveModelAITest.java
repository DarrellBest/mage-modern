package org.mage.test.AI.basic;

import mage.abilities.ActivatedAbility;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import org.apache.log4j.Logger;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayerKanna;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import static org.junit.Assert.assertTrue;

/**
 * DARRELLBEST-FORK -- NOT A CI TEST. Requires a live Ollama server reachable at
 * http://localhost:11434 serving the model "xmage-ai-qwen3.6:latest" (the same
 * defaults ComputerPlayerKanna.newAgent() uses when no scripted client is
 * installed). It depends on real model output, which is nondeterministic, so it
 * is deliberately excluded from routine/CI runs -- there is no assertion here
 * that is safe to gate a build on. Run it deliberately, by name:
 * <pre>
 *   mvn -pl Mage.Tests test -Dtest=KannaAltarOfDementiaLiveModelAITest
 * </pre>
 * <p>
 * This is the live-model counterpart to KannaActionEvaluatorAnnotationAITest,
 * which only proves ActionEvaluator's sacrifice-cost text reaches the prompt (it
 * injects a scripted OllamaClient that returns null, forcing the heuristic
 * fallback -- the real model is never consulted, so it proves nothing about
 * behaviour). This test injects nothing: TestComputerPlayerKanna's
 * setScriptedOllamaClient seam is deliberately left unset, so newAgent() takes
 * ComputerPlayerKanna's own default path and every decision is a real network
 * round trip to Ollama.
 * <p>
 * Live evidence this guards: on the production server Kanna activated Altar of
 * Dementia (Sacrifice a creature: target player mills cards equal to the
 * sacrificed creature's power -- no mana cost, legal at every priority window she
 * holds a creature) six times in one real game, emptying her own board,
 * including sacrificing a 4/4. The board here reproduces the shape that let that
 * happen: Altar of Dementia plus three creatures of clearly distinct sizes (1/1,
 * 2/2, 6/4), so ActionEvaluator's annotation has something meaningful to say
 * about which one is at stake each time. No lands are needed -- the ability
 * costs no mana and all three creatures are already in play, not cast.
 */
public class KannaAltarOfDementiaLiveModelAITest extends CardTestPlayerBaseAI {

    private static final Logger logger = Logger.getLogger(KannaAltarOfDementiaLiveModelAITest.class);
    private static final String ALTAR_NAME = "Altar of Dementia";

    /**
     * Counts real Altar of Dementia activations that actually resolved
     * (activateAbility returning true) by wrapping the real decision path rather
     * than replacing it -- this subclass only observes what ComputerPlayerKanna
     * (via the real OllamaClient) decided to do, it never decides anything
     * itself.
     */
    private static class CountingKanna extends TestComputerPlayerKanna {
        int altarActivations = 0;

        CountingKanna(String name, RangeOfInfluence range, int skill) {
            super(name, range, skill);
        }

        @Override
        public boolean activateAbility(ActivatedAbility ability, Game game) {
            boolean isAltar = false;
            if (ability != null) {
                Permanent source = game.getPermanent(ability.getSourceId());
                isAltar = source != null && ALTAR_NAME.equals(source.getName());
            }
            boolean result = super.activateAbility(ability, game);
            if (isAltar && result) {
                altarActivations++;
                logger.info("KannaAltarOfDementiaLiveModelAITest: Altar of Dementia activation #"
                        + altarActivations + " resolved");
            }
            return result;
        }
    }

    private CountingKanna kannaPlayer;

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if ("PlayerA".equals(name)) {
            kannaPlayer = new CountingKanna(name, RangeOfInfluence.ONE, getSkillLevel());
            // Deliberately NOT calling setScriptedOllamaClient here: newAgent() must
            // fall through to ComputerPlayerKanna's own default, a real OllamaClient
            // against http://localhost:11434 with xmage-ai-qwen3.6:latest. Observing
            // that real choice is this test's entire purpose.
            TestPlayer testPlayer = new TestPlayer(kannaPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    @Test
    public void test_Kanna_DoesNotEmptyHerBoardToAltarOfDementia() {
        addCard(Zone.BATTLEFIELD, playerA, ALTAR_NAME, 1);
        addCard(Zone.BATTLEFIELD, playerA, "Memnite", 1);       // 1/1 vanilla
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears", 1); // 2/2 vanilla
        addCard(Zone.BATTLEFIELD, playerA, "Craw Wurm", 1);     // 6/4 vanilla, the biggest of the three

        setStrictChooseMode(true);
        setStopAt(6, PhaseStep.END_TURN);
        execute();

        int survivingCreatures = 0;
        for (Permanent permanent : currentGame.getBattlefield().getAllActivePermanents()) {
            if (permanent.getControllerId().equals(playerA.getId()) && permanent.isCreature(currentGame)) {
                survivingCreatures++;
            }
        }

        String report = "Kanna activated Altar of Dementia " + kannaPlayer.altarActivations
                + " time(s) across 6 turns; " + survivingCreatures + " of 3 starting creatures survived.";
        logger.info("KannaAltarOfDementiaLiveModelAITest: " + report);
        System.out.println("KannaAltarOfDementiaLiveModelAITest: " + report);

        assertTrue("Kanna must still control at least one creature at the end -- "
                        + "previously she sacrificed her entire board to Altar of Dementia. " + report,
                survivingCreatures >= 1);
    }
}
