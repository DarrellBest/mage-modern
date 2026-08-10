package org.mage.test.player;

import mage.abilities.Ability;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.kanna.ComputerPlayerKanna;
import mage.player.ai.kanna.KannaAgent;
import mage.player.ai.kanna.OllamaClient;
import mage.player.ai.kanna.Strategist;
import mage.target.Target;
import mage.target.TargetCard;

/**
 * DARRELLBEST-FORK: test-harness wrapper for ComputerPlayerKanna, mirroring
 * TestComputerPlayer7's pattern so it can be driven by TestPlayer.
 * <p>
 * Not final: KannaActivationRetryAITest subclasses this to override activateAbility()
 * itself (forcing a deterministic activation failure, independent of any real game-rule
 * reason one might fail), which needs every other bit of test-harness wiring here
 * (choose/chooseTarget delegation, the scripted-client seam) rather than reimplementing
 * it from ComputerPlayerKanna directly.
 */
public class TestComputerPlayerKanna extends ComputerPlayerKanna {

    private TestPlayer testPlayerLink;
    private OllamaClient scriptedOllamaClient;

    public TestComputerPlayerKanna(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
    }

    public void setTestPlayerLink(TestPlayer testPlayerLink) {
        this.testPlayerLink = testPlayerLink;
    }

    /**
     * DARRELLBEST-FORK: lets a test supply an OllamaClient that returns scripted
     * responses (e.g. a subclass overriding call(), the same pattern KannaAgentTest's
     * ScriptedClient uses one layer down) instead of making a real network call, so a
     * test can drive ComputerPlayerKanna's real decision code -- priority(),
     * chooseTarget(), declareAttacksAgentically(), declareBlocksAgentically() -- with a
     * canned model response. Unset (null) by default, in which case newAgent() behaves
     * exactly as ComputerPlayerKanna's own implementation (a real OllamaClient against
     * the configured ollamaUrl). A fresh KannaAgent is still constructed per call, same
     * as the real implementation, so KannaAgent.getInvalidCount() stays scoped to a
     * single decision rather than accumulating across however many decisions a test's
     * scripted client ends up answering.
     */
    public void setScriptedOllamaClient(OllamaClient scriptedOllamaClient) {
        this.scriptedOllamaClient = scriptedOllamaClient;
    }

    @Override
    protected KannaAgent newAgent() {
        return scriptedOllamaClient != null ? new KannaAgent(scriptedOllamaClient, 4) : super.newAgent();
    }

    // DARRELLBEST-FORK: same scriptedOllamaClient seam as newAgent() above, so a test
    // driving the agentic decision path with a canned OllamaClient also gets a canned
    // response for the once-per-turn planning call, instead of that call falling through
    // to a real network request against ComputerPlayerKanna's default Ollama URL.
    @Override
    protected Strategist newStrategist() {
        return scriptedOllamaClient != null ? new Strategist(scriptedOllamaClient) : super.newStrategist();
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.choose(outcome, target, source, game);
        } else {
            return testPlayerLink.choose(outcome, target, source, game);
        }
    }

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.choose(outcome, choice, game);
        } else {
            return testPlayerLink.choose(outcome, choice, game);
        }
    }

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.choose(outcome, cards, target, source, game);
        } else {
            return testPlayerLink.choose(outcome, cards, target, source, game);
        }
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.chooseTarget(outcome, target, source, game);
        } else {
            return testPlayerLink.chooseTarget(outcome, target, source, game);
        }
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.chooseTarget(outcome, cards, target, source, game);
        } else {
            return testPlayerLink.chooseTarget(outcome, cards, target, source, game);
        }
    }

    @Override
    public boolean flipCoinResult(Game game) {
        return testPlayerLink.flipCoinResult(game);
    }

    @Override
    public int rollDieResult(int sides, Game game) {
        return testPlayerLink.rollDieResult(sides, game);
    }

    @Override
    public boolean isComputer() {
        if (testPlayerLink.canChooseByComputer()) {
            return super.isComputer();
        } else {
            return testPlayerLink.isComputer();
        }
    }
}
