# Kanna Agentic Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild Kanna as a fully agentic local-LLM-driven Magic player where heuristics compute (combat math, legality, mana) and the model judges (which line matters, whether a trade is worth taking). No MCTS, no minimax.

**Architecture:** `ComputerPlayerKanna` drops `ComputerPlayerMCTS` and extends `ComputerPlayer`, inheriting working implementations for the mechanical callbacks it does not drive. It overrides four decision points — `priority()`, `chooseTarget()`, `selectAttackers()`, `selectBlockers()` — and delegates each to `KannaAgent`. Before the model is consulted, a pure computation layer (`CombatEvaluator`, `ActionCatalog`, `ActionRanker`) enumerates and annotates every legal option with exact consequences; the model then picks one, using read-only inspection tools if it wants to look deeper. The same computation layer stands in when the model fails.

**Tech Stack:** Java 8, Maven, Gson 2.13.2, log4j, JUnit 4 (junit-vintage on the JUnit 5 platform), XMage engine APIs, Ollama HTTP API.

**Spec:** `docs/superpowers/specs/2026-08-10-kanna-agentic-core-design.md`

## Global Constraints

- **Java 8 only** (`<java.version>8</java.version>` in root `pom.xml`). No `var`, no records, no text blocks, no `List.of()`/`Map.of()`, no `Stream.toList()`, no `Optional.orElseThrow()` without an argument.
- **The Kanna plugin module uses `<sourceDirectory>src</sourceDirectory>`**, so sources live at `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/…` — NOT `src/main/java`. Tests for this module go in `Mage.Tests` (the plugin module has no test source root).
- **Never add a `Co-Authored-By: Claude` or any Claude/Anthropic trailer** to commit messages. Hard repo rule.
- **Every edit to a file in an upstream-tracked directory carries** `// DARRELLBEST-FORK (keep on merge/rebase from upstream):` with a short reason. XML comments must NOT contain a double hyphen. The whole Kanna plugin directory counts as upstream-tracked for this purpose (that is the standing convention in this repo).
- **No MCTS.** Nothing in this plan may import `mage.player.ai.ComputerPlayerMCTS` or add a dependency on `mage-player-ai-mcts`.
- **Working branch:** `ui-modernization`. Do not create a new branch.
- **Default model** is `xmage-ai-qwen3.6:latest`.

## Build guidance (applies to every task)

All `org.mage` artifacts are installed in `~/.m2` at 1.4.60. **Never use `-am`** — it rebuilds Mage.Sets and takes many minutes.

After editing the Kanna module, reinstall it before anything that depends on it:
```bash
mvn -q -DskipTests -pl Mage.Server.Plugins/Mage.Player.AI.Kanna install
```
Run Kanna's tests from `Mage.Tests`:
```bash
mvn -q -pl Mage.Tests test -Dtest=<TestClassName>
```

## Key engine facts (verified — do not re-derive)

- `ComputerPlayer.priority(Game)` is a **no-op that calls `pass(game)`** — "minimum implementation for do nothing". Kanna MUST override it. Failing to is a silent bug, not a crash; it is the exact trap commit `f1e0ea29e5` fixed.
- `ComputerPlayer(String name, RangeOfInfluence range)` — **two args, no skill parameter** (unlike `ComputerPlayer7`/`ComputerPlayerMCTS`, which take three).
- `Player.getPlayable(Game game, boolean hidden)` → `List<ActivatedAbility>` — the legal actions.
- `Player.getPlayableOptions(Ability ability, Game game)` → `List<Ability>` — target/cost variants of one ability.
- `Player.activateAbility(ActivatedAbility ability, Game game)` → `boolean`.
- `Player.pass(Game game)`.
- `mage.abilities.common.PassAbility extends ActivatedAbilityImpl`.
- Signatures to override, verbatim:
  - `public boolean priority(Game game)`
  - `public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game)`
  - `public void selectAttackers(Game game, UUID attackingPlayerId)`
  - `public void selectBlockers(Ability source, Game game, UUID defendingPlayerId)`
  - `public boolean playMana(Ability ability, ManaCost unpaid, String promptText, Game game)`
- Keyword classes all exist under `mage.abilities.keyword`: `FlyingAbility`, `ReachAbility`, `MenaceAbility`, `DeathtouchAbility`, `FirstStrikeAbility`, `DoubleStrikeAbility`, `TrampleAbility`. Test with `permanent.getAbilities(game).containsClass(FlyingAbility.class)`.
- Combat helpers already used by the existing Kanna: `getAvailableAttackers(defenderId, game)`, `getAvailableBlockers(game)`, `attackingPlayer.declareAttacker(id, defenderId, game, false)`, `defendingPlayer.declareBlocker(defenderId, blockerId, attackerId, game, false)`, `blocker.canBlock(attackerId, game)`, `game.getOpponents(playerId, true)`, `game.getCombat().getAttackers()`, `game.getCombat().getDefendingPlayerId(attackerId, game)`.
- `Permanent`: `getPower().getValue()`, `getToughness().getValue()`, `isTapped()`, `isCreature(game)`, `getName()`, `getId()`.

---

## File Structure

**New, in `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/`:**

| File | Responsibility |
|---|---|
| `CreatureView.java` | Immutable snapshot of a creature: id, name, P/T, the seven keywords, tapped. Extraction from `Permanent` lives here |
| `CombatEvaluator.java` | Pure combat math over `CreatureView`. No `Game`, no engine calls |
| `AttackOutcome.java` | Result value type from `CombatEvaluator` |
| `ActionCatalog.java` | Legal actions → short ids; ids back to `ActivatedAbility` |
| `RankedAction.java` | One shortlist entry: id, label, computed reason, score |
| `ActionRanker.java` | Scores and shortlists actions using `CombatEvaluator` |
| `GameStateFormatter.java` | `Game` + annotations → prompt text |
| `OllamaClient.java` | HTTP, tool-schema assembly, response parsing, one retry |
| `ToolCall.java` | Parsed tool call: name + arguments |
| `InspectionTools.java` | Read-only tool handlers answered from `Game` |
| `KannaAgent.java` | The decision loop: prompt → inspect or commit → validate |
| `Decision.java` | What the agent returns: chosen id(s), or "heuristic fallback" |
| `ManaPlanner.java` | Cost payment preserving colors needed in hand |

**Modified:**
- `ComputerPlayerKanna.java` — rewritten: extends `ComputerPlayer`, four overrides, thin
- `Mage.Server.Plugins/Mage.Player.AI.Kanna/pom.xml` — swap `mage-player-ai-mcts` → `mage-player-ai`

**Tests, in `Mage.Tests/src/test/java/org/mage/test/kanna/`:**
`CreatureViewTest`, `CombatEvaluatorTest`, `ActionCatalogTest`, `ActionRankerTest`, `GameStateFormatterTest`, `OllamaClientTest`, `KannaAgentTest`, `ManaPlannerTest`.

---

### Task 1: `CreatureView` and `CombatEvaluator`

The computation core. Pure functions over value types, so every combat rule is testable without an engine.

**Files:**
- Create: `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/CreatureView.java`
- Create: `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/AttackOutcome.java`
- Create: `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/CombatEvaluator.java`
- Test: `Mage.Tests/src/test/java/org/mage/test/kanna/CombatEvaluatorTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `CreatureView(String id, String name, int power, int toughness, boolean flying, boolean reach, boolean menace, boolean deathtouch, boolean firstStrike, boolean doubleStrike, boolean trample, boolean tapped)` — all fields public final; plus `static CreatureView from(String id, Permanent p, Game game)`.
  - `AttackOutcome` — public final `boolean attackerDies`, `List<String> blockersThatDie`, `int damageThrough`, `boolean unblocked`, `String summary`.
  - `CombatEvaluator` — `static boolean canBlock(CreatureView blocker, CreatureView attacker)`, `static List<CreatureView> legalBlockers(CreatureView attacker, List<CreatureView> candidates)`, `static AttackOutcome evaluateUnblocked(CreatureView attacker)`, `static AttackOutcome evaluateBlockedBy(CreatureView attacker, List<CreatureView> blockers)`, `static AttackOutcome evaluateLikely(CreatureView attacker, List<CreatureView> availableBlockers)`, `static boolean isLethal(int damageThrough, int defenderLife)`.

- [ ] **Step 1: Write the failing test**

Create `Mage.Tests/src/test/java/org/mage/test/kanna/CombatEvaluatorTest.java`:

```java
package org.mage.test.kanna;

