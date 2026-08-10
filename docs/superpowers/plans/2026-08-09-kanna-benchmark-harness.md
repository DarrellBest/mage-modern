# Kanna Benchmark Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a headless, reproducible AI-vs-AI benchmark that reports Kanna's win rate against the stock XMage AI, with the diagnostics needed to explain why a change helped or hurt.

**Architecture:** A new isolated Maven module `Mage.Bench` plays real games through the engine API (`TwoPlayerDuel`, `DeckImporter`, `game.start()`) with no `TestPlayer` wrapper and no test mode. Games run serially with a per-game RNG seed and seat swapping; each result is appended to a JSONL file immediately so long runs survive crashes. A thin JUnit smoke test in `Mage.Tests` exercises the same core without needing Ollama.

**Tech Stack:** Java 8, Maven (multi-module), Gson 2.13.2, JUnit 4 (via junit-vintage on the JUnit 5 platform), XMage engine APIs.

**Spec:** `docs/superpowers/specs/2026-08-09-kanna-benchmark-harness-design.md`

## Global Constraints

- **Java 8 only** (`<java.version>8</java.version>` in root `pom.xml`). No `var`, no records, no text blocks, no `List.of()`/`Map.of()`, no `Stream.toList()`. Use `new ArrayList<>()`, `Collections.unmodifiableList()`, and explicit generics.
- **Gson version is managed by the root pom** (2.13.2 in `<dependencyManagement>`). Declare the dependency without a `<version>` tag.
- **XMage plugin modules use `<sourceDirectory>src</sourceDirectory>`**, not the Maven default `src/main/java`. `Mage.Bench` is a top-level module, so it uses the standard `src/main/java` layout — match the top-level modules (`Mage`, `Mage.Tests`), not the plugin modules.
- **Never add a `Co-Authored-By: Claude` trailer** to any commit message in this repo.
- **Do not modify files under upstream directories** except the two explicitly required: root `pom.xml` (add module), `Mage.Tests/pom.xml` (add test dependency), plus the one Kanna plugin file in Task 8. Everything else is new files in `Mage.Bench`.
- **Mark fork-local edits to upstream-tracked files** with a `// DARRELLBEST-FORK (keep on merge/rebase from upstream):` comment, matching the existing convention in `ComputerPlayerMCTS.java` and `ThreadUtils.java`.
- **Working branch:** `ui-modernization` (current). Do not create a new branch unless asked.

## Key engine facts (verified — do not re-derive)

- `TwoPlayerDuel(MultiplayerAttackOption, RangeOfInfluence, Mulligan, int minimumDeckSize, int startLife, int startHandSize)` — note the order: deck size **before** life.
- `MatchImpl.addPlayer(player, deck)` calls `player.setMatchPlayer(...)`. This is **mandatory**: `SimulatedPlayerMCTS` does `new MatchPlayer(originalPlayer.getMatchPlayer(), this)` and will NPE if the match player is null. Every bench game must add both players to a match.
- Turn cap is free via `GameOptions`: `checkStopOnTurnOption()` fires when `stopOnTurn != null && stopAtStep == PhaseStep.UNTAP`, sets `winnerId = null` (draw), and stops. It does **not** require `testMode`.
- `game.getWinner()` returns a display `String`, not an id. Determine the winner by iterating `game.getPlayers().values()` and checking `player.hasWon()`.
- `RandomUtil` holds one process-wide static `Random` (`Mage/src/main/java/mage/util/RandomUtil.java:14`). Seeding is global — this is why games run serially.
- `ThreadUtils.ensureRunInGameThread()` allowlists a thread named `"main"`. `BenchRunner.main()` runs games on the main thread, so this passes. Do not move game execution onto a worker thread.
- Deck loading pattern (from `CardTestPlayerAPIImpl:218-241`): `DeckImporter.importDeckFromFile(name, true)` → `Deck.load(list, false, false)` → `game.loadCards(deck.getCards(), playerId)` → `game.loadCards(deck.getSideboard(), playerId)` → `game.addPlayer(player, deck)` → `match.addPlayer(player, deck)`.
- AI constructors: `ComputerPlayer(String, RangeOfInfluence)`; `ComputerPlayer7(String, RangeOfInfluence, int skill)`; `ComputerPlayerMCTS(String, RangeOfInfluence, int skill)`; `ComputerPlayerKanna(String, RangeOfInfluence, int skill)`.

---

## File Structure

**New module `Mage.Bench/`:**

| File | Responsibility |
|---|---|
| `pom.xml` | Module definition, depends on mage, mage-sets, mage-game-twoplayerduel, the four AI plugins, gson |
| `src/main/java/mage/bench/BenchConfig.java` | Immutable run parameters + CLI parsing |
| `src/main/java/mage/bench/PlayerFactory.java` | Type string → constructed `Player` |
| `src/main/java/mage/bench/LlmStats.java` | LLM call counters and latency percentiles |
| `src/main/java/mage/bench/BenchMetrics.java` | Mutable instrumentation sink Kanna reports into |
| `src/main/java/mage/bench/GameResult.java` | Outcome of one game |
| `src/main/java/mage/bench/Termination.java` | Enum: `WIN`, `CAP`, `ERROR` |
| `src/main/java/mage/bench/BenchGame.java` | Runs exactly one game |
| `src/main/java/mage/bench/ResultWriter.java` | Append-only JSONL writer |
| `src/main/java/mage/bench/RunSummary.java` | Aggregated statistics value object |
| `src/main/java/mage/bench/SummaryReporter.java` | Aggregation + Wilson interval + formatting |
| `src/main/java/mage/bench/OllamaPreflight.java` | Startup check that Ollama is up and the model exists |
| `src/main/java/mage/bench/BenchRunner.java` | `main()`, run loop, wiring |
| `src/test/java/mage/bench/*Test.java` | Unit tests |

**Modified upstream files (3 only):**
- `pom.xml` — add `<module>Mage.Bench</module>`
- `Mage.Tests/pom.xml` — add `mage-bench` test dependency
- `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ComputerPlayerKanna.java` — metrics hook (Task 8)

**New test in `Mage.Tests`:**
- `src/test/java/org/mage/test/bench/BenchSmokeTest.java`

---

### Task 1: Module skeleton and `BenchConfig`

**Files:**
- Create: `Mage.Bench/pom.xml`
- Create: `Mage.Bench/src/main/java/mage/bench/BenchConfig.java`
- Modify: `pom.xml` (add module to the `<modules>` list, after `<module>Mage.Tests</module>`)
- Test: `Mage.Bench/src/test/java/mage/bench/BenchConfigTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `BenchConfig` with public final fields `games` (int), `baseSeed` (long), `deckA`/`deckB` (String), `playerA`/`playerB` (String), `skill` (int), `model` (String), `turnCap` (int), `out` (String), `deckDir` (String), `ollamaUrl` (String); static `BenchConfig parse(String[] args)`; constructor `BenchConfig(int, long, String, String, String, String, int, String, int, String, String, String)` in that field order.

- [ ] **Step 1: Write the failing test**

Create `Mage.Bench/src/test/java/mage/bench/BenchConfigTest.java`:

```java
package mage.bench;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class BenchConfigTest {

    @Test
    public void defaults_areUsedWhenNoArgsGiven() {
        BenchConfig config = BenchConfig.parse(new String[]{});
        assertEquals(20, config.games);
        assertEquals(12345L, config.baseSeed);
        assertEquals("RB Aggro.dck", config.deckA);
        assertEquals("RB Aggro.dck", config.deckB);
        assertEquals("kanna", config.playerA);
        assertEquals("cp7", config.playerB);
        assertEquals(6, config.skill);
        assertEquals("qwen3.6:latest", config.model);
        assertEquals(50, config.turnCap);
        assertEquals("bench-results.jsonl", config.out);
    }

    @Test
    public void namedArgs_overrideDefaults() {
        BenchConfig config = BenchConfig.parse(new String[]{
                "--games=5", "--seed=99", "--playerA=cp7", "--playerB=mcts",
                "--turnCap=10", "--out=x.jsonl", "--model=m", "--skill=4",
                "--deckA=UW Control.dck", "--deckB=Power Hungry.dck"
        });
        assertEquals(5, config.games);
        assertEquals(99L, config.baseSeed);
        assertEquals("cp7", config.playerA);
        assertEquals("mcts", config.playerB);
        assertEquals(10, config.turnCap);
        assertEquals("x.jsonl", config.out);
        assertEquals("m", config.model);
        assertEquals(4, config.skill);
        assertEquals("UW Control.dck", config.deckA);
        assertEquals("Power Hungry.dck", config.deckB);
    }

    @Test
    public void unknownArg_failsClearly() {
        try {
            BenchConfig.parse(new String[]{"--nonsense=1"});
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals(true, e.getMessage().contains("nonsense"));
        }
    }

    @Test
    public void nonNumericGames_failsClearly() {
        try {
            BenchConfig.parse(new String[]{"--games=lots"});
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals(true, e.getMessage().contains("--games"));
        }
    }
}
```

- [ ] **Step 2: Create the module pom**

Create `Mage.Bench/pom.xml`:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.mage</groupId>
        <artifactId>mage-root</artifactId>
        <version>1.4.60</version>
    </parent>

    <artifactId>mage-bench</artifactId>
    <packaging>jar</packaging>
    <name>Mage Bench (headless AI-vs-AI benchmark)</name>

    <dependencies>
        <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>mage</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>mage-sets</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>mage-game-twoplayerduel</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>mage-player-ai</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>mage-player-ai-mad</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>mage-player-ai-mcts</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>mage-player-ai-kanna</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.google.code.gson</groupId>
            <artifactId>gson</artifactId>
        </dependency>
    </dependencies>

    <properties>
        <root.dir>${project.basedir}/..</root.dir>
    </properties>

</project>
```

