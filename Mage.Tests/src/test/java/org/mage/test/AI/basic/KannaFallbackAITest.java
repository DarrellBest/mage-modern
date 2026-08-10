package org.mage.test.AI.basic;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayerKanna;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.util.Arrays;
import java.util.List;

/**
 * DARRELLBEST-FORK: behavioural guards for the fallback path (heuristicAttacks /
 * heuristicBlocks), replacing a KannaSmokeTest assertion that only checked
 * selectAttackers/selectBlockers were declared on ComputerPlayerKanna rather than
 * inherited. That assertion would have passed against the actual Critical-1 defect --
 * Kanna declared both overrides before the bug was found; the bug was that the
 * override's failure path delegated to super.selectAttackers()/super.selectBlockers(),
 * which are ComputerPlayer's empty no-op stubs ("do nothing, parent class must
 * implement it"). Inheriting the override would fail these tests the same as
 * regressing the failure path would, but only the latter is the actual risk now that
 * heuristicAttacks()/heuristicBlocks() exist.
 * <p>
 * Every test here points Kanna at an address nothing listens on, so every Ollama call
 * fails immediately (connection refused, no timeout wait) and every decision --
 * including combat -- is forced through the fallback path. Covers, in order:
 * Critical 1 (fallback is a real move, not a no-op), Important 2 (menace's
 * minimum-blockers restriction is respected rather than triggering the engine's
 * illegal-block retry storm), and Important 3 (lethality is re-checked as blocks are
 * assigned, rather than over-chumping every attacker once the opening total was
 * lethal).
 * <p>
 * A note on which menace test actually guards Important 2, confirmed by deliberately
 * reintroducing the bug (ignoring Permanent.getMinBlockedBy()) and rerunning both:
 * CombatGroup.checkBlockRestrictions only flags an assignment as illegal -- triggering
 * Combat.selectBlockers's retry loop -- when a fully legal assignment actually existed
 * among the defender's creatures and the declared one fell short of it ("if there
 * aren't any possible blocker configuration then it's legal due [to] mtg rules", per
 * that method's own comment). With only one possible blocker in the whole game, a
 * single illegal block against menace is self-healed silently (no retry) rather than
 * looping, because no legal configuration was ever reachable -- so
 * test_Kanna_DoesNotIllegallySingleBlockMenace continued to pass even with the bug
 * reintroduced; it guards correct behaviour in the impossible case, not the regression.
 * test_Kanna_DoubleBlocksMenace_WhenEnoughBlockersExist is the one that actually catches
 * Important 2: with two legal blockers available, a reintroduced single-blocker bug
 * reproduces the exact retry storm and IllegalArgumentException("AI can't find good
 * blocker combination") the coordinator described, turning this test into a hard ERROR.
 */
public class KannaFallbackAITest extends CardTestPlayerBaseAI {

    @Override
    public List<String> getFullSimulatedPlayers() {
        // PlayerB needs to be a real (full-AI) player here, not the base default's bare
        // TestPlayer -- the menace double-block tests attack with a single creature
        // blocked by two, which requires PlayerB to make a real combat-damage
        // assignment-order choice. A bare TestPlayer under setStrictChooseMode(true)
        // has no scripted answer for that and errors with "Missing CHOICE def" rather
        // than silently picking one, so PlayerB needs genuine AI decision-making too.
        return Arrays.asList("PlayerA", "PlayerB");
    }

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if ("PlayerA".equals(name)) {
            TestComputerPlayerKanna kannaPlayer = new TestComputerPlayerKanna(name, RangeOfInfluence.ONE, getSkillLevel());
            // nothing listens on this port -- every call fails fast with connection
            // refused, so every Kanna decision this game is forced through fallback
            kannaPlayer.setOllamaUrl("http://127.0.0.1:1");
            TestPlayer testPlayer = new TestPlayer(kannaPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    @Test
    public void test_Kanna_AttacksWithNothingToLose_WhenOllamaIsUnreachable() {
        // same "nothing to lose" attacking position as KannaSanityAITest -- opponent has
        // zero blockers, Kanna has one creature already in play that is safe to attack
        // with, so no other decision (land drop, casting) is needed to reach combat.
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 1);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 2);
    }