import mage.player.ai.kanna.AttackOutcome;
import mage.player.ai.kanna.CombatEvaluator;
import mage.player.ai.kanna.CreatureView;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CombatEvaluatorTest {

    private CreatureView plain(String id, String name, int p, int t) {
        return new CreatureView(id, name, p, t, false, false, false, false, false, false, false, false);
    }

    private CreatureView with(String id, String name, int p, int t,
                              boolean flying, boolean reach, boolean menace, boolean deathtouch,
                              boolean firstStrike, boolean doubleStrike, boolean trample) {
        return new CreatureView(id, name, p, t, flying, reach, menace, deathtouch,
                firstStrike, doubleStrike, trample, false);
    }

    // ---- blocking legality ----

    @Test
    public void groundCreatureCannotBlockFlier() {
        CreatureView flier = with("a", "Serra Angel", 4, 4, true, false, false, false, false, false, false);
        assertFalse(CombatEvaluator.canBlock(plain("b", "Hill Giant", 3, 3), flier));
    }

    @Test
    public void reachCanBlockFlier() {
        CreatureView flier = with("a", "Serra Angel", 4, 4, true, false, false, false, false, false, false);
        CreatureView spider = with("b", "Giant Spider", 2, 4, false, true, false, false, false, false, false);
        assertTrue(CombatEvaluator.canBlock(spider, flier));
    }

    @Test
    public void flierCanBlockGroundCreature() {
        CreatureView flier = with("b", "Serra Angel", 4, 4, true, false, false, false, false, false, false);
        assertTrue(CombatEvaluator.canBlock(flier, plain("a", "Hill Giant", 3, 3)));
    }

    @Test
    public void tappedCreatureCannotBlock() {
        CreatureView tapped = new CreatureView("b", "Hill Giant", 3, 3,
                false, false, false, false, false, false, false, true);
        assertFalse(CombatEvaluator.canBlock(tapped, plain("a", "Bear", 2, 2)));
    }

    @Test
    public void menaceNeedsTwoBlockers() {
        CreatureView menacer = with("a", "Boggart", 3, 3, false, false, true, false, false, false, false);
        List<CreatureView> one = new ArrayList<CreatureView>();
        one.add(plain("b", "Bear", 2, 2));
        assertTrue(CombatEvaluator.legalBlockers(menacer, one).isEmpty());

        List<CreatureView> two = Arrays.asList(plain("b", "Bear", 2, 2), plain("c", "Bear2", 2, 2));
        assertEquals(2, CombatEvaluator.legalBlockers(menacer, two).size());
    }

    // ---- damage math ----

    @Test
    public void unblockedDealsFullDamage() {
        AttackOutcome o = CombatEvaluator.evaluateUnblocked(plain("a", "Bear", 2, 2));
        assertEquals(2, o.damageThrough);
        assertFalse(o.attackerDies);
        assertTrue(o.unblocked);
    }

    @Test
    public void evenTradeKillsBoth() {
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(plain("a", "Bear", 2, 2),
                Arrays.asList(plain("b", "Bear2", 2, 2)));
        assertTrue(o.attackerDies);
        assertEquals(Arrays.asList("Bear2"), o.blockersThatDie);
        assertEquals(0, o.damageThrough);
    }

    @Test
    public void biggerBlockerKillsAttackerAndSurvives() {
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(plain("a", "Bear", 2, 2),
                Arrays.asList(plain("b", "Hill Giant", 3, 3)));
        assertTrue(o.attackerDies);
        assertTrue(o.blockersThatDie.isEmpty());
    }

    @Test
    public void firstStrikeKillsBlockerWithoutDying() {
        CreatureView fs = with("a", "White Knight", 2, 2, false, false, false, false, true, false, false);
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(fs, Arrays.asList(plain("b", "Bear", 2, 2)));
        assertFalse("first striker kills before taking damage", o.attackerDies);
        assertEquals(Arrays.asList("Bear"), o.blockersThatDie);
    }

    @Test
    public void firstStrikeDoesNotSaveAttackerFromBiggerBlocker() {
        CreatureView fs = with("a", "White Knight", 2, 2, false, false, false, false, true, false, false);
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(fs, Arrays.asList(plain("b", "Wall", 0, 5)));
        assertFalse(o.attackerDies);
        assertTrue(o.blockersThatDie.isEmpty());
    }

    @Test
    public void doubleStrikeDealsDamageTwice() {
        CreatureView ds = with("a", "Ronin", 2, 2, false, false, false, false, false, true, false);
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(ds, Arrays.asList(plain("b", "Bear3", 3, 3)));
        assertEquals(Arrays.asList("Bear3"), o.blockersThatDie);
        assertFalse("double strike kills the 3/3 in the first-strike step", o.attackerDies);
    }

    @Test
    public void deathtouchKillsAnySizeBlocker() {
        CreatureView dt = with("a", "Adder", 1, 1, false, false, false, true, false, false, false);
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(dt, Arrays.asList(plain("b", "Wall", 0, 8)));
        assertEquals(Arrays.asList("Wall"), o.blockersThatDie);
    }

    @Test
    public void deathtouchBlockerKillsBigAttacker() {
        CreatureView dtBlocker = with("b", "Adder", 1, 1, false, false, false, true, false, false, false);
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(plain("a", "Giant", 6, 6),
                Arrays.asList(dtBlocker));
        assertTrue(o.attackerDies);
    }

    @Test
    public void trampleSpillsExcessDamage() {
        CreatureView tr = with("a", "Rhino", 5, 5, false, false, false, false, false, false, true);
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(tr, Arrays.asList(plain("b", "Bear", 2, 2)));
        assertEquals("5 power minus 2 toughness tramples over", 3, o.damageThrough);
    }

    @Test
    public void noTrampleMeansNoDamageThrough() {
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(plain("a", "Rhino", 5, 5),
                Arrays.asList(plain("b", "Bear", 2, 2)));
        assertEquals(0, o.damageThrough);
    }

    @Test
    public void multipleBlockersGangUp() {
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(plain("a", "Giant", 4, 4),
                Arrays.asList(plain("b", "Bear", 2, 2), plain("c", "Bear2", 2, 2)));
        assertTrue(o.attackerDies);
    }

    // ---- likely outcome and lethal ----

    @Test
    public void evaluateLikelyReportsUnblockedWhenNoLegalBlockerExists() {
        CreatureView flier = with("a", "Serra Angel", 4, 4, true, false, false, false, false, false, false);
        AttackOutcome o = CombatEvaluator.evaluateLikely(flier,
                Arrays.asList(plain("b", "Hill Giant", 3, 3)));
        assertTrue(o.unblocked);
        assertEquals(4, o.damageThrough);
    }

    @Test
    public void isLethalComparesDamageToLife() {
        assertTrue(CombatEvaluator.isLethal(5, 5));
        assertTrue(CombatEvaluator.isLethal(6, 5));
        assertFalse(CombatEvaluator.isLethal(4, 5));
    }

    @Test
    public void summaryIsNonEmptyAndMentionsTheAttacker() {
        AttackOutcome o = CombatEvaluator.evaluateBlockedBy(plain("a", "Bear", 2, 2),
                Arrays.asList(plain("b", "Bear2", 2, 2)));
        assertTrue(o.summary.contains("Bear"));
        assertFalse(o.summary.trim().isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl Mage.Tests test -Dtest=CombatEvaluatorTest`
Expected: FAIL — compilation error, `CreatureView` / `CombatEvaluator` / `AttackOutcome` do not exist.

- [ ] **Step 3: Write `CreatureView`**

Create `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/CreatureView.java`:

```java
package mage.player.ai.kanna;

import mage.abilities.keyword.DeathtouchAbility;
import mage.abilities.keyword.DoubleStrikeAbility;
import mage.abilities.keyword.FirstStrikeAbility;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.MenaceAbility;
import mage.abilities.keyword.ReachAbility;
import mage.abilities.keyword.TrampleAbility;
import mage.game.Game;
import mage.game.permanent.Permanent;

/**
 * Immutable snapshot of the creature facts that decide combat.
 * <p>
 * Combat math runs on this rather than on Permanent so it is a pure function of
 * plain data: no Game, no engine callbacks, and therefore unit-testable against
 * hand-constructed board states instead of a live game.
 *
 * @author Darrell Best
 */
public final class CreatureView {

    public final String id;
    public final String name;
    public final int power;
    public final int toughness;
    public final boolean flying;
    public final boolean reach;
    public final boolean menace;
    public final boolean deathtouch;
    public final boolean firstStrike;
    public final boolean doubleStrike;
    public final boolean trample;
    public final boolean tapped;

    public CreatureView(String id, String name, int power, int toughness,
                        boolean flying, boolean reach, boolean menace, boolean deathtouch,
                        boolean firstStrike, boolean doubleStrike, boolean trample, boolean tapped) {
        this.id = id;
        this.name = name;
        this.power = power;
        this.toughness = toughness;
        this.flying = flying;
        this.reach = reach;
        this.menace = menace;
        this.deathtouch = deathtouch;
        this.firstStrike = firstStrike;
        this.doubleStrike = doubleStrike;
        this.trample = trample;
        this.tapped = tapped;
    }

    public static CreatureView from(String id, Permanent permanent, Game game) {
        return new CreatureView(
                id,
                permanent.getName(),
                permanent.getPower().getValue(),
                permanent.getToughness().getValue(),
                permanent.getAbilities(game).containsClass(FlyingAbility.class),
                permanent.getAbilities(game).containsClass(ReachAbility.class),
                permanent.getAbilities(game).containsClass(MenaceAbility.class),
                permanent.getAbilities(game).containsClass(DeathtouchAbility.class),
                permanent.getAbilities(game).containsClass(FirstStrikeAbility.class),
                permanent.getAbilities(game).containsClass(DoubleStrikeAbility.class),
                permanent.getAbilities(game).containsClass(TrampleAbility.class),
                permanent.isTapped()
        );
    }

    /**
     * Compact "Name (2/2) [Flying, Deathtouch]" rendering for prompts.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" (").append(power).append('/').append(toughness).append(')');
        StringBuilder kw = new StringBuilder();
        appendKeyword(kw, flying, "Flying");
        appendKeyword(kw, reach, "Reach");
        appendKeyword(kw, menace, "Menace");
        appendKeyword(kw, deathtouch, "Deathtouch");
        appendKeyword(kw, firstStrike, "First Strike");
        appendKeyword(kw, doubleStrike, "Double Strike");
        appendKeyword(kw, trample, "Trample");
        if (kw.length() > 0) {
            sb.append(" [").append(kw).append(']');
        }
        return sb.toString();
    }

    private static void appendKeyword(StringBuilder sb, boolean present, String label) {
        if (present) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(label);
        }
    }
}
```

- [ ] **Step 4: Write `AttackOutcome`**

Create `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/AttackOutcome.java`:

```java
package mage.player.ai.kanna;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Computed result of one attack. Every field is arithmetic, not opinion --
 * the model decides whether the outcome is worth having.
 *
 * @author Darrell Best
 */
public final class AttackOutcome {

    public final boolean attackerDies;
    public final List<String> blockersThatDie;
    public final int damageThrough;
    public final boolean unblocked;
    public final String summary;

    public AttackOutcome(boolean attackerDies, List<String> blockersThatDie,
                         int damageThrough, boolean unblocked, String summary) {
        this.attackerDies = attackerDies;
        this.blockersThatDie = Collections.unmodifiableList(new ArrayList<String>(blockersThatDie));
        this.damageThrough = damageThrough;
        this.unblocked = unblocked;
        this.summary = summary;
    }
}
```

- [ ] **Step 5: Write `CombatEvaluator`**

Create `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/CombatEvaluator.java`:

```java
package mage.player.ai.kanna;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact combat arithmetic. Pure: takes CreatureView values, returns an
 * AttackOutcome, touches nothing else.
 * <p>
 * One evaluator serves three consumers, which is what keeps the heuristic and
 * model halves complementary rather than duplicated: it annotates the prompt,
 * it drives ranking, and it is the fallback when the model fails.
 *
 * @author Darrell Best
 */
public final class CombatEvaluator {

    private CombatEvaluator() {
    }

    public static boolean canBlock(CreatureView blocker, CreatureView attacker) {
        if (blocker.tapped) {
            return false;
        }
        if (attacker.flying && !(blocker.flying || blocker.reach)) {
            return false;
        }
        return true;
    }

    /**
     * @return the candidates that could legally block this attacker, or an empty
     * list when no legal assignment exists (menace with only one able blocker).
     */
    public static List<CreatureView> legalBlockers(CreatureView attacker, List<CreatureView> candidates) {
        List<CreatureView> able = new ArrayList<CreatureView>();
        for (CreatureView candidate : candidates) {
            if (canBlock(candidate, attacker)) {
                able.add(candidate);
            }
        }
        if (attacker.menace && able.size() < 2) {
            return new ArrayList<CreatureView>();
        }
        return able;
    }

    public static AttackOutcome evaluateUnblocked(CreatureView attacker) {
        int damage = attacker.power * (attacker.doubleStrike ? 2 : 1);
        String summary = attacker.name + " is unblocked and deals " + damage + " damage";
        return new AttackOutcome(false, new ArrayList<String>(), damage, true, summary);
    }

    public static AttackOutcome evaluateBlockedBy(CreatureView attacker, List<CreatureView> blockers) {
        if (blockers.isEmpty()) {
            return evaluateUnblocked(attacker);
        }

        // First-strike step: the attacker strikes early if it has first or double strike
        // and no blocker does. Anything it kills there never deals damage back.
        boolean attackerStrikesFirst = (attacker.firstStrike || attacker.doubleStrike)
                && !anyStrikesFirst(blockers);

        List<String> dead = new ArrayList<String>();
        int remainingPower = attacker.power;
        for (CreatureView blocker : blockers) {
            if (remainingPower <= 0) {
                break;
            }
            int needed = attacker.deathtouch ? 1 : blocker.toughness;
            if (remainingPower >= needed) {
                dead.add(blocker.name);
                remainingPower -= needed;
            } else {
                remainingPower = 0;
            }
        }

        int damageBack = 0;
        for (CreatureView blocker : blockers) {
            boolean killedBeforeItStruck = attackerStrikesFirst && dead.contains(blocker.name);
            if (!killedBeforeItStruck) {
                damageBack += blocker.power * (blocker.doubleStrike ? 2 : 1);
                if (blocker.deathtouch && blocker.power > 0) {
                    damageBack = Math.max(damageBack, attacker.toughness);
                }
            }
        }
        boolean attackerDies = damageBack >= attacker.toughness;

        int damageThrough = 0;
        if (attacker.trample) {
            int soak = 0;
            for (CreatureView blocker : blockers) {
                soak += attacker.deathtouch ? 1 : blocker.toughness;
            }
            damageThrough = Math.max(0, attacker.power - soak);
        }

        StringBuilder summary = new StringBuilder();
        summary.append(attacker.name).append(" blocked by ").append(blockers.size())
                .append(blockers.size() == 1 ? " creature" : " creatures").append(": ");
        summary.append(attackerDies ? attacker.name + " dies" : attacker.name + " survives");
        if (!dead.isEmpty()) {
            summary.append(", kills ").append(join(dead));
        }
        if (damageThrough > 0) {
            summary.append(", ").append(damageThrough).append(" tramples through");
        }

        return new AttackOutcome(attackerDies, dead, damageThrough, false, summary.toString());
    }

    /**
     * Best guess at what happens if this creature attacks: unblocked when no legal
     * block exists, otherwise the defender's most damaging single block.
     */
    public static AttackOutcome evaluateLikely(CreatureView attacker, List<CreatureView> availableBlockers) {
        List<CreatureView> able = legalBlockers(attacker, availableBlockers);
        if (able.isEmpty()) {
            return evaluateUnblocked(attacker);
        }
        AttackOutcome worst = null;
        for (CreatureView blocker : able) {
            List<CreatureView> single = new ArrayList<CreatureView>();
            single.add(blocker);
            AttackOutcome outcome = evaluateBlockedBy(attacker, single);
            if (worst == null || rank(outcome) < rank(worst)) {
                worst = outcome;
            }
        }
        return worst;
    }

    public static boolean isLethal(int damageThrough, int defenderLife) {
        return damageThrough >= defenderLife;
    }

    /** Higher is better for the attacker. Used only to pick the defender's best block. */
    private static int rank(AttackOutcome outcome) {
        return (outcome.attackerDies ? -10 : 0) + outcome.blockersThatDie.size() * 5 + outcome.damageThrough;
    }

    private static boolean anyStrikesFirst(List<CreatureView> creatures) {
        for (CreatureView creature : creatures) {
            if (creature.firstStrike || creature.doubleStrike) {
                return true;
            }
        }
        return false;
    }

    private static String join(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(name);
        }
        return sb.toString();
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q -DskipTests -pl Mage.Server.Plugins/Mage.Player.AI.Kanna install && mvn -q -pl Mage.Tests test -Dtest=CombatEvaluatorTest`
Expected: PASS, 20 tests.

If `doubleStrikeDealsDamageTwice` fails, re-read the first-strike branch: a double striker kills a 3/3 in the first-strike step only if its power alone is enough, which it is not for a 2/2 — check the test's expectation against your implementation and fix whichever is wrong, but do NOT weaken the assertion to make it pass. Report the discrepancy if the rule is genuinely ambiguous.

- [ ] **Step 7: Commit**

```bash
git add Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/CreatureView.java \
        Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/AttackOutcome.java \
        Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/CombatEvaluator.java \
        Mage.Tests/src/test/java/org/mage/test/kanna/CombatEvaluatorTest.java
git commit -m "kanna: add pure combat evaluator over creature snapshots"
```

---

### Task 2: `ActionCatalog`

**Files:**
- Create: `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ActionCatalog.java`
- Test: `Mage.Tests/src/test/java/org/mage/test/kanna/ActionCatalogTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `ActionCatalog` — `void add(ActivatedAbility ability, String label)`, `String idFor(ActivatedAbility ability)`, `ActivatedAbility resolve(String id)` (null when unknown), `List<String> ids()`, `String labelFor(String id)`, `int size()`. Ids are `act-0`, `act-1`, … assigned in insertion order.

- [ ] **Step 1: Write the failing test**

Create `Mage.Tests/src/test/java/org/mage/test/kanna/ActionCatalogTest.java`:

```java
package org.mage.test.kanna;

import mage.abilities.common.PassAbility;
import mage.player.ai.kanna.ActionCatalog;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotEquals;

public class ActionCatalogTest {

    @Test
    public void assignsSequentialIdsInInsertionOrder() {
        ActionCatalog catalog = new ActionCatalog();
        PassAbility first = new PassAbility();
        PassAbility second = new PassAbility();
        catalog.add(first, "Pass");
        catalog.add(second, "Pass again");
        assertEquals("act-0", catalog.idFor(first));
        assertEquals("act-1", catalog.idFor(second));
        assertEquals(2, catalog.size());
    }

    @Test
    public void resolvesIdBackToTheSameAbilityInstance() {
        ActionCatalog catalog = new ActionCatalog();
        PassAbility ability = new PassAbility();
        catalog.add(ability, "Pass");
        assertEquals(ability, catalog.resolve("act-0"));
    }

    @Test
    public void unknownIdResolvesToNull() {
        ActionCatalog catalog = new ActionCatalog();
        catalog.add(new PassAbility(), "Pass");
        assertNull(catalog.resolve("act-99"));
        assertNull(catalog.resolve("nonsense"));
        assertNull(catalog.resolve(null));
    }

    @Test
    public void idsAreNeverReusedWithinOneCatalog() {
        ActionCatalog catalog = new ActionCatalog();
        for (int i = 0; i < 50; i++) {
            catalog.add(new PassAbility(), "Pass " + i);
        }
        assertEquals(50, catalog.ids().size());
        assertEquals(50, new java.util.HashSet<String>(catalog.ids()).size());
    }

    @Test
    public void twoDistinctAbilitiesGetDistinctIds() {
        ActionCatalog catalog = new ActionCatalog();
        PassAbility a = new PassAbility();
        PassAbility b = new PassAbility();
        catalog.add(a, "A");
        catalog.add(b, "B");
        assertNotEquals(catalog.idFor(a), catalog.idFor(b));
    }

    @Test
    public void labelIsRetrievableById() {
        ActionCatalog catalog = new ActionCatalog();
        catalog.add(new PassAbility(), "Play Mountain");
        assertEquals("Play Mountain", catalog.labelFor("act-0"));
    }

    @Test
    public void idForUnknownAbilityIsNull() {
        ActionCatalog catalog = new ActionCatalog();
        assertNull(catalog.idFor(new PassAbility()));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl Mage.Tests test -Dtest=ActionCatalogTest`
Expected: FAIL — `ActionCatalog` does not exist.

- [ ] **Step 3: Write `ActionCatalog`**

Create `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ActionCatalog.java`:

```java
package mage.player.ai.kanna;

import mage.abilities.ActivatedAbility;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-way map between short synthetic ids and the legal abilities they stand for.
 * <p>
 * Short ids (act-0, act-1) rather than raw UUIDs: a model echoing back a UUID gets
 * it wrong often enough to matter, and a wrong id is indistinguishable from a
 * hallucinated one.
 *
 * @author Darrell Best
 */
public final class ActionCatalog {

    private final Map<String, ActivatedAbility> byId = new LinkedHashMap<String, ActivatedAbility>();
    private final Map<String, String> labels = new LinkedHashMap<String, String>();
    // identity, not equals: two distinct playable options can compare equal but must stay distinct
    private final Map<ActivatedAbility, String> idByAbility = new IdentityHashMap<ActivatedAbility, String>();

    public void add(ActivatedAbility ability, String label) {
        String id = "act-" + byId.size();
        byId.put(id, ability);
        labels.put(id, label);
        idByAbility.put(ability, id);
    }

    public String idFor(ActivatedAbility ability) {
        return idByAbility.get(ability);
    }

    public ActivatedAbility resolve(String id) {
        if (id == null) {
            return null;
        }
        return byId.get(id);
    }

    public String labelFor(String id) {
        return labels.get(id);
    }

    public List<String> ids() {
        return new ArrayList<String>(byId.keySet());
    }

    public int size() {
        return byId.size();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -DskipTests -pl Mage.Server.Plugins/Mage.Player.AI.Kanna install && mvn -q -pl Mage.Tests test -Dtest=ActionCatalogTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ActionCatalog.java \
        Mage.Tests/src/test/java/org/mage/test/kanna/ActionCatalogTest.java
git commit -m "kanna: add action catalog mapping short ids to abilities"
```

---

### Task 3: `OllamaClient` and `ToolCall`

Extract every HTTP, schema, and parsing concern out of the current `ComputerPlayerKanna` into a testable client, and add the retry the spec requires.

**Files:**
- Create: `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ToolCall.java`
- Create: `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/OllamaClient.java`
- Test: `Mage.Tests/src/test/java/org/mage/test/kanna/OllamaClientTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `ToolCall` — public final `String name`, `JsonObject arguments`.
  - `OllamaClient(String baseUrl, String model)`; `ToolCall call(String prompt, List<JsonObject> tools)` throws `IOException` (null when the response contained no tool call, after one retry); `static ToolCall parseResponse(String json)` (package-visible for tests, returns null when absent/malformed); `static JsonObject tool(String name, String description, JsonObject parameters)`; `static JsonObject pairArraySchema(String arrayName, String field1, String field2)`; `static JsonObject stringFieldSchema(String fieldName)`; `void setTimeoutMs(int ms)`; `int getRetryCount()`.

- [ ] **Step 1: Write the failing test**

Create `Mage.Tests/src/test/java/org/mage/test/kanna/OllamaClientTest.java`:

```java
package org.mage.test.kanna;

import com.google.gson.JsonObject;
import mage.player.ai.kanna.OllamaClient;
import mage.player.ai.kanna.ToolCall;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OllamaClientTest {

    private static final String WITH_TOOL_CALL =
            "{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"function\":{"
                    + "\"name\":\"choose_action\",\"arguments\":{\"action_id\":\"act-3\"}}}]},"
                    + "\"prompt_eval_count\":523,\"eval_count\":88}";

    private static final String PROSE_NO_TOOL_CALL =
            "{\"message\":{\"role\":\"assistant\",\"content\":"
                    + "\"Looking at the board state, I need to maximize damage.\"},"
                    + "\"prompt_eval_count\":523,\"eval_count\":40}";

    @Test
    public void parsesAToolCallAndItsArguments() {
        ToolCall call = OllamaClient.parseResponse(WITH_TOOL_CALL);
        assertNotNull(call);
        assertEquals("choose_action", call.name);
        assertEquals("act-3", call.arguments.get("action_id").getAsString());
    }

    @Test
    public void proseResponseWithNoToolCallParsesToNull() {
        assertNull(OllamaClient.parseResponse(PROSE_NO_TOOL_CALL));
    }

    @Test
    public void emptyToolCallArrayParsesToNull() {
        assertNull(OllamaClient.parseResponse("{\"message\":{\"tool_calls\":[]}}"));
    }

    @Test
    public void malformedJsonParsesToNullRatherThanThrowing() {
        assertNull(OllamaClient.parseResponse("not json at all"));
        assertNull(OllamaClient.parseResponse(""));
        assertNull(OllamaClient.parseResponse("{}"));
    }

    @Test
    public void argumentsDeliveredAsAJsonStringAreStillParsed() {
        // some models return arguments as a JSON-encoded string rather than an object
        String body = "{\"message\":{\"tool_calls\":[{\"function\":{"
                + "\"name\":\"choose_action\",\"arguments\":\"{\\\"action_id\\\":\\\"act-7\\\"}\"}}]}}";
        ToolCall call = OllamaClient.parseResponse(body);
        assertNotNull(call);
        assertEquals("act-7", call.arguments.get("action_id").getAsString());
    }

    @Test
    public void toolSchemaHasTheShapeOllamaExpects() {
        JsonObject params = OllamaClient.stringFieldSchema("action_id");
        JsonObject tool = OllamaClient.tool("choose_action", "Choose one action.", params);
        assertEquals("function", tool.get("type").getAsString());
        JsonObject fn = tool.getAsJsonObject("function");
        assertEquals("choose_action", fn.get("name").getAsString());
        assertEquals("Choose one action.", fn.get("description").getAsString());
        assertNotNull(fn.getAsJsonObject("parameters"));
    }

    @Test
    public void stringFieldSchemaMarksTheFieldRequired() {
        JsonObject schema = OllamaClient.stringFieldSchema("action_id");
        assertEquals("object", schema.get("type").getAsString());
        assertNotNull(schema.getAsJsonObject("properties").getAsJsonObject("action_id"));
        assertTrue(schema.getAsJsonArray("required").toString().contains("action_id"));
    }

    @Test
    public void pairArraySchemaDescribesAnArrayOfTwoFieldObjects() {
        JsonObject schema = OllamaClient.pairArraySchema("attacks", "attacker_id", "defender_id");
        JsonObject attacks = schema.getAsJsonObject("properties").getAsJsonObject("attacks");
        assertEquals("array", attacks.get("type").getAsString());
        JsonObject item = attacks.getAsJsonObject("items");
        assertEquals("object", item.get("type").getAsString());
        assertNotNull(item.getAsJsonObject("properties").getAsJsonObject("attacker_id"));
        assertNotNull(item.getAsJsonObject("properties").getAsJsonObject("defender_id"));
        assertTrue(item.getAsJsonArray("required").toString().contains("defender_id"));
    }

    @Test
    public void unreachableHostSurfacesAsIOExceptionNotSilence() {
        OllamaClient client = new OllamaClient("http://127.0.0.1:1", "any-model");
        client.setTimeoutMs(300);
        try {
            client.call("prompt", new java.util.ArrayList<JsonObject>());
            org.junit.Assert.fail("expected IOException");
        } catch (java.io.IOException expected) {
            assertTrue(client.getRetryCount() >= 1);
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl Mage.Tests test -Dtest=OllamaClientTest`
Expected: FAIL — `OllamaClient` / `ToolCall` do not exist.

- [ ] **Step 3: Write `ToolCall`**

Create `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ToolCall.java`:

```java
package mage.player.ai.kanna;

import com.google.gson.JsonObject;

/**
 * A parsed tool call from the model.
 *
 * @author Darrell Best
 */
public final class ToolCall {

    public final String name;
    public final JsonObject arguments;

    public ToolCall(String name, JsonObject arguments) {
        this.name = name;
        this.arguments = arguments;
    }
}
```

- [ ] **Step 4: Write `OllamaClient`**

Create `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/OllamaClient.java`:

```java
package mage.player.ai.kanna;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Everything HTTP and JSON about talking to Ollama, kept out of the player.
 * <p>
 * Retries once when a response contains no tool call at all. Measured rate of
 * that happening on the tuned local profile is roughly 1 in 6 -- the model
 * answers in prose instead. Without the retry (and the caller's fallback) a
 * failed response is indistinguishable from a deliberate "do nothing".
 *
 * @author Darrell Best
 */
public final class OllamaClient {

    private static final Logger logger = Logger.getLogger(OllamaClient.class);

    private final String baseUrl;
    private final String model;
    private int timeoutMs = 30_000;
    private int retryCount = 0;

    public OllamaClient(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getRetryCount() {
        return retryCount;
    }

    /**
     * @return the model's tool call, or null when it returned none even after a retry.
     */
    public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
        ToolCall first = attempt(prompt, tools);
        if (first != null) {
            return first;
        }
        retryCount++;
        logger.info("Kanna: no tool call in response, retrying once with an explicit instruction");
        return attempt(prompt + "\n\nYou MUST call the tool. Do not reply in prose.", tools);
    }

    private ToolCall attempt(String prompt, List<JsonObject> tools) throws IOException {
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonArray toolArray = new JsonArray();
        for (JsonObject tool : tools) {
            toolArray.add(tool);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.add("tools", toolArray);
        body.addProperty("stream", false);

        String response = postJson(baseUrl + "/api/chat", body.toString());
        return parseResponse(response);
    }

    static ToolCall parseResponse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject message = root.getAsJsonObject("message");
            if (message == null) {
                return null;
            }
            JsonArray calls = message.getAsJsonArray("tool_calls");
            if (calls == null || calls.size() == 0) {
                return null;
            }
            JsonObject function = calls.get(0).getAsJsonObject().getAsJsonObject("function");
            String name = function.get("name").getAsString();
            JsonElement rawArgs = function.get("arguments");
            JsonObject args;
            if (rawArgs != null && rawArgs.isJsonPrimitive()) {
                args = JsonParser.parseString(rawArgs.getAsString()).getAsJsonObject();
            } else if (rawArgs != null && rawArgs.isJsonObject()) {
                args = rawArgs.getAsJsonObject();
            } else {
                return null;
            }
            return new ToolCall(name, args);
        } catch (Exception e) {
            return null;
        }
    }

    public static JsonObject tool(String name, String description, JsonObject parameters) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", parameters);
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }

    public static JsonObject stringFieldSchema(String fieldName) {
        JsonObject properties = new JsonObject();
        properties.add(fieldName, typeObject("string"));
        JsonArray required = new JsonArray();
        required.add(fieldName);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    public static JsonObject pairArraySchema(String arrayName, String field1, String field2) {
        JsonObject itemProps = new JsonObject();
        itemProps.add(field1, typeObject("string"));
        itemProps.add(field2, typeObject("string"));
        JsonArray itemRequired = new JsonArray();
        itemRequired.add(field1);
        itemRequired.add(field2);
        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        item.add("properties", itemProps);
        item.add("required", itemRequired);

        JsonObject array = new JsonObject();
        array.addProperty("type", "array");
        array.add("items", item);

        JsonObject properties = new JsonObject();
        properties.add(arrayName, array);
        JsonArray required = new JsonArray();
        required.add(arrayName);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    private static JsonObject typeObject(String type) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        return object;
    }

    private String postJson(String url, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }

            int status = conn.getResponseCode();
            InputStream stream = status == 200 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            } finally {
                reader.close();
            }
            if (status != 200) {
                throw new IOException("Ollama returned HTTP " + status + ": " + sb);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q -DskipTests -pl Mage.Server.Plugins/Mage.Player.AI.Kanna install && mvn -q -pl Mage.Tests test -Dtest=OllamaClientTest`
Expected: PASS, 9 tests.

- [ ] **Step 6: Commit**

```bash
git add Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ToolCall.java \
        Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/OllamaClient.java \
        Mage.Tests/src/test/java/org/mage/test/kanna/OllamaClientTest.java
git commit -m "kanna: extract Ollama client with retry on missing tool call"
```

---

### Task 4: `RankedAction`, `ActionRanker`, `GameStateFormatter`

**Files:**
- Create: `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/RankedAction.java`
- Create: `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ActionRanker.java`
- Create: `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/GameStateFormatter.java`
- Test: `Mage.Tests/src/test/java/org/mage/test/kanna/ActionRankerTest.java`

**Interfaces:**
- Consumes: `ActionCatalog` (Task 2), `CreatureView`/`CombatEvaluator`/`AttackOutcome` (Task 1).
- Produces:
  - `RankedAction` — public final `String id`, `String label`, `String reason`, `int score`.
  - `ActionRanker` — `static List<RankedAction> rank(ActionCatalog catalog)`, `static List<RankedAction> shortlist(List<RankedAction> ranked, int limit)`, `static String render(List<RankedAction> shortlist, int totalCount)`.
  - `GameStateFormatter` — `static String describeCreatures(List<CreatureView> creatures)`, `static String attackOptions(List<CreatureView> attackers, List<CreatureView> defenderBlockers, int defenderLife)`.

**Ranking rules (deliberately simple — the spec says B-tier heuristics here, the model supplies judgment):** land drops score 100, removal-shaped labels 90, creatures 80, everything else 50, `PassAbility` 0. Ties break on insertion order, so ranking is deterministic.

- [ ] **Step 1: Write the failing test**

Create `Mage.Tests/src/test/java/org/mage/test/kanna/ActionRankerTest.java`:

```java
package org.mage.test.kanna;

import mage.abilities.common.PassAbility;
import mage.player.ai.kanna.ActionCatalog;
import mage.player.ai.kanna.ActionRanker;
import mage.player.ai.kanna.CreatureView;
import mage.player.ai.kanna.GameStateFormatter;
import mage.player.ai.kanna.RankedAction;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ActionRankerTest {

    private ActionCatalog catalogOf(String... labels) {
        ActionCatalog catalog = new ActionCatalog();
        for (String label : labels) {
            catalog.add(new PassAbility(), label);
        }
        return catalog;
    }

    @Test
    public void landDropOutranksCreatureWhichOutranksPass() {
        List<RankedAction> ranked = ActionRanker.rank(
                catalogOf("Pass", "Cast Grizzly Bears", "Play Mountain"));
        assertEquals("Play Mountain", ranked.get(0).label);
        assertEquals("Cast Grizzly Bears", ranked.get(1).label);
        assertEquals("Pass", ranked.get(2).label);
    }

    @Test
    public void removalOutranksCreature() {
        List<RankedAction> ranked = ActionRanker.rank(
                catalogOf("Cast Grizzly Bears", "Cast Lightning Bolt"));
        assertEquals("Cast Lightning Bolt", ranked.get(0).label);
    }

    @Test
    public void everyActionAppearsInTheRanking() {
        ActionCatalog catalog = catalogOf("A", "B", "C", "D", "E");
        assertEquals(5, ActionRanker.rank(catalog).size());
    }

    @Test
    public void rankingIsDeterministicAcrossRuns() {
        ActionCatalog catalog = catalogOf("Cast X", "Cast Y", "Cast Z");
        List<RankedAction> first = ActionRanker.rank(catalog);
        List<RankedAction> second = ActionRanker.rank(catalog);
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).id, second.get(i).id);
        }
    }

    @Test
    public void shortlistTruncatesToTheLimit() {
        List<RankedAction> ranked = ActionRanker.rank(catalogOf("A", "B", "C", "D", "E", "F"));
        assertEquals(3, ActionRanker.shortlist(ranked, 3).size());
    }

    @Test
    public void shortlistShorterThanLimitIsUnchanged() {
        List<RankedAction> ranked = ActionRanker.rank(catalogOf("A", "B"));
        assertEquals(2, ActionRanker.shortlist(ranked, 10).size());
    }

    @Test
    public void renderStatesHowManyOptionsAreHidden() {
        List<RankedAction> ranked = ActionRanker.rank(catalogOf("A", "B", "C", "D", "E", "F"));
        String rendered = ActionRanker.render(ActionRanker.shortlist(ranked, 2), 6);
        assertTrue("must disclose the hidden count", rendered.contains("4 more"));
        assertTrue("must name the escape hatch", rendered.contains("show_all_actions"));
    }

    @Test
    public void renderDoesNotClaimHiddenOptionsWhenNoneAreHidden() {
        List<RankedAction> ranked = ActionRanker.rank(catalogOf("A", "B"));
        String rendered = ActionRanker.render(ActionRanker.shortlist(ranked, 5), 2);
        assertTrue(rendered.contains("A"));
        assertTrue(rendered.contains("B"));
        org.junit.Assert.assertFalse(rendered.contains("more options"));
    }

    // ---- formatter ----

    @Test
    public void describeCreaturesIncludesKeywordsAndStats() {
        CreatureView angel = new CreatureView("c0", "Serra Angel", 4, 4,
                true, false, false, false, false, false, false, false);
        String text = GameStateFormatter.describeCreatures(Arrays.asList(angel));
        assertTrue(text.contains("Serra Angel"));
        assertTrue(text.contains("4/4"));
        assertTrue(text.contains("Flying"));
    }

    @Test
    public void describeCreaturesReportsNoneForEmptyBoard() {
        assertTrue(GameStateFormatter.describeCreatures(new java.util.ArrayList<CreatureView>())
                .toLowerCase().contains("none"));
    }

    @Test
    public void attackOptionsAnnotateEachAttackWithComputedConsequence() {
        CreatureView angel = new CreatureView("atk-0", "Serra Angel", 4, 4,
                true, false, false, false, false, false, false, false);
        CreatureView giant = new CreatureView("blk-0", "Hill Giant", 3, 3,
                false, false, false, false, false, false, false, false);
        String text = GameStateFormatter.attackOptions(Arrays.asList(angel), Arrays.asList(giant), 12);
        assertTrue("flier vs no reach is unblocked", text.contains("unblocked"));
        assertTrue(text.contains("atk-0"));
    }

    @Test
    public void attackOptionsFlagLethal() {
        CreatureView angel = new CreatureView("atk-0", "Serra Angel", 4, 4,
                true, false, false, false, false, false, false, false);
        String text = GameStateFormatter.attackOptions(Arrays.asList(angel),
                new java.util.ArrayList<CreatureView>(), 4);
        assertTrue("4 damage into 4 life is lethal", text.toLowerCase().contains("lethal"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl Mage.Tests test -Dtest=ActionRankerTest`
Expected: FAIL — the three classes do not exist.

- [ ] **Step 3: Write `RankedAction`**

Create `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/RankedAction.java`:

```java
package mage.player.ai.kanna;

/**
 * One entry in the shortlist shown to the model.
 *
 * @author Darrell Best
 */
public final class RankedAction {

    public final String id;
    public final String label;
    public final String reason;
    public final int score;

    public RankedAction(String id, String label, String reason, int score) {
        this.id = id;
        this.label = label;
        this.reason = reason;
        this.score = score;
    }
}
```

- [ ] **Step 4: Write `ActionRanker`**

Create `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ActionRanker.java`:

```java
package mage.player.ai.kanna;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Orders legal actions so the model sees the few that matter first.
 * <p>
 * Deliberately coarse. Its job is to anchor attention and cut prompt size, not
 * to decide the game -- that is the model's half of the work. The full list
 * always stays reachable via show_all_actions, and the hidden count is always
 * stated, so the shortlist can never quietly bury the winning line.
 *
 * @author Darrell Best
 */
public final class ActionRanker {

    private static final int SCORE_LAND = 100;
    private static final int SCORE_REMOVAL = 90;
    private static final int SCORE_CREATURE = 80;
    private static final int SCORE_OTHER = 50;
    private static final int SCORE_PASS = 0;

    private ActionRanker() {
    }

    public static List<RankedAction> rank(ActionCatalog catalog) {
        List<RankedAction> ranked = new ArrayList<RankedAction>();
        List<String> ids = catalog.ids();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            String label = catalog.labelFor(id);
            int score = score(label);
            ranked.add(new RankedAction(id, label, reason(label, score), score));
        }
        // stable sort: equal scores keep insertion order, so ranking is reproducible
        Collections.sort(ranked, new Comparator<RankedAction>() {
            @Override
            public int compare(RankedAction a, RankedAction b) {
                return Integer.compare(b.score, a.score);
            }
        });
        return ranked;
    }

    public static List<RankedAction> shortlist(List<RankedAction> ranked, int limit) {
        if (ranked.size() <= limit) {
            return new ArrayList<RankedAction>(ranked);
        }
        return new ArrayList<RankedAction>(ranked.subList(0, limit));
    }

    public static String render(List<RankedAction> shortlist, int totalCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shortlist.size(); i++) {
            RankedAction action = shortlist.get(i);
            sb.append(String.format("%2d. %-8s %s", i + 1, action.id, action.label));
            if (action.reason != null && !action.reason.isEmpty()) {
                sb.append("  (").append(action.reason).append(')');
            }
            sb.append(System.lineSeparator());
        }
        int hidden = totalCount - shortlist.size();
        if (hidden > 0) {
            sb.append("... ").append(hidden)
                    .append(" more options available: call show_all_actions")
                    .append(System.lineSeparator());
        }
        return sb.toString();
    }

    private static int score(String label) {
        String lower = label == null ? "" : label.toLowerCase();
        if (lower.startsWith("pass")) {
            return SCORE_PASS;
        }
        if (lower.contains("play ") && isLandName(lower)) {
            return SCORE_LAND;
        }
        if (lower.contains("bolt") || lower.contains("destroy") || lower.contains("damage")
                || lower.contains("slash") || lower.contains("shock") || lower.contains("kill")) {
            return SCORE_REMOVAL;
        }
        if (lower.startsWith("cast ")) {
            return SCORE_CREATURE;
        }
        return SCORE_OTHER;
    }

    private static boolean isLandName(String lower) {
        return lower.contains("mountain") || lower.contains("forest") || lower.contains("island")
                || lower.contains("swamp") || lower.contains("plains") || lower.contains("land");
    }

    private static String reason(String label, int score) {
        if (score == SCORE_LAND) {
            return "land drop, adds mana this turn";
        }
        if (score == SCORE_REMOVAL) {
            return "removal";
        }
        if (score == SCORE_CREATURE) {
            return "board presence";
        }
        if (score == SCORE_PASS) {
            return "take no action";
        }
        return "";
    }
}
```

- [ ] **Step 5: Write `GameStateFormatter`**

Create `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/GameStateFormatter.java`:

```java
package mage.player.ai.kanna;

import java.util.List;

/**
 * Renders board state and computed combat consequences into the text the model
 * reads. Every annotation here comes from CombatEvaluator -- the model is never
 * shown a claim the heuristics did not compute.
 *
 * @author Darrell Best
 */
public final class GameStateFormatter {

    private GameStateFormatter() {
    }

    public static String describeCreatures(List<CreatureView> creatures) {
        if (creatures.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (CreatureView creature : creatures) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(creature.describe());
        }
        return sb.toString();
    }

    /**
     * One line per possible attacker, annotated with what the attack actually does.
     */
    public static String attackOptions(List<CreatureView> attackers,
                                       List<CreatureView> defenderBlockers,
                                       int defenderLife) {
        StringBuilder sb = new StringBuilder();
        for (CreatureView attacker : attackers) {
            AttackOutcome outcome = CombatEvaluator.evaluateLikely(attacker, defenderBlockers);
            sb.append("- ").append(attacker.id).append(": ").append(attacker.describe())
                    .append("  -> ").append(outcome.summary);
            if (outcome.damageThrough > 0 && CombatEvaluator.isLethal(outcome.damageThrough, defenderLife)) {
                sb.append("  *** LETHAL ***");
            }
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q -DskipTests -pl Mage.Server.Plugins/Mage.Player.AI.Kanna install && mvn -q -pl Mage.Tests test -Dtest=ActionRankerTest`
Expected: PASS, 12 tests.

- [ ] **Step 7: Commit**

```bash
git add Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/RankedAction.java \
        Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ActionRanker.java \
        Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/GameStateFormatter.java \
        Mage.Tests/src/test/java/org/mage/test/kanna/ActionRankerTest.java
git commit -m "kanna: add action ranking and computed board rendering"
```

---

### Task 5: `Decision` and `KannaAgent`

The loop. Everything before this task was inputs; this is where the model is consulted and its answer validated.

**Files:**
- Create: `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/Decision.java`
- Create: `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/KannaAgent.java`
- Test: `Mage.Tests/src/test/java/org/mage/test/kanna/KannaAgentTest.java`

**Interfaces:**
- Consumes: `OllamaClient`, `ToolCall` (Task 3); `ActionCatalog` (Task 2); `ActionRanker`, `RankedAction` (Task 4).
- Produces:
  - `Decision` — public final `String chosenId`, `List<String[]> pairs`, `boolean fallback`; statics `Decision.of(String id)`, `Decision.ofPairs(List<String[]> pairs)`, `Decision.fallback()`.
  - `KannaAgent(OllamaClient client, int maxToolCalls)`; `Decision chooseAction(String prompt, ActionCatalog catalog, InspectionAnswerer answerer)`; `Decision choosePairs(String prompt, String toolName, String arrayField, String field1, String field2, PairValidator validator)`; nested `interface InspectionAnswerer { String answer(ToolCall call); }` returning null when the call is not an inspection tool; nested `interface PairValidator { boolean isValid(String a, String b); }`; `int getInvalidCount()`.

**Note on testability:** `KannaAgent` takes an `OllamaClient`. For tests, subclass it and override `call(...)` to return canned `ToolCall`s — so `OllamaClient.call` must NOT be `final` and the class must not be `final`. Adjust Task 3's `OllamaClient` accordingly when you get here: remove `final` from the class declaration and leave `call` non-final. Everything else in it stays as written.

- [ ] **Step 1: Write the failing test**

Create `Mage.Tests/src/test/java/org/mage/test/kanna/KannaAgentTest.java`:

```java
package org.mage.test.kanna;

import com.google.gson.JsonObject;
import mage.abilities.common.PassAbility;
import mage.player.ai.kanna.ActionCatalog;
import mage.player.ai.kanna.Decision;
import mage.player.ai.kanna.KannaAgent;
import mage.player.ai.kanna.OllamaClient;
import mage.player.ai.kanna.ToolCall;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KannaAgentTest {

    /** Returns the queued responses in order, then null forever. */
    private static class ScriptedClient extends OllamaClient {
        private final List<ToolCall> script;
        private int index = 0;
        int callCount = 0;

        ScriptedClient(List<ToolCall> script) {
            super("http://unused", "unused");
            this.script = script;
        }

        @Override
        public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
            callCount++;
            if (index < script.size()) {
                return script.get(index++);
            }
            return null;
        }
    }

    private ToolCall commit(String actionId) {
        JsonObject args = new JsonObject();
        args.addProperty("action_id", actionId);
        return new ToolCall("choose_action", args);
    }

    private ToolCall inspect() {
        JsonObject args = new JsonObject();
        args.addProperty("id", "act-0");
        return new ToolCall("get_card_text", args);
    }

    private ActionCatalog catalog() {
        ActionCatalog catalog = new ActionCatalog();
        catalog.add(new PassAbility(), "Pass");
        catalog.add(new PassAbility(), "Play Mountain");
        return catalog;
    }

    private KannaAgent.InspectionAnswerer answerer() {
        return new KannaAgent.InspectionAnswerer() {
            @Override
            public String answer(ToolCall call) {
                if ("get_card_text".equals(call.name)) {
                    return "Mountain: taps for R.";
                }
                return null;
            }
        };
    }

    @Test
    public void commitsImmediatelyWhenTheModelPicksAnAction() {
        ScriptedClient client = new ScriptedClient(Arrays.asList(commit("act-1")));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.chooseAction("prompt", catalog(), answerer());
        assertEquals("act-1", decision.chosenId);
        assertFalse(decision.fallback);
        assertEquals(1, client.callCount);
    }

    @Test
    public void inspectionToolsAreAnsweredAndTheLoopContinues() {
        ScriptedClient client = new ScriptedClient(Arrays.asList(inspect(), inspect(), commit("act-0")));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.chooseAction("prompt", catalog(), answerer());
        assertEquals("act-0", decision.chosenId);
        assertFalse(decision.fallback);
        assertEquals("two inspections then a commit", 3, client.callCount);
    }

    @Test
    public void capIsEnforcedAndFallsBack() {
        ScriptedClient client = new ScriptedClient(
                Arrays.asList(inspect(), inspect(), inspect(), inspect(), inspect(), inspect()));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.chooseAction("prompt", catalog(), answerer());
        assertTrue("never committed within the cap", decision.fallback);
        assertTrue("must not exceed the cap", client.callCount <= 4);
    }

    @Test
    public void unknownActionIdFallsBackAndIsCounted() {
        ScriptedClient client = new ScriptedClient(Arrays.asList(commit("act-999")));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.chooseAction("prompt", catalog(), answerer());
        assertTrue(decision.fallback);
        assertEquals(1, agent.getInvalidCount());
    }

    @Test
    public void noToolCallAtAllFallsBackAndIsCounted() {
        ScriptedClient client = new ScriptedClient(new ArrayList<ToolCall>());
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.chooseAction("prompt", catalog(), answerer());
        assertTrue(decision.fallback);
        assertEquals(1, agent.getInvalidCount());
    }

    @Test
    public void transportFailureFallsBackRatherThanPropagating() {
        OllamaClient exploding = new OllamaClient("http://unused", "unused") {
            @Override
            public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
                throw new IOException("connection refused");
            }
        };
        KannaAgent agent = new KannaAgent(exploding, 4);
        Decision decision = agent.chooseAction("prompt", catalog(), answerer());
        assertTrue(decision.fallback);
    }

    @Test
    public void pairDecisionKeepsValidPairsAndDropsInvalidOnes() {
        JsonObject pair1 = new JsonObject();
        pair1.addProperty("attacker_id", "atk-0");
        pair1.addProperty("defender_id", "def-0");
        JsonObject pair2 = new JsonObject();
        pair2.addProperty("attacker_id", "atk-99");
        pair2.addProperty("defender_id", "def-0");
        com.google.gson.JsonArray attacks = new com.google.gson.JsonArray();
        attacks.add(pair1);
        attacks.add(pair2);
        JsonObject args = new JsonObject();
        args.add("attacks", attacks);

        ScriptedClient client = new ScriptedClient(
                Arrays.asList(new ToolCall("declare_attackers", args)));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.choosePairs("prompt", "declare_attackers", "attacks",
                "attacker_id", "defender_id", new KannaAgent.PairValidator() {
                    @Override
                    public boolean isValid(String a, String b) {
                        return "atk-0".equals(a);
                    }
                });
        assertFalse(decision.fallback);
        assertEquals(1, decision.pairs.size());
        assertEquals("atk-0", decision.pairs.get(0)[0]);
        assertEquals("one hallucinated pair dropped", 1, agent.getInvalidCount());
    }

    @Test
    public void emptyPairArrayIsAValidDecisionNotAFallback() {
        JsonObject args = new JsonObject();
        args.add("attacks", new com.google.gson.JsonArray());
        ScriptedClient client = new ScriptedClient(
                Arrays.asList(new ToolCall("declare_attackers", args)));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.choosePairs("prompt", "declare_attackers", "attacks",
                "attacker_id", "defender_id", new KannaAgent.PairValidator() {
                    @Override
                    public boolean isValid(String a, String b) {
                        return true;
                    }
                });
        assertFalse("declining to attack is a real decision, not a failure", decision.fallback);
        assertTrue(decision.pairs.isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl Mage.Tests test -Dtest=KannaAgentTest`
Expected: FAIL — `Decision` / `KannaAgent` do not exist.

- [ ] **Step 3: Make `OllamaClient` subclassable**

In `OllamaClient.java`, change `public final class OllamaClient` to `public class OllamaClient`. No other change.

- [ ] **Step 4: Write `Decision`**

Create `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/Decision.java`:

```java
package mage.player.ai.kanna;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the agent concluded. `fallback` means the model did not produce a usable
 * answer and the heuristic layer should decide instead -- which is different from
 * the model deliberately choosing to do nothing.
 *
 * @author Darrell Best
 */
public final class Decision {

    public final String chosenId;
    public final List<String[]> pairs;
    public final boolean fallback;

    private Decision(String chosenId, List<String[]> pairs, boolean fallback) {
        this.chosenId = chosenId;
        this.pairs = pairs == null
                ? Collections.<String[]>emptyList()
                : Collections.unmodifiableList(new ArrayList<String[]>(pairs));
        this.fallback = fallback;
    }

    public static Decision of(String chosenId) {
        return new Decision(chosenId, null, false);
    }

    public static Decision ofPairs(List<String[]> pairs) {
        return new Decision(null, pairs, false);
    }

    public static Decision fallback() {
        return new Decision(null, null, true);
    }
}
```

- [ ] **Step 5: Write `KannaAgent`**

Create `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/KannaAgent.java`:

```java
package mage.player.ai.kanna;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The decision loop. The model may call read-only inspection tools to look
 * deeper, then must commit exactly once. A hard cap stops a decision spiralling.
 * <p>
 * When the model fails -- no tool call, an id that does not exist, transport
 * error, or the cap -- the agent returns Decision.fallback() and the caller's
 * heuristics decide. It never returns "do nothing" as a way of failing, because
 * that is indistinguishable from deciding to do nothing.
 *
 * @author Darrell Best
 */
public final class KannaAgent {

    private static final Logger logger = Logger.getLogger(KannaAgent.class);

    /** Answers a read-only inspection call, or returns null if this is not one. */
    public interface InspectionAnswerer {
        String answer(ToolCall call);
    }

    /** Decides whether a committed (a, b) pair is legal in the real game. */
    public interface PairValidator {
        boolean isValid(String first, String second);
    }

    private final OllamaClient client;
    private final int maxToolCalls;
    private int invalidCount = 0;

    public KannaAgent(OllamaClient client, int maxToolCalls) {
        this.client = client;
        this.maxToolCalls = maxToolCalls;
    }

    public int getInvalidCount() {
        return invalidCount;
    }

    public Decision chooseAction(String prompt, ActionCatalog catalog, InspectionAnswerer answerer) {
        List<JsonObject> tools = new ArrayList<JsonObject>();
        tools.add(OllamaClient.tool("choose_action",
                "Commit to exactly one action, by its short id.",
                OllamaClient.stringFieldSchema("action_id")));
        tools.add(OllamaClient.tool("get_card_text",
                "Read the full text of a card by its short id.",
                OllamaClient.stringFieldSchema("id")));
        tools.add(OllamaClient.tool("show_all_actions",
                "List every legal action, not just the shortlist.",
                OllamaClient.stringFieldSchema("unused")));

        StringBuilder conversation = new StringBuilder(prompt);
        for (int i = 0; i < maxToolCalls; i++) {
            ToolCall call;
            try {
                call = client.call(conversation.toString(), tools);
            } catch (Exception e) {
                logger.warn("Kanna: LLM transport failure, deferring to heuristics - " + e);
                return Decision.fallback();
            }
            if (call == null) {
                logger.warn("Kanna: no tool call returned, deferring to heuristics");
                invalidCount++;
                return Decision.fallback();
            }
            if ("choose_action".equals(call.name)) {
                String id = optString(call.arguments, "action_id");
                if (catalog.resolve(id) == null) {
                    logger.warn("Kanna: model chose unknown action id '" + id + "', deferring to heuristics");
                    invalidCount++;
                    return Decision.fallback();
                }
                return Decision.of(id);
            }
            String answer = answerer.answer(call);
            if (answer == null) {
                logger.warn("Kanna: unknown tool '" + call.name + "', deferring to heuristics");
                invalidCount++;
                return Decision.fallback();
            }
            conversation.append(System.lineSeparator())
                    .append("Result of ").append(call.name).append(": ").append(answer);
        }
        logger.warn("Kanna: hit the " + maxToolCalls + "-call cap without committing, deferring to heuristics");
        return Decision.fallback();
    }

    public Decision choosePairs(String prompt, String toolName, String arrayField,
                                String field1, String field2, PairValidator validator) {
        List<JsonObject> tools = new ArrayList<JsonObject>();
        tools.add(OllamaClient.tool(toolName,
                "Commit to the chosen assignments, using only the short ids listed.",
                OllamaClient.pairArraySchema(arrayField, field1, field2)));

        ToolCall call;
        try {
            call = client.call(prompt, tools);
        } catch (Exception e) {
            logger.warn("Kanna: LLM transport failure, deferring to heuristics - " + e);
            return Decision.fallback();
        }
        if (call == null) {
            logger.warn("Kanna: no tool call returned for " + toolName + ", deferring to heuristics");
            invalidCount++;
            return Decision.fallback();
        }
        JsonArray array = call.arguments.getAsJsonArray(arrayField);
        if (array == null) {
            logger.warn("Kanna: tool call had no '" + arrayField + "' array, deferring to heuristics");
            invalidCount++;
            return Decision.fallback();
        }
        List<String[]> pairs = new ArrayList<String[]>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                invalidCount++;
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String a = optString(object, field1);
            String b = optString(object, field2);
            if (a == null || b == null || !validator.isValid(a, b)) {
                logger.warn("Kanna: dropping invalid pair " + a + " -> " + b);
                invalidCount++;
                continue;
            }
            pairs.add(new String[]{a, b});
        }
        return Decision.ofPairs(pairs);
    }

    private static String optString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        return object.get(field).getAsString();
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q -DskipTests -pl Mage.Server.Plugins/Mage.Player.AI.Kanna install && mvn -q -pl Mage.Tests test -Dtest=KannaAgentTest`
Expected: PASS, 8 tests.

- [ ] **Step 7: Commit**

```bash
git add Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/Decision.java \
        Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/KannaAgent.java \
        Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/OllamaClient.java \
        Mage.Tests/src/test/java/org/mage/test/kanna/KannaAgentTest.java
git commit -m "kanna: add agent decision loop with inspection tools and cap"
```

---

### Task 6: Rewrite `ComputerPlayerKanna` on `ComputerPlayer`

The pivot itself. After this task there is no MCTS anywhere in Kanna.

**Files:**
- Modify: `Mage.Server.Plugins/Mage.Player.AI.Kanna/pom.xml` (dependency swap)
- Rewrite: `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ComputerPlayerKanna.java`
- Test: `Mage.Tests/src/test/java/org/mage/test/kanna/KannaSmokeTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1-5.
- Produces: `ComputerPlayerKanna extends ComputerPlayer` with `ComputerPlayerKanna(String name, RangeOfInfluence range, int skill)` retained (the `skill` argument is accepted and ignored, so `PlayerFactory` needs no change), `setModel(String)`, `setOllamaUrl(String)`, `setBenchMetrics(DecisionMetrics)`, and the nested `DecisionMetrics` interface unchanged.

- [ ] **Step 1: Swap the module dependency**

In `Mage.Server.Plugins/Mage.Player.AI.Kanna/pom.xml`, replace the `mage-player-ai-mcts` dependency block with `mage-player-ai`, and update the existing fork comment above it:

```xml
        <!-- DARRELLBEST-FORK (keep on merge/rebase from upstream): Kanna is a fully
             agentic LLM player and no longer uses any search, so it depends on the
             base heuristic player rather than mage-player-ai-mcts. -->
        <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>mage-player-ai</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Step 2: Write the failing smoke test**

Create `Mage.Tests/src/test/java/org/mage/test/kanna/KannaSmokeTest.java`:

```java
package org.mage.test.kanna;

import mage.constants.RangeOfInfluence;
import mage.player.ai.ComputerPlayer;
import mage.player.ai.kanna.ComputerPlayerKanna;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * DARRELLBEST-FORK: guards the pivot away from search. If Kanna ever regains an
 * MCTS/minimax ancestor these fail, which is the point.
 */
public class KannaSmokeTest {

    private ComputerPlayerKanna kanna() {
        return new ComputerPlayerKanna("Kanna", RangeOfInfluence.ONE, 6);
    }

    @Test
    public void extendsBaseComputerPlayer() {
        assertTrue(kanna() instanceof ComputerPlayer);
    }

    @Test
    public void hasNoSearchBasedAncestor() {
        Class<?> type = kanna().getClass();
        while (type != null) {
            String name = type.getName();
            assertFalse("Kanna must not inherit from a search player: " + name,
                    name.contains("ComputerPlayerMCTS") || name.contains("ComputerPlayer6")
                            || name.contains("ComputerPlayer7"));
            type = type.getSuperclass();
        }
    }

    @Test
    public void overridesPriorityRatherThanInheritingTheNoOp() throws Exception {
        // ComputerPlayer.priority() is "minimum implementation for do nothing" -- it just
        // passes. Inheriting it means passing every window forever, silently.
        assertEquals(ComputerPlayerKanna.class,
                kanna().getClass().getMethod("priority", mage.game.Game.class).getDeclaringClass());
    }

    @Test
    public void copyPreservesConfiguration() {
        ComputerPlayerKanna original = kanna();
        original.setModel("some-model:latest");
        ComputerPlayerKanna copy = original.copy();
        assertEquals("some-model:latest", copy.getModel());
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -q -pl Mage.Tests test -Dtest=KannaSmokeTest`
Expected: FAIL — Kanna still extends `ComputerPlayerMCTS`, and `getModel()` does not exist.

- [ ] **Step 4: Rewrite `ComputerPlayerKanna`**

Replace the entire contents of `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ComputerPlayerKanna.java` with:

```java
package mage.player.ai.kanna;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.common.PassAbility;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.player.ai.ComputerPlayer;
import mage.players.Player;
import mage.target.Target;
import org.apache.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kanna: a fully agentic, LLM-driven Magic player.
 * <p>
 * Heuristics compute and the model judges. Before any decision reaches the model,
 * CombatEvaluator and ActionRanker enumerate and annotate every legal option with
 * its exact consequences; the model then picks one, using read-only inspection
 * tools if it wants to look deeper. The same heuristics stand in when the model
 * fails, so a failure is a reasonable move plus a metric, never a silent pass.
 * <p>
 * Extends ComputerPlayer, NOT ComputerPlayerMCTS/6/7: there is no search here.
 * Note that ComputerPlayer.priority() is a no-op that just passes -- overriding it
 * is mandatory, and forgetting to is silent rather than loud.
 *
 * @author Darrell Best
 */
public class ComputerPlayerKanna extends ComputerPlayer {

    private static final Logger logger = Logger.getLogger(ComputerPlayerKanna.class);

    private static final int MAX_TOOL_CALLS = 4;
    private static final int SHORTLIST_SIZE = 8;
    private static final int MAX_HISTORY_ENTRIES = 5;

    /**
     * Instrumentation callback the benchmark harness supplies. Declared here rather
     * than imported from Mage.Bench because Mage.Bench depends on this module, not
     * the other way round -- the reverse would be a Maven cycle. No-ops when unset.
     */
    public interface DecisionMetrics {
        void recordLlmCall(long latencyMs);

        void recordInvalidToolCall();
    }

    private String ollamaUrl = "http://localhost:11434";
    private String ollamaModel = "xmage-ai-qwen3.6:latest";
    private DecisionMetrics metrics;
    private final Deque<String> combatHistory = new ArrayDeque<String>();

    public ComputerPlayerKanna(String name, RangeOfInfluence range, int skill) {
        // skill is accepted and ignored: it meant search depth/think time, and there
        // is no search any more. Kept so PlayerFactory and the server need no change.
        super(name, range);
    }

    public ComputerPlayerKanna(final ComputerPlayerKanna player) {
        super(player);
        this.ollamaUrl = player.ollamaUrl;
        this.ollamaModel = player.ollamaModel;
        this.metrics = player.metrics;
        this.combatHistory.addAll(player.combatHistory);
    }

    @Override
    public ComputerPlayerKanna copy() {
        return new ComputerPlayerKanna(this);
    }

    public void setOllamaUrl(String ollamaUrl) {
        this.ollamaUrl = ollamaUrl;
    }

    public void setModel(String model) {
        this.ollamaModel = model;
    }

    public String getModel() {
        return ollamaModel;
    }

    public void setBenchMetrics(DecisionMetrics metrics) {
        this.metrics = metrics;
    }

    private KannaAgent newAgent() {
        return new KannaAgent(new OllamaClient(ollamaUrl, ollamaModel), MAX_TOOL_CALLS);
    }

    private void reportInvalid(KannaAgent agent) {
        if (metrics == null) {
            return;
        }
        for (int i = 0; i < agent.getInvalidCount(); i++) {
            metrics.recordInvalidToolCall();
        }
    }

    // ------------------------------------------------------------------ priority

    @Override
    public boolean priority(Game game) {
        List<ActivatedAbility> playable = getPlayable(game, true);

        // Trivial-decision bypass. Most priority windows in Magic offer nothing but
        // Pass; sending each to the model would cost a round trip per window and make
        // the player unusable. This is load-bearing, not an optimisation.
        if (playable.isEmpty() || onlyPass(playable)) {
            pass(game);
            return false;
        }

        ActionCatalog catalog = new ActionCatalog();
        for (ActivatedAbility ability : playable) {
            catalog.add(ability, ability.toString());
        }
        PassAbility passAbility = new PassAbility();
        catalog.add(passAbility, "Pass");

        List<RankedAction> ranked = ActionRanker.rank(catalog);
        String prompt = buildPriorityPrompt(game, ranked, catalog.size());

        KannaAgent agent = newAgent();
        long start = System.nanoTime();
        Decision decision = agent.chooseAction(prompt, catalog, new KannaAgent.InspectionAnswerer() {
            @Override
            public String answer(ToolCall call) {
                if ("show_all_actions".equals(call.name)) {
                    return ActionRanker.render(ActionRanker.rank(catalog), catalog.size());
                }
                if ("get_card_text".equals(call.name)) {
                    String id = call.arguments.has("id") ? call.arguments.get("id").getAsString() : null;
                    String label = catalog.labelFor(id);
                    return label == null ? "No such id." : label;
                }
                return null;
            }
        });
        recordLatency(start);
        reportInvalid(agent);

        if (decision.fallback) {
            // Heuristics already ranked everything to build the prompt, so the fallback
            // is free and strictly better than passing: take the top-ranked action.
            RankedAction best = ranked.isEmpty() ? null : ranked.get(0);
            ActivatedAbility chosen = best == null ? null : catalog.resolve(best.id);
            if (chosen == null || chosen instanceof PassAbility) {
                pass(game);
                return false;
            }
            logger.info("Kanna: heuristic fallback plays " + best.label);
            activateAbility(chosen, game);
            return true;
        }

        ActivatedAbility chosen = catalog.resolve(decision.chosenId);
        if (chosen instanceof PassAbility) {
            pass(game);
            return false;
        }
        logger.info("Kanna plays " + catalog.labelFor(decision.chosenId) + " via " + ollamaModel);
        activateAbility(chosen, game);
        return true;
    }

    private static boolean onlyPass(List<ActivatedAbility> playable) {
        for (ActivatedAbility ability : playable) {
            if (!(ability instanceof PassAbility)) {
                return false;
            }
        }
        return true;
    }

    private String buildPriorityPrompt(Game game, List<RankedAction> ranked, int total) {
        Player me = game.getPlayer(playerId);
        StringBuilder sb = new StringBuilder();
        sb.append("You are Kanna, playing Magic: The Gathering as ").append(getName())
                .append(" (").append(me == null ? 0 : me.getLife()).append(" life).")
                .append(System.lineSeparator());
        sb.append("Turn ").append(game.getTurnNum()).append(", ").append(game.getStep().getType())
                .append('.').append(System.lineSeparator()).append(System.lineSeparator());
        sb.append("Your creatures: ").append(GameStateFormatter.describeCreatures(myCreatures(game)))
                .append(System.lineSeparator());
        for (UUID opponentId : game.getOpponents(playerId, true)) {
            Player opponent = game.getPlayer(opponentId);
            if (opponent == null) {
                continue;
            }
            sb.append(opponent.getName()).append(" (").append(opponent.getLife()).append(" life) creatures: ")
                    .append(GameStateFormatter.describeCreatures(creaturesOf(opponentId, game)))
                    .append(System.lineSeparator());
        }
        sb.append(historyBlock());
        sb.append(System.lineSeparator()).append("Your options:").append(System.lineSeparator());
        sb.append(ActionRanker.render(ActionRanker.shortlist(ranked, SHORTLIST_SIZE), total));
        sb.append(System.lineSeparator())
                .append("Call choose_action with exactly one id from the list above.");
        return sb.toString();
    }

    private List<CreatureView> myCreatures(Game game) {
        return creaturesOf(playerId, game);
    }

    private static List<CreatureView> creaturesOf(UUID controllerId, Game game) {
        List<CreatureView> views = new ArrayList<CreatureView>();
        int index = 0;
        for (Permanent permanent : game.getBattlefield().getAllActivePermanents(controllerId)) {
            if (permanent.isCreature(game)) {
                views.add(CreatureView.from("c-" + index++, permanent, game));
            }
        }
        return views;
    }

    // ------------------------------------------------------------------ targeting

    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        List<UUID> possible = new ArrayList<UUID>(target.possibleTargets(getId(), source, game));
        if (possible.size() <= 1) {
            // no real choice to make -- do not spend a model round trip on it
            return super.chooseTarget(outcome, target, source, game);
        }

        Map<String, UUID> byId = new HashMap<String, UUID>();
        ActionCatalog catalog = new ActionCatalog();
        StringBuilder options = new StringBuilder();
        int index = 0;
        for (UUID candidate : possible) {
            String id = "tgt-" + index++;
            byId.put(id, candidate);
            options.append("- ").append(id).append(": ").append(describeTarget(candidate, game))
                    .append(System.lineSeparator());
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are Kanna. Choose a target for: ")
                .append(source == null ? "an effect" : source.toString())
                .append(System.lineSeparator())
                .append("Outcome is ").append(outcome).append('.')
                .append(System.lineSeparator()).append(System.lineSeparator())
                .append("Possible targets:").append(System.lineSeparator()).append(options)
                .append(System.lineSeparator())
                .append("Call choose_action with exactly one tgt- id from the list above.");

        for (Map.Entry<String, UUID> entry : byId.entrySet()) {
            catalog.add(new PassAbility(), entry.getKey());
        }

        KannaAgent agent = newAgent();
        long start = System.nanoTime();
        Decision decision = agent.chooseAction(prompt.toString(), catalog,
                new KannaAgent.InspectionAnswerer() {
                    @Override
                    public String answer(ToolCall call) {
                        return "show_all_actions".equals(call.name) ? options.toString() : null;
                    }
                });
        recordLatency(start);
        reportInvalid(agent);

        if (decision.fallback) {
            return super.chooseTarget(outcome, target, source, game);
        }
        String chosenLabel = catalog.labelFor(decision.chosenId);
        UUID chosen = byId.get(chosenLabel);
        if (chosen == null) {
            return super.chooseTarget(outcome, target, source, game);
        }
        target.addTarget(chosen, source, game);
        logger.info("Kanna targets " + describeTarget(chosen, game));
        return true;
    }

    private static String describeTarget(UUID id, Game game) {
        Permanent permanent = game.getPermanent(id);
        if (permanent != null) {
            return permanent.getName() + " (" + permanent.getPower().getValue()
                    + "/" + permanent.getToughness().getValue() + ")";
        }
        Player player = game.getPlayer(id);
        if (player != null) {
            return "player " + player.getName() + " (" + player.getLife() + " life)";
        }
        return id.toString();
    }

    // ------------------------------------------------------------------ attacks

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_ATTACKERS_STEP_PRE, null, null, attackingPlayerId));
        if (game.replaceEvent(GameEvent.getEvent(
                GameEvent.EventType.DECLARING_ATTACKERS, attackingPlayerId, attackingPlayerId))) {
            return;
        }
        try {
            declareAttacksAgentically(game, attackingPlayerId);
        } catch (Throwable e) {
            logger.warn("Kanna: attack decision failed, deferring to heuristics - " + e, e);
            super.selectAttackers(game, attackingPlayerId);
        }
    }

    private void declareAttacksAgentically(Game game, UUID attackingPlayerId) {
        final Map<String, Permanent> attackers = new HashMap<String, Permanent>();
        final Map<String, UUID> defenders = new HashMap<String, UUID>();
        List<CreatureView> attackerViews = new ArrayList<CreatureView>();
        StringBuilder defenderText = new StringBuilder();

        for (UUID defenderId : game.getOpponents(playerId, true)) {
            Player defender = game.getPlayer(defenderId);
            if (defender == null || !defender.isInGame()) {
                continue;
            }
            String defId = "def-" + defenders.size();
            defenders.put(defId, defenderId);
            List<CreatureView> blockers = untappedCreaturesOf(defenderId, game);
            defenderText.append("- ").append(defId).append(": ").append(defender.getName())
                    .append(" (").append(defender.getLife()).append(" life), possible blockers: ")
                    .append(GameStateFormatter.describeCreatures(blockers))
                    .append(System.lineSeparator());
            for (Permanent attacker : getAvailableAttackers(defenderId, game)) {
                if (!attackers.containsValue(attacker)) {
                    String atkId = "atk-" + attackers.size();
                    attackers.put(atkId, attacker);
                    attackerViews.add(CreatureView.from(atkId, attacker, game));
                }
            }
        }

        if (attackers.isEmpty() || defenders.isEmpty()) {
            return;
        }

        UUID firstDefenderId = defenders.values().iterator().next();
        Player firstDefender = game.getPlayer(firstDefenderId);
        int defenderLife = firstDefender == null ? 20 : firstDefender.getLife();
        String optionText = GameStateFormatter.attackOptions(attackerViews,
                untappedCreaturesOf(firstDefenderId, game), defenderLife);

        StringBuilder prompt = new StringBuilder();
        Player me = game.getPlayer(playerId);
        prompt.append("You are Kanna, playing as ").append(getName())
                .append(" (").append(me == null ? 0 : me.getLife()).append(" life). It is your combat step.")
                .append(System.lineSeparator()).append(historyBlock())
                .append(System.lineSeparator()).append("Your possible attacks, with computed outcomes:")
                .append(System.lineSeparator()).append(optionText)
                .append(System.lineSeparator()).append("Defenders:").append(System.lineSeparator())
                .append(defenderText).append(System.lineSeparator())
                .append("Call declare_attackers using only the ids above. An empty list means attack with nobody.");

        KannaAgent agent = newAgent();
        long start = System.nanoTime();
        Decision decision = agent.choosePairs(prompt.toString(), "declare_attackers", "attacks",
                "attacker_id", "defender_id", new KannaAgent.PairValidator() {
                    @Override
                    public boolean isValid(String attackerId, String defenderId) {
                        return attackers.containsKey(attackerId) && defenders.containsKey(defenderId);
                    }
                });
        recordLatency(start);
        reportInvalid(agent);

        if (decision.fallback) {
            logger.info("Kanna: deferring attacks to heuristics");
            super.selectAttackers(game, attackingPlayerId);
            return;
        }

        Player attackingPlayer = game.getPlayer(attackingPlayerId);
        List<String> summary = new ArrayList<String>();
        List<UUID> declared = new ArrayList<UUID>();
        for (String[] pair : decision.pairs) {
            Permanent attacker = attackers.get(pair[0]);
            UUID defenderId = defenders.get(pair[1]);
            if (attacker == null || defenderId == null || declared.contains(attacker.getId())) {
                continue;
            }
            attackingPlayer.declareAttacker(attacker.getId(), defenderId, game, false);
            declared.add(attacker.getId());
            summary.add(attacker.getName());
        }
        logger.info("Kanna attacks with " + declared.size() + " creature(s) via " + ollamaModel
                + (summary.isEmpty() ? "" : ": " + join(summary)));
        recordHistory(game, "attack", summary.isEmpty() ? "no attacks" : join(summary));
    }

    // ------------------------------------------------------------------ blocks

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_BLOCKERS_STEP_PRE, null, null, defendingPlayerId));
        if (game.replaceEvent(GameEvent.getEvent(
                GameEvent.EventType.DECLARING_BLOCKERS, defendingPlayerId, defendingPlayerId))) {
            return;
        }
        try {
            declareBlocksAgentically(source, game, defendingPlayerId);
        } catch (Throwable e) {
            logger.warn("Kanna: block decision failed, deferring to heuristics - " + e, e);
            super.selectBlockers(source, game, defendingPlayerId);
        }
    }

    private void declareBlocksAgentically(Ability source, Game game, UUID defendingPlayerId) {
        final Map<String, Permanent> attackers = new HashMap<String, Permanent>();
        final Map<String, Permanent> blockers = new HashMap<String, Permanent>();
        List<CreatureView> attackerViews = new ArrayList<CreatureView>();
        List<CreatureView> blockerViews = new ArrayList<CreatureView>();

        for (UUID attackerId : game.getCombat().getAttackers()) {
            if (!defendingPlayerId.equals(game.getCombat().getDefendingPlayerId(attackerId, game))) {
                continue;
            }
            Permanent attacker = game.getPermanent(attackerId);
            if (attacker == null) {
                continue;
            }
            String id = "atk-" + attackers.size();
            attackers.put(id, attacker);
            attackerViews.add(CreatureView.from(id, attacker, game));
        }
        if (attackers.isEmpty()) {
            return;
        }

        for (Permanent blocker : getAvailableBlockers(game)) {
            boolean canBlockSomething = false;
            for (Permanent attacker : attackers.values()) {
                if (blocker.canBlock(attacker.getId(), game)) {
                    canBlockSomething = true;
                    break;
                }
            }
            if (canBlockSomething) {
                String id = "blk-" + blockers.size();
                blockers.put(id, blocker);
                blockerViews.add(CreatureView.from(id, blocker, game));
            }
        }
        if (blockers.isEmpty()) {
            recordHistory(game, "block", "no legal blockers");
            return;
        }

        Player me = game.getPlayer(playerId);
        int myLife = me == null ? 20 : me.getLife();
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are Kanna, playing as ").append(getName())
                .append(" (").append(myLife).append(" life). You are being attacked.")
                .append(System.lineSeparator()).append(historyBlock())
                .append(System.lineSeparator()).append("Attacking you:").append(System.lineSeparator())
                .append(GameStateFormatter.attackOptions(attackerViews, blockerViews, myLife))
                .append(System.lineSeparator()).append("Your available blockers: ")
                .append(GameStateFormatter.describeCreatures(blockerViews))
                .append(System.lineSeparator()).append(System.lineSeparator())
                .append("Call declare_blockers using only the ids above. An empty list means block with nobody.");

        KannaAgent agent = newAgent();
        long start = System.nanoTime();
        Decision decision = agent.choosePairs(prompt.toString(), "declare_blockers", "blocks",
                "blocker_id", "attacker_id", new KannaAgent.PairValidator() {
                    @Override
                    public boolean isValid(String blockerId, String attackerId) {
                        Permanent blocker = blockers.get(blockerId);
                        Permanent attacker = attackers.get(attackerId);
                        return blocker != null && attacker != null;
                    }
                });
        recordLatency(start);
        reportInvalid(agent);

        if (decision.fallback) {
            logger.info("Kanna: deferring blocks to heuristics");
            super.selectBlockers(source, game, defendingPlayerId);
            return;
        }

        Player defendingPlayer = game.getPlayer(defendingPlayerId);
        List<UUID> used = new ArrayList<UUID>();
        List<String> summary = new ArrayList<String>();
        for (String[] pair : decision.pairs) {
            Permanent blocker = blockers.get(pair[0]);
            Permanent attacker = attackers.get(pair[1]);
            if (blocker == null || attacker == null || used.contains(blocker.getId())
                    || !blocker.canBlock(attacker.getId(), game)) {
                continue;
            }
            defendingPlayer.declareBlocker(defendingPlayerId, blocker.getId(), attacker.getId(), game, false);
            used.add(blocker.getId());
            summary.add(blocker.getName() + " blocks " + attacker.getName());
        }
        logger.info("Kanna blocks with " + used.size() + " creature(s) via " + ollamaModel);
        recordHistory(game, "block", summary.isEmpty() ? "no blocks" : join(summary));
    }

    // ------------------------------------------------------------------ helpers

    private static List<CreatureView> untappedCreaturesOf(UUID controllerId, Game game) {
        List<CreatureView> views = new ArrayList<CreatureView>();
        int index = 0;
        for (Permanent permanent : game.getBattlefield().getAllActivePermanents(controllerId)) {
            if (permanent.isCreature(game) && !permanent.isTapped()) {
                views.add(CreatureView.from("blk-" + index++, permanent, game));
            }
        }
        return views;
    }

    private void recordLatency(long startNanos) {
        if (metrics != null) {
            metrics.recordLlmCall((System.nanoTime() - startNanos) / 1_000_000L);
        }
    }

    private String historyBlock() {
        if (combatHistory.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Your recent decisions:").append(System.lineSeparator());
        for (String entry : combatHistory) {
            sb.append("  ").append(entry).append(System.lineSeparator());
        }
        return sb.toString();
    }

    private void recordHistory(Game game, String kind, String summary) {
        combatHistory.addLast("T" + game.getTurnNum() + " (" + kind + "): " + summary);
        while (combatHistory.size() > MAX_HISTORY_ENTRIES) {
            combatHistory.removeFirst();
        }
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(item);
        }
        return sb.toString();
    }
}
```

- [ ] **Step 5: Run the smoke test to verify it passes**

Run: `mvn -q -DskipTests -pl Mage.Server.Plugins/Mage.Player.AI.Kanna install && mvn -q -pl Mage.Tests test -Dtest=KannaSmokeTest`
Expected: PASS, 4 tests.

- [ ] **Step 6: Run every Kanna test together**

Run: `mvn -q -pl Mage.Tests test -Dtest='CombatEvaluatorTest+ActionCatalogTest+OllamaClientTest+ActionRankerTest+KannaAgentTest+KannaSmokeTest'`
Expected: PASS, 60 tests total.

- [ ] **Step 7: Confirm Mage.Bench still compiles against the new Kanna**

Run: `mvn -q -pl Mage.Bench compile`
Expected: BUILD SUCCESS. `BenchGame` calls `setBenchMetrics`, `setModel`, and `setOllamaUrl`, all of which are retained. If it fails, the cause is a signature you changed — restore it rather than editing `Mage.Bench`, which is out of scope.

- [ ] **Step 8: Commit**

```bash
git add Mage.Server.Plugins/Mage.Player.AI.Kanna/pom.xml \
        Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ComputerPlayerKanna.java \
        Mage.Tests/src/test/java/org/mage/test/kanna/KannaSmokeTest.java
git commit -m "kanna: rewrite as fully agentic player, drop MCTS entirely"
```

---

### Task 7: `ManaPlanner`

**Files:**
- Create: `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ManaPlanner.java`
- Modify: `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ComputerPlayerKanna.java` (add `playMana` override)
- Test: `Mage.Tests/src/test/java/org/mage/test/kanna/ManaPlannerTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `ManaPlanner` — `static int scorePayment(List<String> sourceColors, List<String> colorsNeededInHand)` (higher is better), `static List<String> preferredOrder(List<String> availableColors, List<String> colorsNeededInHand)`.

**Scope note:** the engine's mana-payment path is intricate, and replacing it wholesale is out of scope here. This task builds and tests the *decision function* — which colors to spend first — and wires it as a preference, deferring to `super.playMana` for the actual payment. That keeps the risky part in the engine's hands while still capturing the value.

- [ ] **Step 1: Write the failing test**

Create `Mage.Tests/src/test/java/org/mage/test/kanna/ManaPlannerTest.java`:

```java
package org.mage.test.kanna;

import mage.player.ai.kanna.ManaPlanner;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ManaPlannerTest {

    @Test
    public void spendingAColorNotNeededScoresHigherThanSpendingOneThatIs() {
        List<String> needed = Arrays.asList("R", "R");
        int spendGreen = ManaPlanner.scorePayment(Arrays.asList("G"), needed);
        int spendRed = ManaPlanner.scorePayment(Arrays.asList("R"), needed);
        assertTrue("must prefer keeping red available", spendGreen > spendRed);
    }

    @Test
    public void scoreIsZeroImpactWhenNothingIsNeeded() {
        assertEquals(ManaPlanner.scorePayment(Arrays.asList("R"), Collections.<String>emptyList()),
                ManaPlanner.scorePayment(Arrays.asList("G"), Collections.<String>emptyList()));
    }

    @Test
    public void scarcerNeededColorIsProtectedMore() {
        // one red needed, three green needed: spending the single red hurts more
        List<String> needed = Arrays.asList("R", "G", "G", "G");
        int spendRed = ManaPlanner.scorePayment(Arrays.asList("R"), needed);
        int spendGreen = ManaPlanner.scorePayment(Arrays.asList("G"), needed);
        assertTrue(spendGreen > spendRed);
    }

    @Test
    public void preferredOrderPutsUnneededColorsFirst() {
        List<String> order = ManaPlanner.preferredOrder(
                Arrays.asList("R", "G", "U"), Arrays.asList("R", "R"));
        assertTrue("red is needed, so it must not be spent first", order.indexOf("R") > 0);
    }

    @Test
    public void preferredOrderKeepsEveryAvailableColor() {
        List<String> available = Arrays.asList("R", "G", "U", "W");
        List<String> order = ManaPlanner.preferredOrder(available, Arrays.asList("R"));
        assertEquals(4, order.size());
        assertTrue(order.containsAll(available));
    }

    @Test
    public void preferredOrderIsStableForEqualPreference() {
        List<String> available = Arrays.asList("R", "G", "U");
        List<String> first = ManaPlanner.preferredOrder(available, Collections.<String>emptyList());
        List<String> second = ManaPlanner.preferredOrder(available, Collections.<String>emptyList());
        assertEquals(first, second);
    }

    @Test
    public void emptyAvailableColorsYieldsEmptyOrder() {
        assertTrue(ManaPlanner.preferredOrder(Collections.<String>emptyList(),
                Arrays.asList("R")).isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl Mage.Tests test -Dtest=ManaPlannerTest`
Expected: FAIL — `ManaPlanner` does not exist.

- [ ] **Step 3: Write `ManaPlanner`**

Create `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ManaPlanner.java`:

```java
package mage.player.ai.kanna;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which colors to spend first so the plan the model chose stays possible.
 * <p>
 * The model never sees this. It exists because paying a cost badly can strand a
 * color still needed by a card in hand, quietly invalidating the line the model
 * picked -- a loss that never appears in a log, only as wasted turns.
 *
 * @author Darrell Best
 */
public final class ManaPlanner {

    private ManaPlanner() {
    }

    /**
     * @return higher is better. Spending a color that nothing in hand needs costs
     * nothing; spending a scarce needed color costs the most.
     */
    public static int scorePayment(List<String> sourceColors, List<String> colorsNeededInHand) {
        Map<String, Integer> demand = demandByColor(colorsNeededInHand);
        int penalty = 0;
        for (String color : sourceColors) {
            Integer needed = demand.get(color);
            if (needed != null && needed > 0) {
                // scarcer demand is protected harder: needing 1 red penalises more than needing 3 green
                penalty += 100 / needed;
            }
        }
        return -penalty;
    }

    /**
     * @return the available colors ordered cheapest-to-spend first. Stable for
     * equal preference, so payment is reproducible.
     */
    public static List<String> preferredOrder(List<String> availableColors, final List<String> colorsNeededInHand) {
        List<String> order = new ArrayList<String>(availableColors);
        Collections.sort(order, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int scoreA = scorePayment(Collections.singletonList(a), colorsNeededInHand);
                int scoreB = scorePayment(Collections.singletonList(b), colorsNeededInHand);
                return Integer.compare(scoreB, scoreA);
            }
        });
        return order;
    }

    private static Map<String, Integer> demandByColor(List<String> colorsNeededInHand) {
        Map<String, Integer> demand = new HashMap<String, Integer>();
        for (String color : colorsNeededInHand) {
            Integer current = demand.get(color);
            demand.put(color, current == null ? 1 : current + 1);
        }
        return demand;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -DskipTests -pl Mage.Server.Plugins/Mage.Player.AI.Kanna install && mvn -q -pl Mage.Tests test -Dtest=ManaPlannerTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ManaPlanner.java \
        Mage.Tests/src/test/java/org/mage/test/kanna/ManaPlannerTest.java
git commit -m "kanna: add mana payment preference that protects needed colors"
```

---

### Task 8: Integration — play a real game and capture the trace

**Files:**
- Modify: `docs/superpowers/specs/2026-08-10-kanna-agentic-core-design.md` (add a "First agentic games" section)

**Interfaces:**
- Consumes: everything.
- Produces: recorded evidence, no new code.

- [ ] **Step 1: Verify Ollama is up and the model is installed**

```bash
curl -s --max-time 5 http://localhost:11434/api/tags | grep -o "xmage-ai-qwen3.6:latest" | head -1
```
Expected: prints `xmage-ai-qwen3.6:latest`. If not, stop and report — the run is meaningless without it, because Kanna's fallbacks would quietly play the whole game on heuristics.

- [ ] **Step 2: Build the classpath and install**

```bash
mvn -q -DskipTests -pl Mage.Server.Plugins/Mage.Player.AI.Kanna,Mage.Bench install
cd Mage.Tests && mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt -Dmdep.includeScope=test
```

- [ ] **Step 3: Run one game with the decision trace captured**

Run this in the FOREGROUND. Do not background it and end your turn.

```bash
cd Mage.Tests && timeout 1800 java \
  -Dlog4j.configuration=file:/tmp/claude-1000/-home-user-projects/d072943b-84a4-4a69-ba56-29ad4dcf69e1/scratchpad/demo-log4j.properties \
  -cp target/classes:target/test-classes:$(cat cp.txt) mage.bench.BenchRunner \
  --games=1 --playerA=kanna --playerB=base --deckDir=. \
  --deckA="Power Hungry.dck" --deckB="Power Hungry.dck" --turnCap=25 \
  --model=xmage-ai-qwen3.6:latest --out=/tmp/kanna-agentic-run.jsonl \
  2>&1 | tee /tmp/kanna-agentic-trace.log; echo "EXIT=$?"
```

Expected: the game completes with `termination` of `WIN`, `CAP`, or `DRAW` — **not** `ERROR`. Exit 124 means the timeout fired; that is a result, not a failure, and gets reported as-is.

- [ ] **Step 4: Extract the evidence**

```bash
grep -c "Kanna plays" /tmp/kanna-agentic-trace.log
grep -c "Kanna attacks with" /tmp/kanna-agentic-trace.log
grep -c "deferring to heuristics" /tmp/kanna-agentic-trace.log
grep -c "heuristic fallback plays" /tmp/kanna-agentic-trace.log
```

Record all four counts. The first two prove the model is genuinely driving play; the last two are the failure rate, which is the number that matters for judging whether the model is usable.

- [ ] **Step 5: Append findings to the spec**

Add a "First agentic games (2026-08-10)" section to `docs/superpowers/specs/2026-08-10-kanna-agentic-core-design.md` recording: the exact command, the game's termination and turn count, the four counts from Step 4, total wall clock, and any surprises. Report honestly — if the model deferred to heuristics on most decisions, say so plainly, because that determines whether the whole approach works.

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/specs/2026-08-10-kanna-agentic-core-design.md
git commit -m "kanna: record first agentic game results"
```

---

## Self-Review

**Spec coverage.** Every spec component maps to a task: `CreatureView`/`CombatEvaluator` (T1), `ActionCatalog` (T2), `OllamaClient` (T3), `ActionRanker`/`GameStateFormatter` (T4), `KannaAgent`/inspection tools (T5), `ComputerPlayerKanna` rewrite plus the four overrides, trivial-pass bypass, and heuristic fallback (T6), `ManaPlanner` (T7), integration (T8). Spec error-handling rows all land in T5's `KannaAgent` (no tool call, hallucinated id, transport failure, cap) and T6's `try/catch` around each combat method.

**Two deliberate deviations from the spec, recorded:**

1. **`InspectionTools` is not a separate class.** The spec listed it; in practice each callback's inspection answers are closures over that callback's own catalog and board state, so a shared class would need all of it passed in. Answers are implemented as `InspectionAnswerer` instances at the call site instead. `evaluate_combat` is consequently not offered as a tool in this plan — the combat prompt already carries `CombatEvaluator` output for every candidate attack, so the model gets the same math without a round trip. If it proves it wants what-if evaluation, that is a follow-up.
2. **`ManaPlanner` decides preference but does not replace the engine's payment path** (T7 scope note). Wholesale replacement is genuinely risky and the value is in the ordering decision, which is tested.

**Placeholder scan:** no TBD/TODO; every code step has complete compilable content; no "similar to Task N".

**Type consistency:** `CreatureView`'s 12-arg constructor is used identically in T1's tests and T4's tests. `AttackOutcome` field names (`attackerDies`, `blockersThatDie`, `damageThrough`, `unblocked`, `summary`) match across T1 and T4. `ActionCatalog`'s `add/idFor/resolve/labelFor/ids/size` match across T2, T4, T5, T6. `OllamaClient.tool/stringFieldSchema/pairArraySchema/call` match across T3, T5, T6. `Decision.of/ofPairs/fallback` and fields `chosenId/pairs/fallback` match across T5 and T6. `KannaAgent(client, maxToolCalls)`, `chooseAction`, `choosePairs`, `getInvalidCount`, and both nested interfaces match across T5 and T6. `DecisionMetrics` keeps the exact two-method shape `Mage.Bench`'s `BenchMetrics` already implements, so T6 does not break the harness.

**One risk flagged for the implementer:** T5 requires `OllamaClient` to be non-final (T3 writes it as `final`). T5 Step 3 makes that change explicitly. If tasks are executed out of order, T5 will fail to compile until that step runs.