- [ ] **Step 3: Register the module in the root pom**

In `pom.xml`, in the `<modules>` block, add after the `Mage.Tests` line:

```xml
        <module>Mage.Bench</module>
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `mvn -q -pl Mage.Bench -am test -Dtest=BenchConfigTest`
Expected: FAIL — compilation error, `BenchConfig` does not exist.

- [ ] **Step 5: Write `BenchConfig`**

Create `Mage.Bench/src/main/java/mage/bench/BenchConfig.java`:

```java
package mage.bench;

/**
 * Immutable run parameters for a benchmark run, built from CLI arguments.
 *
 * @author Darrell Best
 */
public final class BenchConfig {

    public final int games;
    public final long baseSeed;
    public final String deckA;
    public final String deckB;
    public final String playerA;
    public final String playerB;
    public final int skill;
    public final String model;
    public final int turnCap;
    public final String out;
    public final String deckDir;
    public final String ollamaUrl;

    public BenchConfig(int games, long baseSeed, String deckA, String deckB,
                       String playerA, String playerB, int skill, String model,
                       int turnCap, String out, String deckDir, String ollamaUrl) {
        this.games = games;
        this.baseSeed = baseSeed;
        this.deckA = deckA;
        this.deckB = deckB;
        this.playerA = playerA;
        this.playerB = playerB;
        this.skill = skill;
        this.model = model;
        this.turnCap = turnCap;
        this.out = out;
        this.deckDir = deckDir;
        this.ollamaUrl = ollamaUrl;
    }