    @Test
    public void test_Kanna_DoesNotIllegallySingleBlockMenace_WhenOllamaIsUnreachable() {
        // Boggart Brute is a plain 3/2 with Menace and nothing else -- PermanentImpl's
        // own canBlock() does not check the minimum-blockers restriction menace sets
        // (Permanent.minBlockedBy), so a candidate-selection loop that only checks
        // canBlock() will treat a single Wall of Stone as a perfectly legal, favourable
        // blocker here. Kanna has exactly one potential blocker -- not enough to legally
        // block menace, and no legal double-block configuration exists at all -- so the
        // only correct move is to leave it unblocked and take the hit.
        //
        // NOTE: this scenario alone does NOT reproduce the engine's illegal-block retry
        // storm (confirmed by deliberately reintroducing the bug -- see the class
        // Javadoc): with only one possible blocker in the whole game, MTG rules (and
        // CombatGroup.checkBlockRestrictions) treat an unsatisfiable menace requirement
        // as legitimately unblockable and self-heal silently, no retry. This test is
        // still a genuine correctness check on the "impossible" branch, just not the
        // one that traps the specific defect -- see
        // test_Kanna_DoubleBlocksMenace_WhenEnoughBlockersExist for that.
        addCard(Zone.BATTLEFIELD, playerA, "Wall of Stone", 1); // 0/8, single blocker
        addCard(Zone.BATTLEFIELD, playerB, "Boggart Brute", 1); // 3/2, Menace
        addCard(Zone.BATTLEFIELD, playerB, "Forest", 1);

        attack(2, playerB, "Boggart Brute");

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        // took the full 3 -- Kanna correctly declined a block it could never make legal
        assertLife(playerA, 20 - 3);
    }

    @Test
    public void test_Kanna_DoubleBlocksMenace_WhenEnoughBlockersExist_WhenOllamaIsUnreachable() {
        // same attacker as above, but now Kanna has two potential blockers -- enough to
        // legally satisfy menace's minimum-blocked-by-two requirement, so
        // heuristicBlocks should actually use both. This is the test that actually
        // catches Important 2 (see the class Javadoc): with a legal double-block
        // possible, a reintroduced single-blocker bug does not just fail an assertion,
        // it reproduces the engine's real retry storm and errors with
        // IllegalArgumentException("AI can't find good blocker combination") -- confirmed
        // by deliberately reintroducing the bug and rerunning this test before writing
        // it up. Wall of Stone (0/8) rather than a smaller wall on purpose: the 3 power
        // has to go somewhere even split across two blockers, and a wall with toughness
        // <= 3 (e.g. Wall of Wood, 0/3) would take the full assignment on one of the two
        // and legitimately die -- a real, unfavourable trade, not a bug. 8 toughness
        // makes the double-block unambiguously safe for both blockers, isolating the
        // assignment-count behaviour this test targets from combat-math correctness
        // (CombatEvaluator's own tests already cover that separately).
        addCard(Zone.BATTLEFIELD, playerA, "Wall of Stone", 2); // 0/8 x2
        addCard(Zone.BATTLEFIELD, playerB, "Boggart Brute", 1); // 3/2, Menace
        addCard(Zone.BATTLEFIELD, playerB, "Forest", 1);

        attack(2, playerB, "Boggart Brute");

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        // neither Wall of Stone dies to a 3/2 (8 toughness each) and 0 power means
        // nothing hits back either -- a clean double-block, no damage through
        assertLife(playerA, 20);
    }

    @Test
    public void test_Kanna_DoesNotOverChump_WhenOneBlockAlreadyPreventsLethal_WhenOllamaIsUnreachable() {
        // Kanna at 5 life, facing two vanilla 3/3s (Watchwolf) with two 0/1 chump
        // blockers (Kobolds of Kher Keep) available. The opening alpha-strike total (6)
        // is lethal at 5 life, but chumping only the first 3/3 already brings the
        // remaining worst case (3) below life (5) -- the second attacker no longer
        // needs to be chumped. A fallback that computes "must chump" once from the
        // opening total and never re-checks it would sacrifice both kobolds instead of
        // one, ending at the same life total but with no creatures left on board.
        setLife(playerA, 5);
        addCard(Zone.BATTLEFIELD, playerA, "Kobolds of Kher Keep", 2); // 0/1 x2
        addCard(Zone.BATTLEFIELD, playerB, "Watchwolf", 2); // 3/3 x2
        addCard(Zone.BATTLEFIELD, playerB, "Forest", 2);
        addCard(Zone.BATTLEFIELD, playerB, "Plains", 2);

        attack(2, playerB, "Watchwolf");
        attack(2, playerB, "Watchwolf");

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        // one kobold chumped the first (biggest-threat-first) attacker, preventing 3;
        // the second went through unblocked because blocking it was no longer necessary
        // to survive -- life ends at 5 - 3 = 2, with the second kobold still alive
        assertLife(playerA, 5 - 3);
        assertPermanentCount(playerA, "Kobolds of Kher Keep", 1);
    }
}