    public static BenchConfig parse(String[] args) {
        int games = 20;
        long baseSeed = 12345L;
        String deckA = "RB Aggro.dck";
        String deckB = "RB Aggro.dck";
        String playerA = "kanna";
        String playerB = "cp7";
        int skill = 6;
        String model = "qwen3.6:latest";
        int turnCap = 50;
        String out = "bench-results.jsonl";
        String deckDir = "Mage.Tests";
        String ollamaUrl = "http://localhost:11434";

        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (!arg.startsWith("--") || eq < 0) {
                throw new IllegalArgumentException("Bad argument '" + arg + "', expected --name=value");
            }
            String key = arg.substring(2, eq);
            String value = arg.substring(eq + 1);
            if ("games".equals(key)) {
                games = parseInt(key, value);
            } else if ("seed".equals(key)) {
                baseSeed = parseLong(key, value);
            } else if ("deckA".equals(key)) {
                deckA = value;
            } else if ("deckB".equals(key)) {
                deckB = value;
            } else if ("playerA".equals(key)) {
                playerA = value;
            } else if ("playerB".equals(key)) {
                playerB = value;
            } else if ("skill".equals(key)) {
                skill = parseInt(key, value);
            } else if ("model".equals(key)) {
                model = value;
            } else if ("turnCap".equals(key)) {
                turnCap = parseInt(key, value);
            } else if ("out".equals(key)) {
                out = value;
            } else if ("deckDir".equals(key)) {
                deckDir = value;
            } else if ("ollamaUrl".equals(key)) {
                ollamaUrl = value;
            } else {
                throw new IllegalArgumentException("Unknown argument '--" + key + "'");
            }
        }
        return new BenchConfig(games, baseSeed, deckA, deckB, playerA, playerB,
                skill, model, turnCap, out, deckDir, ollamaUrl);
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be an integer, got '" + value + "'");
        }
    }

    private static long parseLong(String key, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be an integer, got '" + value + "'");
        }
    }

    /**
     * True when either seat uses an LLM-backed player, i.e. the run needs Ollama.
     */
    public boolean usesLlm() {
        return "kanna".equals(playerA) || "kanna".equals(playerB);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q -pl Mage.Bench -am test -Dtest=BenchConfigTest`
Expected: PASS, 4 tests.

- [ ] **Step 7: Commit**

```bash
git add pom.xml Mage.Bench/pom.xml Mage.Bench/src/main/java/mage/bench/BenchConfig.java Mage.Bench/src/test/java/mage/bench/BenchConfigTest.java
git commit -m "bench: add Mage.Bench module skeleton and BenchConfig"
```

---

### Task 2: `PlayerFactory`

**Files:**
- Create: `Mage.Bench/src/main/java/mage/bench/PlayerFactory.java`
- Test: `Mage.Bench/src/test/java/mage/bench/PlayerFactoryTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `static Player PlayerFactory.create(String type, String name, RangeOfInfluence range, int skill)`. Valid types: `"kanna"`, `"cp7"`, `"mcts"`, `"base"`. Throws `IllegalArgumentException` on unknown type.

- [ ] **Step 1: Write the failing test**

Create `Mage.Bench/src/test/java/mage/bench/PlayerFactoryTest.java`:

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl Mage.Bench -am test -Dtest=PlayerFactoryTest`
Expected: FAIL — `PlayerFactory` does not exist.

- [ ] **Step 3: Write `PlayerFactory`**

Create `Mage.Bench/src/main/java/mage/bench/PlayerFactory.java`:

```java
package mage.bench;

import mage.constants.RangeOfInfluence;
import mage.player.ai.ComputerPlayer;
import mage.player.ai.ComputerPlayer7;
import mage.player.ai.ComputerPlayerMCTS;
import mage.player.ai.kanna.ComputerPlayerKanna;
import mage.players.Player;

/**
 * Builds a benchmark player from a short type key, so any matchup is
 * expressible from the command line without code changes. Kanna-vs-stock and
 * stock-vs-stock therefore share one code path, which is what makes the
 * stock-vs-stock control run a trustworthy check on the harness itself.
 *
 * @author Darrell Best
 */
public final class PlayerFactory {

    public static final String KANNA = "kanna";
    public static final String CP7 = "cp7";
    public static final String MCTS = "mcts";
    public static final String BASE = "base";

    private PlayerFactory() {
    }

    public static Player create(String type, String name, RangeOfInfluence range, int skill) {
        if (KANNA.equals(type)) {
            return new ComputerPlayerKanna(name, range, skill);
        } else if (CP7.equals(type)) {
            return new ComputerPlayer7(name, range, skill);
        } else if (MCTS.equals(type)) {
            return new ComputerPlayerMCTS(name, range, skill);
        } else if (BASE.equals(type)) {
            return new ComputerPlayer(name, range);
        }
        throw new IllegalArgumentException("Unknown player type '" + type
                + "', expected one of: " + KANNA + ", " + CP7 + ", " + MCTS + ", " + BASE);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl Mage.Bench -am test -Dtest=PlayerFactoryTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add Mage.Bench/src/main/java/mage/bench/PlayerFactory.java Mage.Bench/src/test/java/mage/bench/PlayerFactoryTest.java
git commit -m "bench: add PlayerFactory for configurable AI matchups"
```

---

### Task 3: `LlmStats`, `BenchMetrics`, `Termination`, `GameResult`

**Files:**
- Create: `Mage.Bench/src/main/java/mage/bench/LlmStats.java`
- Create: `Mage.Bench/src/main/java/mage/bench/BenchMetrics.java`
- Create: `Mage.Bench/src/main/java/mage/bench/Termination.java`
- Create: `Mage.Bench/src/main/java/mage/bench/GameResult.java`
- Test: `Mage.Bench/src/test/java/mage/bench/BenchMetricsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum Termination { WIN, CAP, ERROR }`
  - `BenchMetrics` — `void recordLlmCall(long latencyMs)`, `void recordInvalidToolCall()`, `LlmStats snapshot()`
  - `LlmStats` — public final `int calls`, `long totalLatencyMs`, `long p50LatencyMs`, `long p95LatencyMs`, `int invalidToolCalls`
  - `GameResult` — public final `int gameIndex`, `long seed`, `String winner` (nullable), `int turns`, `long wallTimeMs`, `Termination termination`, `String errorMessage` (nullable), `boolean seatSwapped`, `LlmStats llm`; constructor in that order.

- [ ] **Step 1: Write the failing test**

Create `Mage.Bench/src/test/java/mage/bench/BenchMetricsTest.java`:

```java
package mage.bench;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BenchMetricsTest {

    @Test
    public void emptyMetrics_reportZeroes() {
        LlmStats stats = new BenchMetrics().snapshot();
        assertEquals(0, stats.calls);
        assertEquals(0L, stats.totalLatencyMs);
        assertEquals(0L, stats.p50LatencyMs);
        assertEquals(0L, stats.p95LatencyMs);
        assertEquals(0, stats.invalidToolCalls);
    }

    @Test
    public void countsCallsAndTotalLatency() {
        BenchMetrics metrics = new BenchMetrics();
        metrics.recordLlmCall(100L);
        metrics.recordLlmCall(300L);
        LlmStats stats = metrics.snapshot();
        assertEquals(2, stats.calls);
        assertEquals(400L, stats.totalLatencyMs);
    }

    @Test
    public void percentilesUseNearestRankOnSortedLatencies() {
        BenchMetrics metrics = new BenchMetrics();
        // recorded out of order on purpose: snapshot must sort before ranking
        long[] latencies = {500L, 100L, 400L, 200L, 300L, 900L, 700L, 600L, 800L, 1000L};
        for (long latency : latencies) {
            metrics.recordLlmCall(latency);
        }
        LlmStats stats = metrics.snapshot();
        assertEquals(10, stats.calls);
        // nearest-rank: p50 -> ceil(0.50*10)=5th smallest = 500
        assertEquals(500L, stats.p50LatencyMs);
        // nearest-rank: p95 -> ceil(0.95*10)=10th smallest = 1000
        assertEquals(1000L, stats.p95LatencyMs);
    }

    @Test
    public void singleCall_bothPercentilesAreThatCall() {
        BenchMetrics metrics = new BenchMetrics();
        metrics.recordLlmCall(42L);
        LlmStats stats = metrics.snapshot();
        assertEquals(42L, stats.p50LatencyMs);
        assertEquals(42L, stats.p95LatencyMs);
    }

    @Test
    public void countsInvalidToolCallsSeparatelyFromCalls() {
        BenchMetrics metrics = new BenchMetrics();
        metrics.recordLlmCall(10L);
        metrics.recordInvalidToolCall();
        metrics.recordInvalidToolCall();
        LlmStats stats = metrics.snapshot();
        assertEquals(1, stats.calls);
        assertEquals(2, stats.invalidToolCalls);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl Mage.Bench -am test -Dtest=BenchMetricsTest`
Expected: FAIL — `BenchMetrics` does not exist.

- [ ] **Step 3: Write the four classes**

Create `Mage.Bench/src/main/java/mage/bench/Termination.java`:

```java
package mage.bench;

/**
 * How a benchmark game ended. CAP and ERROR are deliberately distinct from a
 * loss: "never finished" and "lost" mean very different things, and folding
 * them together would silently distort every comparison.
 *
 * @author Darrell Best
 */
public enum Termination {
    WIN,
    CAP,
    ERROR
}
```

Create `Mage.Bench/src/main/java/mage/bench/LlmStats.java`:

```java
package mage.bench;

/**
 * Immutable snapshot of LLM usage for one game.
 *
 * @author Darrell Best
 */
public final class LlmStats {

    public final int calls;
    public final long totalLatencyMs;
    public final long p50LatencyMs;
    public final long p95LatencyMs;
    public final int invalidToolCalls;

    public LlmStats(int calls, long totalLatencyMs, long p50LatencyMs, long p95LatencyMs, int invalidToolCalls) {
        this.calls = calls;
        this.totalLatencyMs = totalLatencyMs;
        this.p50LatencyMs = p50LatencyMs;
        this.p95LatencyMs = p95LatencyMs;
        this.invalidToolCalls = invalidToolCalls;
    }

    public static LlmStats empty() {
        return new LlmStats(0, 0L, 0L, 0L, 0);
    }
}
```

Create `Mage.Bench/src/main/java/mage/bench/BenchMetrics.java`:

```java
package mage.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Instrumentation sink that an LLM-backed player reports into during a game.
 * The invalid-tool-call count is first-class rather than a log line because it
 * is the metric that evaluates custom Modelfiles.
 * <p>
 * Synchronized because the engine may call into a player from more than one
 * thread over a game's lifetime; contention is irrelevant at these call rates.
 *
 * @author Darrell Best
 */
public final class BenchMetrics {

    private final List<Long> latenciesMs = new ArrayList<>();
    private int invalidToolCalls = 0;

    public synchronized void recordLlmCall(long latencyMs) {
        latenciesMs.add(latencyMs);
    }

    public synchronized void recordInvalidToolCall() {
        invalidToolCalls++;
    }

    public synchronized LlmStats snapshot() {
        if (latenciesMs.isEmpty()) {
            return new LlmStats(0, 0L, 0L, 0L, invalidToolCalls);
        }
        List<Long> sorted = new ArrayList<>(latenciesMs);
        Collections.sort(sorted);
        long total = 0L;
        for (Long latency : sorted) {
            total += latency;
        }
        return new LlmStats(sorted.size(), total,
                percentile(sorted, 0.50), percentile(sorted, 0.95), invalidToolCalls);
    }

    /**
     * Nearest-rank percentile: the smallest value at or above the given rank.
     */
    private static long percentile(List<Long> sorted, double fraction) {
        int rank = (int) Math.ceil(fraction * sorted.size());
        if (rank < 1) {
            rank = 1;
        }
        if (rank > sorted.size()) {
            rank = sorted.size();
        }
        return sorted.get(rank - 1);
    }
}
```

Create `Mage.Bench/src/main/java/mage/bench/GameResult.java`:

```java
package mage.bench;

/**
 * Outcome of one benchmark game. Written verbatim as one JSON line.
 *
 * @author Darrell Best
 */
public final class GameResult {

    public final int gameIndex;
    public final long seed;
    /** Player key of the winner ("kanna", "cp7", ...), or null for a draw/cap/error. */
    public final String winner;
    public final int turns;
    public final long wallTimeMs;
    public final Termination termination;
    public final String errorMessage;
    public final boolean seatSwapped;
    public final LlmStats llm;

    public GameResult(int gameIndex, long seed, String winner, int turns, long wallTimeMs,
                      Termination termination, String errorMessage, boolean seatSwapped, LlmStats llm) {
        this.gameIndex = gameIndex;
        this.seed = seed;
        this.winner = winner;
        this.turns = turns;
        this.wallTimeMs = wallTimeMs;
        this.termination = termination;
        this.errorMessage = errorMessage;
        this.seatSwapped = seatSwapped;
        this.llm = llm;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl Mage.Bench -am test -Dtest=BenchMetricsTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add Mage.Bench/src/main/java/mage/bench/LlmStats.java Mage.Bench/src/main/java/mage/bench/BenchMetrics.java Mage.Bench/src/main/java/mage/bench/Termination.java Mage.Bench/src/main/java/mage/bench/GameResult.java Mage.Bench/src/test/java/mage/bench/BenchMetricsTest.java
git commit -m "bench: add result and metrics value types"
```

---

### Task 4: `ResultWriter`

**Files:**
- Create: `Mage.Bench/src/main/java/mage/bench/ResultWriter.java`
- Test: `Mage.Bench/src/test/java/mage/bench/ResultWriterTest.java`

**Interfaces:**
- Consumes: `GameResult`, `LlmStats`, `Termination` from Task 3.
- Produces: `ResultWriter implements Closeable` — constructor `ResultWriter(String path)` (throws `IOException`), `void append(GameResult result)` (throws `IOException`), `void close()`; plus `static List<GameResult> read(String path)` (throws `IOException`) used by `SummaryReporter` and tests.

- [ ] **Step 1: Write the failing test**

Create `Mage.Bench/src/test/java/mage/bench/ResultWriterTest.java`:

```java
package mage.bench;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ResultWriterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private GameResult result(int index, String winner, Termination termination) {
        return new GameResult(index, 100L + index, winner, 12, 3400L, termination, null, index % 2 == 1, LlmStats.empty());
    }

    @Test
    public void roundTripsResultsThroughJsonl() throws Exception {
        File out = new File(folder.getRoot(), "r.jsonl");
        ResultWriter writer = new ResultWriter(out.getAbsolutePath());
        writer.append(result(0, "kanna", Termination.WIN));
        writer.append(result(1, "cp7", Termination.WIN));
        writer.close();

        List<GameResult> read = ResultWriter.read(out.getAbsolutePath());
        assertEquals(2, read.size());
        assertEquals(0, read.get(0).gameIndex);
        assertEquals("kanna", read.get(0).winner);
        assertEquals(Termination.WIN, read.get(0).termination);
        assertEquals(false, read.get(0).seatSwapped);
        assertEquals(101L, read.get(1).seed);
        assertEquals(true, read.get(1).seatSwapped);
    }

    @Test
    public void writesOneLinePerResult() throws Exception {
        File out = new File(folder.getRoot(), "lines.jsonl");
        ResultWriter writer = new ResultWriter(out.getAbsolutePath());
        writer.append(result(0, "kanna", Termination.WIN));
        writer.append(result(1, null, Termination.CAP));
        writer.append(result(2, null, Termination.ERROR));
        writer.close();

        List<String> lines = java.nio.file.Files.readAllLines(out.toPath());
        assertEquals(3, lines.size());
    }

    @Test
    public void dataSurvivesWithoutClose_becauseEachAppendFlushes() throws Exception {
        File out = new File(folder.getRoot(), "crash.jsonl");
        ResultWriter writer = new ResultWriter(out.getAbsolutePath());
        writer.append(result(0, "kanna", Termination.WIN));
        // deliberately NOT closed: simulates a killed run
        List<GameResult> read = ResultWriter.read(out.getAbsolutePath());
        assertEquals(1, read.size());
        writer.close();
    }

    @Test
    public void nullWinnerRoundTripsAsNull() throws Exception {
        File out = new File(folder.getRoot(), "draw.jsonl");
        ResultWriter writer = new ResultWriter(out.getAbsolutePath());
        writer.append(result(0, null, Termination.CAP));
        writer.close();
        assertNull(ResultWriter.read(out.getAbsolutePath()).get(0).winner);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl Mage.Bench -am test -Dtest=ResultWriterTest`
Expected: FAIL — `ResultWriter` does not exist.

- [ ] **Step 3: Write `ResultWriter`**

Create `Mage.Bench/src/main/java/mage/bench/ResultWriter.java`:

```java
package mage.bench;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only JSONL writer, one line per finished game, flushed on every
 * append. Runs are long enough that a crash at game 73 must keep 73 games,
 * and separate JVM processes can write distinct files that merge trivially.
 *
 * @author Darrell Best
 */
public final class ResultWriter implements Closeable {

    private static final Gson GSON = new Gson();

    private final Writer writer;

    public ResultWriter(String path) throws IOException {
        this.writer = new OutputStreamWriter(new FileOutputStream(path, true), StandardCharsets.UTF_8);
    }

    public void append(GameResult result) throws IOException {
        writer.write(GSON.toJson(result));
        writer.write(System.lineSeparator());
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    public static List<GameResult> read(String path) throws IOException {
        List<GameResult> results = new ArrayList<>();
        if (!Files.exists(Paths.get(path))) {
            return results;
        }
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    results.add(GSON.fromJson(line, GameResult.class));
                }
            }
        }
        return results;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl Mage.Bench -am test -Dtest=ResultWriterTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add Mage.Bench/src/main/java/mage/bench/ResultWriter.java Mage.Bench/src/test/java/mage/bench/ResultWriterTest.java
git commit -m "bench: add append-only JSONL result writer"
```

---

### Task 5: `RunSummary` and `SummaryReporter`

**Files:**
- Create: `Mage.Bench/src/main/java/mage/bench/RunSummary.java`
- Create: `Mage.Bench/src/main/java/mage/bench/SummaryReporter.java`
- Test: `Mage.Bench/src/test/java/mage/bench/SummaryReporterTest.java`

**Interfaces:**
- Consumes: `GameResult`, `Termination`, `LlmStats` from Task 3.
- Produces:
  - `RunSummary` — public final `int total`, `int decisive`, `int winsA`, `int winsB`, `int caps`, `int errors`, `double winRateA`, `double wilsonLowerA`, `double wilsonUpperA`, `long p50TurnMs`, `long p95TurnMs`, `int llmCalls`, `int invalidToolCalls`
  - `SummaryReporter` — `static RunSummary summarize(List<GameResult> results, String playerAKey)`, `static String format(RunSummary summary, String playerAKey, String playerBKey)`

**Note on the win-rate denominator:** cap and error games are excluded from `decisive`, so `winRateA = winsA / decisive`. They are reported separately. `winner` in `GameResult` is already the *player key*, so seat swapping needs no extra un-mapping here — `BenchGame` (Task 6) is responsible for recording the key rather than the seat.

- [ ] **Step 1: Write the failing test**

Create `Mage.Bench/src/test/java/mage/bench/SummaryReporterTest.java`:

```java
package mage.bench;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SummaryReporterTest {

    private GameResult game(int index, String winner, Termination termination, long wallMs) {
        return new GameResult(index, index, winner, 10, wallMs, termination, null, index % 2 == 1, LlmStats.empty());
    }

    private List<GameResult> games(int kannaWins, int cp7Wins, int caps, int errors) {
        List<GameResult> results = new ArrayList<>();
        int index = 0;
        for (int i = 0; i < kannaWins; i++) {
            results.add(game(index++, "kanna", Termination.WIN, 1000L));
        }
        for (int i = 0; i < cp7Wins; i++) {
            results.add(game(index++, "cp7", Termination.WIN, 1000L));
        }
        for (int i = 0; i < caps; i++) {
            results.add(game(index++, null, Termination.CAP, 1000L));
        }
        for (int i = 0; i < errors; i++) {
            results.add(game(index++, null, Termination.ERROR, 1000L));
        }
        return results;
    }

    @Test
    public void countsWinsCapsAndErrorsSeparately() {
        RunSummary summary = SummaryReporter.summarize(games(6, 4, 3, 2), "kanna");
        assertEquals(15, summary.total);
        assertEquals(10, summary.decisive);
        assertEquals(6, summary.winsA);
        assertEquals(4, summary.winsB);
        assertEquals(3, summary.caps);
        assertEquals(2, summary.errors);
    }

    @Test
    public void winRateExcludesCapsAndErrorsFromTheDenominator() {
        // 6 wins of 10 decisive games is 60%, NOT 6/15 = 40%
        RunSummary summary = SummaryReporter.summarize(games(6, 4, 3, 2), "kanna");
        assertEquals(0.60, summary.winRateA, 0.0001);
    }

    @Test
    public void wilsonInterval_fiftyPercentOfTen() {
        RunSummary summary = SummaryReporter.summarize(games(5, 5, 0, 0), "kanna");
        assertEquals(0.50, summary.winRateA, 0.0001);
        assertEquals(0.23659, summary.wilsonLowerA, 0.001);
        assertEquals(0.76341, summary.wilsonUpperA, 0.001);
    }

    @Test
    public void wilsonInterval_zeroWins_lowerBoundIsZeroNotNegative() {
        RunSummary summary = SummaryReporter.summarize(games(0, 10, 0, 0), "kanna");
        assertEquals(0.0, summary.winRateA, 0.0001);
        assertEquals(0.0, summary.wilsonLowerA, 0.001);
        assertEquals(0.27754, summary.wilsonUpperA, 0.001);
    }

    @Test
    public void wilsonInterval_allWins_upperBoundIsOneNotAboveOne() {
        RunSummary summary = SummaryReporter.summarize(games(10, 0, 0, 0), "kanna");
        assertEquals(1.0, summary.winRateA, 0.0001);
        assertEquals(0.72246, summary.wilsonLowerA, 0.001);
        assertEquals(1.0, summary.wilsonUpperA, 0.001);
    }

    @Test
    public void noDecisiveGames_doesNotDivideByZero() {
        RunSummary summary = SummaryReporter.summarize(games(0, 0, 4, 1), "kanna");
        assertEquals(0, summary.decisive);
        assertEquals(0.0, summary.winRateA, 0.0001);
        assertEquals(0.0, summary.wilsonLowerA, 0.0001);
        assertEquals(0.0, summary.wilsonUpperA, 0.0001);
    }

    @Test
    public void emptyResults_summarizeCleanly() {
        RunSummary summary = SummaryReporter.summarize(new ArrayList<GameResult>(), "kanna");
        assertEquals(0, summary.total);
        assertEquals(0, summary.decisive);
    }

    @Test
    public void formatMentionsBothPlayersAndTheCapCount() {
        RunSummary summary = SummaryReporter.summarize(games(6, 4, 3, 2), "kanna");
        String text = SummaryReporter.format(summary, "kanna", "cp7");
        assertTrue(text.contains("kanna"));
        assertTrue(text.contains("cp7"));
        assertTrue(text.contains("3"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl Mage.Bench -am test -Dtest=SummaryReporterTest`
Expected: FAIL — `SummaryReporter` does not exist.

- [ ] **Step 3: Write `RunSummary`**

Create `Mage.Bench/src/main/java/mage/bench/RunSummary.java`:

```java
package mage.bench;

/**
 * Aggregated statistics for a benchmark run.
 *
 * @author Darrell Best
 */
public final class RunSummary {

    public final int total;
    public final int decisive;
    public final int winsA;
    public final int winsB;
    public final int caps;
    public final int errors;
    public final double winRateA;
    public final double wilsonLowerA;
    public final double wilsonUpperA;
    public final long p50TurnMs;
    public final long p95TurnMs;
    public final int llmCalls;
    public final int invalidToolCalls;

    public RunSummary(int total, int decisive, int winsA, int winsB, int caps, int errors,
                      double winRateA, double wilsonLowerA, double wilsonUpperA,
                      long p50TurnMs, long p95TurnMs, int llmCalls, int invalidToolCalls) {
        this.total = total;
        this.decisive = decisive;
        this.winsA = winsA;
        this.winsB = winsB;
        this.caps = caps;
        this.errors = errors;
        this.winRateA = winRateA;
        this.wilsonLowerA = wilsonLowerA;
        this.wilsonUpperA = wilsonUpperA;
        this.p50TurnMs = p50TurnMs;
        this.p95TurnMs = p95TurnMs;
        this.llmCalls = llmCalls;
        this.invalidToolCalls = invalidToolCalls;
    }
}
```

- [ ] **Step 4: Write `SummaryReporter`**

Create `Mage.Bench/src/main/java/mage/bench/SummaryReporter.java`:

```java
package mage.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregates game results into a run summary.
 * <p>
 * Uses a Wilson score interval rather than the normal approximation: at the
 * small N these runs produce, and at rates near 0 or 1, the normal interval
 * gives bounds outside [0, 1] and badly understates uncertainty.
 * <p>
 * Cap and error games are excluded from the win-rate denominator and reported
 * separately -- "never finished" and "lost" mean different things.
 *
 * @author Darrell Best
 */
public final class SummaryReporter {

    /** 95% two-sided normal quantile. */
    private static final double Z = 1.96;

    private SummaryReporter() {
    }

    public static RunSummary summarize(List<GameResult> results, String playerAKey) {
        int total = results.size();
        int winsA = 0;
        int winsB = 0;
        int caps = 0;
        int errors = 0;
        int llmCalls = 0;
        int invalidToolCalls = 0;
        List<Long> turnTimes = new ArrayList<>();

        for (GameResult result : results) {
            if (result.termination == Termination.CAP) {
                caps++;
            } else if (result.termination == Termination.ERROR) {
                errors++;
            } else if (playerAKey.equals(result.winner)) {
                winsA++;
            } else if (result.winner != null) {
                winsB++;
            }
            if (result.llm != null) {
                llmCalls += result.llm.calls;
                invalidToolCalls += result.llm.invalidToolCalls;
            }
            if (result.turns > 0) {
                turnTimes.add(result.wallTimeMs / result.turns);
            }
        }

        int decisive = winsA + winsB;
        double winRateA = decisive == 0 ? 0.0 : (double) winsA / decisive;
        double[] interval = wilson(winsA, decisive);

        Collections.sort(turnTimes);
        return new RunSummary(total, decisive, winsA, winsB, caps, errors,
                winRateA, interval[0], interval[1],
                percentile(turnTimes, 0.50), percentile(turnTimes, 0.95),
                llmCalls, invalidToolCalls);
    }

    /**
     * Wilson score interval. Returns {lower, upper}, both clamped to [0, 1].
     */
    static double[] wilson(int successes, int n) {
        if (n == 0) {
            return new double[]{0.0, 0.0};
        }
        double p = (double) successes / n;
        double z2 = Z * Z;
        double denominator = 1.0 + z2 / n;
        double center = (p + z2 / (2.0 * n)) / denominator;
        double margin = Z * Math.sqrt((p * (1.0 - p) + z2 / (4.0 * n)) / n) / denominator;
        double lower = Math.max(0.0, center - margin);
        double upper = Math.min(1.0, center + margin);
        return new double[]{lower, upper};
    }

    private static long percentile(List<Long> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int rank = (int) Math.ceil(fraction * sorted.size());
        if (rank < 1) {
            rank = 1;
        }
        if (rank > sorted.size()) {
            rank = sorted.size();
        }
        return sorted.get(rank - 1);
    }

    public static String format(RunSummary summary, String playerAKey, String playerBKey) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n=== Benchmark summary ===%n"));
        sb.append(String.format("Games:        %d total, %d decisive, %d cap, %d error%n",
                summary.total, summary.decisive, summary.caps, summary.errors));
        sb.append(String.format("%-12s %d wins%n", playerAKey + ":", summary.winsA));
        sb.append(String.format("%-12s %d wins%n", playerBKey + ":", summary.winsB));
        sb.append(String.format("Win rate:     %.1f%% for %s  (95%% CI %.1f%% - %.1f%%)%n",
                summary.winRateA * 100.0, playerAKey,
                summary.wilsonLowerA * 100.0, summary.wilsonUpperA * 100.0));
        sb.append(String.format("Turn time:    p50 %d ms, p95 %d ms%n", summary.p50TurnMs, summary.p95TurnMs));
        sb.append(String.format("LLM:          %d calls, %d invalid tool calls%n",
                summary.llmCalls, summary.invalidToolCalls));
        return sb.toString();
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q -pl Mage.Bench -am test -Dtest=SummaryReporterTest`
Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add Mage.Bench/src/main/java/mage/bench/RunSummary.java Mage.Bench/src/main/java/mage/bench/SummaryReporter.java Mage.Bench/src/test/java/mage/bench/SummaryReporterTest.java
git commit -m "bench: add run summary with Wilson score interval"
```

---

### Task 6: `BenchGame` — run one real game

**Files:**
- Create: `Mage.Bench/src/main/java/mage/bench/BenchGame.java`
- Test: none in this task — `BenchGame` needs the full card database loaded, which makes it an integration concern. It is covered by `BenchSmokeTest` in Task 9.

**Interfaces:**
- Consumes: `BenchConfig` (Task 1), `PlayerFactory` (Task 2), `GameResult`/`LlmStats`/`Termination`/`BenchMetrics` (Task 3).
- Produces: `static GameResult BenchGame.run(BenchConfig config, int gameIndex, long seed, boolean seatSwapped)`.

**Design notes for the implementer:**
- Both players **must** be added to a `TwoPlayerMatch`, otherwise `SimulatedPlayerMCTS` NPEs on `getMatchPlayer()`.
- Seat swapping: on swapped games, the player built from `config.playerB` takes the first seat. The returned `winner` is the **player key**, not the seat, so `SummaryReporter` needs no un-mapping.
- Turn cap uses `GameOptions.stopOnTurn` with `stopAtStep = PhaseStep.UNTAP`, which the engine treats as a draw. Distinguish a genuine cap from a real draw by comparing the final turn number to the cap.
- The winner is found by iterating players and checking `hasWon()`, because `game.getWinner()` returns a display string.
- If both player keys are identical (the `cp7` vs `cp7` control run), the names still differ (`A`/`B`), so the winner key is derived from the seat mapping rather than the name.

- [ ] **Step 1: Write `BenchGame`**

Create `Mage.Bench/src/main/java/mage/bench/BenchGame.java`:

```java
package mage.bench;

import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.constants.MultiplayerAttackOption;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameOptions;
import mage.game.TwoPlayerDuel;
import mage.game.TwoPlayerMatch;
import mage.game.match.MatchOptions;
import mage.game.mulligan.MulliganType;
import mage.player.ai.kanna.ComputerPlayerKanna;
import mage.players.Player;
import mage.util.RandomUtil;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs exactly one benchmark game. Knows nothing about runs, files or
 * aggregation.
 * <p>
 * Plays a real game through the engine API: no TestPlayer wrapper and no
 * test mode, because that scaffolding changes error handling and choice
 * behavior and would make the benchmark measure the harness rather than the
 * shipped AI.
 *
 * @author Darrell Best
 */
public final class BenchGame {

    private static final Map<String, DeckCardLists> DECK_CACHE = new HashMap<>();

    private BenchGame() {
    }

    public static GameResult run(BenchConfig config, int gameIndex, long seed, boolean seatSwapped) {
        long startNanos = System.nanoTime();
        BenchMetrics metrics = new BenchMetrics();
        Game game = null;
        try {
            RandomUtil.setSeed(seed);

            game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                    MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);

            TwoPlayerMatch match = new TwoPlayerMatch(
                    new MatchOptions("bench match", "bench game type", false));

            // seat 1 / seat 2 assignment, swapped on odd games so play/draw advantage cancels
            String seat1Key = seatSwapped ? config.playerB : config.playerA;
            String seat2Key = seatSwapped ? config.playerA : config.playerB;
            String seat1Deck = seatSwapped ? config.deckB : config.deckA;
            String seat2Deck = seatSwapped ? config.deckA : config.deckB;

            Player seat1 = addPlayer(game, match, config, seat1Key, "Seat1", seat1Deck, metrics);
            Player seat2 = addPlayer(game, match, config, seat2Key, "Seat2", seat2Deck, metrics);

            GameOptions options = new GameOptions();
            options.testMode = false;
            options.stopOnTurn = config.turnCap;
            options.stopAtStep = PhaseStep.UNTAP;
            game.setGameOptions(options);

            game.start(seat1.getId());

            int turns = game.getState().getTurnNum();
            String winnerKey = null;
            if (seat1.hasWon()) {
                winnerKey = seat1Key;
            } else if (seat2.hasWon()) {
                winnerKey = seat2Key;
            }

            Termination termination;
            if (winnerKey != null) {
                termination = Termination.WIN;
            } else {
                // engine treats the turn cap as a draw; a genuine draw before the cap is
                // vanishingly rare in a duel, so treat "no winner" at or past the cap as CAP
                termination = Termination.CAP;
            }

            long wallMs = (System.nanoTime() - startNanos) / 1_000_000L;
            return new GameResult(gameIndex, seed, winnerKey, turns, wallMs,
                    termination, null, seatSwapped, metrics.snapshot());

        } catch (Throwable e) {
            long wallMs = (System.nanoTime() - startNanos) / 1_000_000L;
            int turns = game == null ? 0 : game.getState().getTurnNum();
            String message = e.getClass().getSimpleName() + ": " + e.getMessage();
            return new GameResult(gameIndex, seed, null, turns, wallMs,
                    Termination.ERROR, message, seatSwapped, metrics.snapshot());
        }
    }

    private static Player addPlayer(Game game, TwoPlayerMatch match, BenchConfig config,
                                    String typeKey, String name, String deckName,
                                    BenchMetrics metrics) throws Exception {
        Player player = PlayerFactory.create(typeKey, name, RangeOfInfluence.ONE, config.skill);
        if (player instanceof ComputerPlayerKanna) {
            ComputerPlayerKanna kanna = (ComputerPlayerKanna) player;
            kanna.setBenchMetrics(metrics);
            kanna.setModel(config.model);
            kanna.setOllamaUrl(config.ollamaUrl + "/api/chat");
        }

        Deck deck = Deck.load(loadDeckList(config.deckDir, deckName), false, false);
        if (deck.getMaindeckCards().size() < 40) {
            throw new IllegalArgumentException("Deck '" + deckName + "' loaded only "
                    + deck.getMaindeckCards().size() + " cards");
        }

        game.loadCards(deck.getCards(), player.getId());
        game.loadCards(deck.getSideboard(), player.getId());
        game.addPlayer(player, deck);
        // mandatory: MatchImpl.addPlayer sets the MatchPlayer, and SimulatedPlayerMCTS
        // dereferences it during rollouts
        match.addPlayer(player, deck);
        return player;
    }

    private static DeckCardLists loadDeckList(String deckDir, String deckName) {
        String key = deckDir + "/" + deckName;
        DeckCardLists cached = DECK_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        File file = new File(deckDir, deckName);
        if (!file.exists()) {
            throw new IllegalArgumentException("Deck file not found: " + file.getAbsolutePath());
        }
        DeckCardLists list = DeckImporter.importDeckFromFile(file.getAbsolutePath(), true);
        DECK_CACHE.put(key, list);
        return list;
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -q -pl Mage.Bench -am compile`
Expected: FAIL — `setBenchMetrics`, `setModel`, and `setOllamaUrl` do not exist on `ComputerPlayerKanna` yet. That is expected; Task 7 adds them. Do not commit a broken build — proceed directly to Task 7 and commit both together.

---

### Task 7: Kanna instrumentation hooks

**Files:**
- Modify: `Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ComputerPlayerKanna.java`
- Modify: `Mage.Server.Plugins/Mage.Player.AI.Kanna/pom.xml` (no new dependency needed — see note)

**Interfaces:**
- Consumes: nothing (deliberately — see note below).
- Produces: on `ComputerPlayerKanna`: `void setModel(String)`, `void setOllamaUrl(String)`, `void setBenchMetrics(Object)` plus internal recording.

**Critical note on the dependency direction:** `Mage.Bench` depends on `mage-player-ai-kanna`. Kanna must **not** depend on `Mage.Bench` — that would be a dependency cycle and Maven will refuse to build it. So Kanna cannot reference the `BenchMetrics` type directly.

Resolve this with a minimal callback interface **declared inside the Kanna module**, which `BenchMetrics` then implements. Update Task 6's call to `kanna.setBenchMetrics(metrics)` accordingly — the parameter type is `ComputerPlayerKanna.DecisionMetrics`, and `BenchMetrics` implements it.

- [ ] **Step 1: Add the callback interface and hooks to `ComputerPlayerKanna`**

In `ComputerPlayerKanna.java`, change the three constants from `private static final` to instance fields with setters. Replace:

```java
    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String OLLAMA_MODEL = "deepseek-v4-pro:cloud";
    private static final int REQUEST_TIMEOUT_MS = 30_000;
```

with:

```java
    private static final int REQUEST_TIMEOUT_MS = 30_000;

    // DARRELLBEST-FORK (keep on merge/rebase from upstream): url/model are instance fields
    // rather than constants so the benchmark harness can point a run at a specific local
    // model without rebuilding. Defaults match the previous hardcoded values' intent.
    private String ollamaUrl = "http://localhost:11434/api/chat";
    private String ollamaModel = "qwen3.6:latest";

    /**
     * Instrumentation callback the benchmark harness supplies. Declared here rather than
     * imported from the bench module because Mage.Bench depends on this module, not the
     * other way round -- the reverse would be a dependency cycle. No-ops when unset, so
     * the plugin stays usable on the live server.
     */
    public interface DecisionMetrics {
        void recordLlmCall(long latencyMs);

        void recordInvalidToolCall();
    }

    private DecisionMetrics metrics;

    public void setOllamaUrl(String ollamaUrl) {
        this.ollamaUrl = ollamaUrl;
    }

    public void setModel(String model) {
        this.ollamaModel = model;
    }

    public void setBenchMetrics(DecisionMetrics metrics) {
        this.metrics = metrics;
    }
```

- [ ] **Step 2: Carry the new fields through the copy constructor**

In the copy constructor `ComputerPlayerKanna(final ComputerPlayerKanna player)`, after `this.combatHistory.addAll(player.combatHistory);` add:

```java
        this.ollamaUrl = player.ollamaUrl;
        this.ollamaModel = player.ollamaModel;
        this.metrics = player.metrics;
```

- [ ] **Step 3: Replace the constant references**

Replace every remaining use of `OLLAMA_MODEL` with `ollamaModel` and `OLLAMA_URL` with `ollamaUrl`. There are four `OLLAMA_MODEL` uses (two `logger.info` lines in the declare summaries, the `body.addProperty("model", ...)` call, and the prompt-logging line) and one `OLLAMA_URL` use (the `postJson` call in `callOllamaForDecision`).

- [ ] **Step 4: Record LLM call latency**

In `callOllamaForDecision`, wrap the HTTP call. Replace:

```java
        String responseBody = postJson(OLLAMA_URL, body.toString());
```

with:

```java
        long callStart = System.nanoTime();
        String responseBody;
        try {
            responseBody = postJson(ollamaUrl, body.toString());
        } finally {
            if (metrics != null) {
                metrics.recordLlmCall((System.nanoTime() - callStart) / 1_000_000L);
            }
        }
```

- [ ] **Step 5: Record hallucinated tool calls**

In `chooseAttackersWithKanna`, inside the `if (attacker == null || defenderId == null || ...)` branch, immediately after the existing `logger.warn(...)` line and before `continue;`, add:

```java
                if (metrics != null) {
                    metrics.recordInvalidToolCall();
                }
```

Do the same in `chooseBlockersWithKanna`, inside its corresponding `if (blocker == null || attacker == null || ...)` branch, after its `logger.warn(...)` line.

- [ ] **Step 6: Update `BenchGame` to use the interface type**

In `Mage.Bench/src/main/java/mage/bench/BenchGame.java`, the call `kanna.setBenchMetrics(metrics)` now requires `BenchMetrics` to implement the interface. In `Mage.Bench/src/main/java/mage/bench/BenchMetrics.java`, change the class declaration:

```java
public final class BenchMetrics implements mage.player.ai.kanna.ComputerPlayerKanna.DecisionMetrics {
```

and add `@Override` to `recordLlmCall` and `recordInvalidToolCall`.

- [ ] **Step 7: Verify the whole thing compiles and existing tests still pass**

Run: `mvn -q -pl Mage.Bench -am compile`
Expected: BUILD SUCCESS.

Run: `mvn -q -pl Mage.Bench test`
Expected: PASS — all tests from Tasks 1-5 still green.

- [ ] **Step 8: Commit**

```bash
git add Mage.Server.Plugins/Mage.Player.AI.Kanna/src/mage/player/ai/kanna/ComputerPlayerKanna.java Mage.Bench/src/main/java/mage/bench/BenchGame.java Mage.Bench/src/main/java/mage/bench/BenchMetrics.java
git commit -m "bench: add BenchGame and Kanna instrumentation hooks"
```

---

### Task 8: `OllamaPreflight` and `BenchRunner`

**Files:**
- Create: `Mage.Bench/src/main/java/mage/bench/OllamaPreflight.java`
- Create: `Mage.Bench/src/main/java/mage/bench/BenchRunner.java`
- Test: `Mage.Bench/src/test/java/mage/bench/OllamaPreflightTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1-7.
- Produces: `static void OllamaPreflight.check(String baseUrl, String model)` throwing `IllegalStateException`; `static boolean OllamaPreflight.modelPresent(String tagsJson, String model)` (package-visible for testing); `BenchRunner.main(String[])`.

**Why preflight is fail-fast:** Kanna's `selectAttackers`/`selectBlockers` catch `Throwable` and fall back to declaring nothing. An unreachable Ollama would therefore produce a full run of plausible-looking games in which Kanna never attacks or blocks — a meaningless win rate that looks valid. That failure must be loud and immediate.

- [ ] **Step 1: Write the failing test**

Create `Mage.Bench/src/test/java/mage/bench/OllamaPreflightTest.java`:

```java
package mage.bench;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OllamaPreflightTest {

    private static final String TAGS_JSON =
            "{\"models\":[{\"name\":\"qwen3.6:latest\"},{\"name\":\"gemma4:31b\"}]}";

    @Test
    public void findsAModelThatIsPresent() {
        assertTrue(OllamaPreflight.modelPresent(TAGS_JSON, "qwen3.6:latest"));
        assertTrue(OllamaPreflight.modelPresent(TAGS_JSON, "gemma4:31b"));
    }

    @Test
    public void rejectsAModelThatIsAbsent() {
        assertFalse(OllamaPreflight.modelPresent(TAGS_JSON, "llama9:latest"));
    }

    @Test
    public void emptyModelList_rejectsEverything() {
        assertFalse(OllamaPreflight.modelPresent("{\"models\":[]}", "qwen3.6:latest"));
    }

    @Test
    public void malformedJson_rejectsRatherThanThrowing() {
        assertFalse(OllamaPreflight.modelPresent("not json", "qwen3.6:latest"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl Mage.Bench -am test -Dtest=OllamaPreflightTest`
Expected: FAIL — `OllamaPreflight` does not exist.

- [ ] **Step 3: Write `OllamaPreflight`**

Create `Mage.Bench/src/main/java/mage/bench/OllamaPreflight.java`:

```java
package mage.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Startup check that Ollama is reachable and the configured model exists.
 * <p>
 * This is deliberately fail-fast. Kanna's combat methods catch Throwable and
 * fall back to declaring no attacks or blocks, so an unreachable Ollama would
 * silently produce a full run of games in which Kanna never fights -- a
 * meaningless win rate that still looks valid.
 *
 * @author Darrell Best
 */
public final class OllamaPreflight {

    private static final int TIMEOUT_MS = 5000;

    private OllamaPreflight() {
    }

    public static void check(String baseUrl, String model) {
        String body;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/api/tags").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            body = sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Ollama is not reachable at " + baseUrl
                    + " -- an LLM run cannot proceed, because Kanna would silently fall back to"
                    + " declaring no attacks and report a meaningless win rate. Cause: " + e);
        }
        if (!modelPresent(body, model)) {
            throw new IllegalStateException("Ollama is up at " + baseUrl + " but model '" + model
                    + "' is not installed. Run: ollama pull " + model);
        }
    }

    static boolean modelPresent(String tagsJson, String model) {
        try {
            JsonObject root = JsonParser.parseString(tagsJson).getAsJsonObject();
            JsonArray models = root.getAsJsonArray("models");
            if (models == null) {
                return false;
            }
            for (JsonElement element : models) {
                JsonObject entry = element.getAsJsonObject();
                if (entry.has("name") && model.equals(entry.get("name").getAsString())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl Mage.Bench -am test -Dtest=OllamaPreflightTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Write `BenchRunner`**

Create `Mage.Bench/src/main/java/mage/bench/BenchRunner.java`:

```java
package mage.bench;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for a benchmark run.
 * <p>
 * Games run serially on the main thread, for two independent reasons: Ollama
 * serializes requests so parallelism buys little, and RandomUtil holds one
 * process-wide static Random, so parallel in-process games would destroy
 * per-game reproducibility. Running on the main thread also satisfies
 * ThreadUtils.ensureRunInGameThread(), which allowlists the thread named
 * "main".
 *
 * @author Darrell Best
 */
public final class BenchRunner {

    private static final int PROGRESS_EVERY = 5;

    public static void main(String[] args) throws Exception {
        BenchConfig config = BenchConfig.parse(args);

        if (config.usesLlm()) {
            OllamaPreflight.check(config.ollamaUrl, config.model);
            System.out.println("Ollama preflight OK: " + config.model + " at " + config.ollamaUrl);
        }

        System.out.println(String.format("Running %d games: %s vs %s (seed %d, turn cap %d)",
                config.games, config.playerA, config.playerB, config.baseSeed, config.turnCap));

        List<GameResult> results = new ArrayList<>();
        try (ResultWriter writer = new ResultWriter(config.out)) {
            for (int i = 0; i < config.games; i++) {
                long seed = config.baseSeed + i;
                boolean seatSwapped = (i % 2 == 1);

                GameResult result = BenchGame.run(config, i, seed, seatSwapped);
                writer.append(result);
                results.add(result);

                System.out.println(String.format("  game %d/%d  seed=%d  %s  winner=%s  turns=%d  %dms",
                        i + 1, config.games, seed, result.termination,
                        result.winner == null ? "-" : result.winner,
                        result.turns, result.wallTimeMs));

                if ((i + 1) % PROGRESS_EVERY == 0 && (i + 1) < config.games) {
                    System.out.print(SummaryReporter.format(
                            SummaryReporter.summarize(results, config.playerA),
                            config.playerA, config.playerB));
                }
            }
        }

        System.out.print(SummaryReporter.format(
                SummaryReporter.summarize(results, config.playerA),
                config.playerA, config.playerB));
        System.out.println("Results written to " + config.out);
    }
}
```

- [ ] **Step 6: Verify it compiles**

Run: `mvn -q -pl Mage.Bench -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add Mage.Bench/src/main/java/mage/bench/OllamaPreflight.java Mage.Bench/src/main/java/mage/bench/BenchRunner.java Mage.Bench/src/test/java/mage/bench/OllamaPreflightTest.java
git commit -m "bench: add Ollama preflight check and BenchRunner entry point"
```

---

### Task 9: `BenchSmokeTest` and first real run

**Files:**
- Modify: `Mage.Tests/pom.xml` (add `mage-bench` dependency)
- Create: `Mage.Tests/src/test/java/org/mage/test/bench/BenchSmokeTest.java`

**Interfaces:**
- Consumes: `BenchConfig`, `BenchGame`, `GameResult`, `Termination` from earlier tasks.
- Produces: nothing consumed by later tasks.

**Why this test uses `base` players, not Kanna:** it must run in CI without Ollama, and it exercises the harness rather than the LLM path. `base` (`ComputerPlayer`) is also far faster than `cp7`, keeping the test to seconds.

- [ ] **Step 1: Add the dependency to `Mage.Tests/pom.xml`**

In the `<dependencies>` block, after the `mage-player-ai-kanna` dependency, add:

```xml
        <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>mage-bench</artifactId>
            <version>${project.version}</version>
            <scope>compile</scope>
        </dependency>
```

- [ ] **Step 2: Write the test**

Create `Mage.Tests/src/test/java/org/mage/test/bench/BenchSmokeTest.java`:

```java
package org.mage.test.bench;

import mage.bench.BenchConfig;
import mage.bench.BenchGame;
import mage.bench.GameResult;
import mage.bench.Termination;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * DARRELLBEST-FORK: smoke test for the Mage.Bench harness. Uses stock
 * ComputerPlayer on both seats so it needs no Ollama and runs in CI.
 * Exercises the harness, not the LLM path.
 */
public class BenchSmokeTest {

    private BenchConfig config() {
        return BenchConfig.parse(new String[]{
                "--games=3",
                "--seed=4242",
                "--playerA=base",
                "--playerB=base",
                "--turnCap=15",
                "--deckDir=."
        });
    }

    @Test
    public void threeGamesAllTerminate() {
        BenchConfig config = config();
        for (int i = 0; i < config.games; i++) {
            GameResult result = BenchGame.run(config, i, config.baseSeed + i, i % 2 == 1);
            assertNotNull("game " + i + " produced no result", result);
            assertNotNull("game " + i + " has no termination", result.termination);
            assertTrue("game " + i + " failed: " + result.errorMessage,
                    result.termination != Termination.ERROR);
            assertTrue("game " + i + " played no turns", result.turns > 0);
        }
    }

    @Test
    public void sameSeedProducesSameOutcome() {
        BenchConfig config = config();
        GameResult first = BenchGame.run(config, 0, 777L, false);
        GameResult second = BenchGame.run(config, 0, 777L, false);
        assertEquals(first.termination, second.termination);
        assertEquals(first.winner, second.winner);
        assertEquals(first.turns, second.turns);
    }
}
```

- [ ] **Step 3: Run the smoke test**

Run: `mvn -q -pl Mage.Tests -am test -Dtest=BenchSmokeTest`
Expected: PASS, 2 tests. Note `--deckDir=.` because surefire runs with `Mage.Tests` as the working directory, and the `.dck` files live there.

- [ ] **Step 4: Run the full Mage.Bench test suite**

Run: `mvn -q -pl Mage.Bench test`
Expected: PASS — all unit tests from Tasks 1-8.

- [ ] **Step 5: Commit**

```bash
git add Mage.Tests/pom.xml Mage.Tests/src/test/java/org/mage/test/bench/BenchSmokeTest.java
git commit -m "bench: add harness smoke test"
```

- [ ] **Step 6: Run the control benchmark — this is the acceptance gate**

Build once, then run stock-vs-stock. This validates the harness is unbiased **before** any Kanna number is trusted (spec success criterion 2).

```bash
mvn -q -DskipTests -pl Mage.Bench -am install
cd Mage.Tests && mvn -q exec:java \
  -Dexec.mainClass=mage.bench.BenchRunner \
  -Dexec.classpathScope=test \
  -Dexec.args="--games=20 --playerA=cp7 --playerB=cp7 --turnCap=30 --out=control.jsonl"
```

Expected: 20 games complete; the reported win rate's 95% confidence interval **contains 50%**. If it does not, the harness is biased — stop and investigate seat swapping before running any Kanna comparison.

- [ ] **Step 7: Record the control result**

Append a short "Baseline runs" section to `docs/superpowers/specs/2026-08-09-kanna-benchmark-harness-design.md` with the date, the exact command, and the observed win rate and interval. Commit:

```bash
git add docs/superpowers/specs/2026-08-09-kanna-benchmark-harness-design.md
git commit -m "bench: record stock-vs-stock control baseline"
```

---

## Self-Review

**Spec coverage:** All eight components in the spec map to tasks — `BenchConfig` (1), `PlayerFactory` (2), `BenchGame` (6), `GameResult` (3), `BenchMetrics` (3), `ResultWriter` (4), `SummaryReporter` (5), `BenchRunner` (8). Spec error handling maps to Task 6 (per-game catch, turn cap) and Task 8 (Ollama preflight, deck load failure via `BenchGame.loadDeckList`). Spec testing section maps to Tasks 1-5 unit tests and Task 9's smoke test. All five success criteria are exercised: criteria 1 and 2 by Task 9 Step 6, criterion 3 by `sameSeedProducesSameOutcome`, criterion 4 by `dataSurvivesWithoutClose_becauseEachAppendFlushes`, criterion 5 by the smoke test using `base` players.

**Deviation from spec, recorded:** the spec described `BenchMetrics` as a plain sink Kanna reports into. Implementation required splitting it into a `DecisionMetrics` interface declared *inside* the Kanna module, because `Mage.Bench` depends on `mage-player-ai-kanna` and the reverse dependency would be a Maven cycle. Task 7 documents this. The spec's intent — Kanna no-ops without a sink and stays usable on the live server — is preserved.

**Placeholder scan:** no TBD/TODO markers; every code step contains complete compilable content; no "similar to Task N" references.

**Type consistency:** `LlmStats` field names (`calls`, `totalLatencyMs`, `p50LatencyMs`, `p95LatencyMs`, `invalidToolCalls`) are consistent across Tasks 3, 4, 5. `GameResult` constructor argument order is identical in Tasks 3, 4, 5, 6. `Termination` values `WIN`/`CAP`/`ERROR` used consistently. `BenchConfig` field names match between Task 1's definition and their uses in Tasks 6, 8, 9. `SummaryReporter.summarize(List, String)` and `format(RunSummary, String, String)` signatures match between Task 5 and Task 8.

**Known risk flagged for the implementer:** Task 6 Step 2 deliberately ends on a failing compile, resolved by Task 7. These two tasks must be executed and committed together. If your workflow requires every task to end green, merge Tasks 6 and 7 into one.
