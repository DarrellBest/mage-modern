package mage.bench;

import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.cards.repository.CardScanner;
import mage.collectors.DataCollectorServices;
import mage.constants.MultiplayerAttackOption;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.CommanderDuel;
import mage.game.CommanderDuelMatch;
import mage.game.CommanderFreeForAll;
import mage.game.CommanderFreeForAllMatch;
import mage.game.Game;
import mage.game.GameOptions;
import mage.game.TwoPlayerDuel;
import mage.game.TwoPlayerMatch;
import mage.game.match.Match;
import mage.game.match.MatchOptions;
import mage.game.mulligan.MulliganType;
import mage.player.ai.commander.score.CommanderEvalParams;
import mage.players.Player;
import mage.util.RandomUtil;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Logger logger = Logger.getLogger(BenchGame.class);

    private static final Map<String, DeckCardLists> DECK_CACHE = new HashMap<>();

    // Sanity floor for the loaded maindeck size, so a deck that failed to import most of
    // its list (bad card names, encoding issues, etc.) fails loudly instead of silently
    // being benchmarked as if it were a legal, functioning deck -- the exact failure this
    // harness exists to avoid. Two-player constructed decks run ~60 cards; Commander
    // maindecks run 99 (the 100th card, the commander, lives in the deck's sideboard and
    // is loaded separately -- see addPlayer below).
    private static final int MIN_MAINDECK_TWOPLAYER = 40;
    private static final int MIN_MAINDECK_COMMANDER = 90;

    // How often the --maxGameSeconds watchdog looks at the clock. Turns take seconds to
    // minutes, so 1s costs nothing and still bounds the overshoot to well under a turn.
    private static final long WATCHDOG_POLL_MS = 1000L;

    // Mirrors DckDeckImporter's own card-line grammar (group 1 = "SB:" prefix, group 2 =
    // declared quantity), so parseDeclaredCounts below counts a line as a card line under
    // exactly the same rule the importer used to decide whether to try resolving it -- not a
    // second, potentially-drifting definition of "card line".
    private static final Pattern DECK_LINE_PATTERN =
            Pattern.compile("(SB:)?\\s*(\\d+)\\s*\\[([^]:]+):([^]:]+)\\]\\s*(.*)\\s*$");

    private BenchGame() {
    }

    // Literal player names BenchGame always constructs its two seats with, regardless of which
    // config key or deck is on that seat for a given (possibly seat-swapped) game -- these are
    // also exactly the labels CardPlayCollector will see as the "casts" prefix in game logs.
    private static final String SEAT1_LABEL = "Seat1";
    private static final String SEAT2_LABEL = "Seat2";

    /**
     * DARRELLBEST-FORK: who sits where for ONE game, after the odd-game seat swap.
     * <p>
     * <b>Why a type instead of the four ternaries this replaced.</b> A matchup has two SIDES, A and
     * B, and a side is a triple: a player key, a deck, and (now) a set of evaluator weights. The
     * swap reorders the two sides between seat 1 and seat 2; it must never reorder the contents of a
     * side. When each element was computed by its own {@code seatSwapped ? b : a} ternary, "params
     * follow their deck" was an invariant maintained by three expressions agreeing with each other
     * -- and a single inverted ternary (or a fourth element added later without one) would attribute
     * half the games of every sweep to the wrong parameter set. Nothing would look wrong: both
     * bots would still play, the run would still finish, and the win rate would be a blend of the
     * two parameter sets rather than a comparison of them.
     * <p>
     * Binding the triple into one object and swapping the OBJECTS makes that bug unrepresentable:
     * there is exactly one ternary ({@link #assignSeats}), and it moves whole sides. Everything
     * downstream -- deck loading, player construction, card-play attribution, winner keys -- reads
     * from the same Seat, so a deck and the weights it was benchmarked with cannot come apart.
     */
    static final class SeatPlan {

        final Seat seat1;
        final Seat seat2;
        /**
         * DARRELLBEST-FORK: every seat in seat order. For a duel this is exactly
         * {@code [seat1, seat2]} and the two fields above are the same objects, so the duel path is
         * unchanged; a Free For All simply has more entries.
         */
        final List<Seat> seats;

        SeatPlan(Seat seat1, Seat seat2) {
            this(Arrays.asList(seat1, seat2));
        }

        SeatPlan(List<Seat> seats) {
            this.seats = seats;
            this.seat1 = seats.get(0);
            this.seat2 = seats.get(1);
        }

        /** 1-based seat index of side A, which is the one seat the bot under test occupies. */
        int seatOfSideA() {
            for (int i = 0; i < seats.size(); i++) {
                if ("A".equals(seats.get(i).side)) {
                    return i + 1;
                }
            }
            return 0;
        }

        /** One side of the matchup, and everything that defines it. */
        static final class Seat {
            /** "A" or "B": which config side this is, independent of which seat it is sitting in. */
            final String side;
            final String playerKey;
            final String deck;
            /** Tuned weights for this side, or null for the bot's stock evaluation. */
            final CommanderEvalParams evalParams;

            Seat(String side, String playerKey, String deck, CommanderEvalParams evalParams) {
                this.side = side;
                this.playerKey = playerKey;
                this.deck = deck;
                this.evalParams = evalParams;
            }
        }
    }

    /**
     * Builds the two sides, then chooses which sits in which seat. The only place the seat swap is
     * expressed.
     */
    static SeatPlan assignSeats(BenchConfig config, boolean seatSwapped) {
        SeatPlan.Seat sideA = new SeatPlan.Seat("A", config.playerA, config.deckA,
                EvalParamsLoader.paramsFor(config.paramsA));
        if (config.isFreeForAll()) {
            return assignPodSeats(config, sideA, seatSwapped);
        }
        SeatPlan.Seat sideB = new SeatPlan.Seat("B", config.playerB, config.deckB,
                EvalParamsLoader.paramsFor(config.paramsB));
        return seatSwapped ? new SeatPlan(sideB, sideA) : new SeatPlan(sideA, sideB);
    }

    /**
     * DARRELLBEST-FORK: one side A seat and N-1 side B seats, rotated so side A does not always
     * play first.
     * <p>
     * Turn order is a real edge in a pod -- the first player unfolds a whole turn before anyone can
     * answer, and the last player faces three developed boards -- so leaving side A parked in seat 1
     * would measure the seat as much as the bot. The duel's seat swap exists for the same reason;
     * this is that idea with more than two chairs. The rotation is driven by the caller's
     * alternating flag rather than a random draw so that a run of games covers the seats evenly.
     * <p>
     * Deck order is preserved relative to the seats: {@code deckList.get(0)} is side A's deck and
     * the rest stay in their given order among the side B seats, so rotating the pod never pairs a
     * deck with another seat's weights.
     */
    private static SeatPlan assignPodSeats(BenchConfig config, SeatPlan.Seat sideA, boolean seatSwapped) {
        List<SeatPlan.Seat> ordered = new ArrayList<>();
        ordered.add(sideA);
        for (int i = 1; i < config.deckList.size(); i++) {
            ordered.add(new SeatPlan.Seat("B", config.playerB, config.deckList.get(i),
                    EvalParamsLoader.paramsFor(config.paramsB)));
        }
        int rotation = seatSwapped ? ordered.size() / 2 : 0;
        List<SeatPlan.Seat> rotated = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            rotated.add(ordered.get((i - rotation + ordered.size()) % ordered.size()));
        }
        return new SeatPlan(rotated);
    }

    public static GameResult run(BenchConfig config, int gameIndex, long seed, boolean seatSwapped) {
        return run(config, gameIndex, seed, seatSwapped, null);
    }

    /**
     * @param cardTracker when non-null (i.e. {@code --trackCards} was given), a per-game
     *                    {@link CardPlayCollector} is registered with {@code DataCollectorServices}
     *                    for the duration of this one game and its results folded into
     *                    {@code cardTracker}, keyed by the deck that was actually on each seat for
     *                    this game (not the raw seat label -- see {@link CardPlayTracker}'s own
     *                    javadoc for why that distinction matters on seat-swapped games). When
     *                    null, no collector is created or registered at all: an ordinary run pays
     *                    zero extra cost for this feature.
     */
    public static GameResult run(BenchConfig config, int gameIndex, long seed, boolean seatSwapped,
                                 CardPlayTracker cardTracker) {
        // placeholder value in case CardScanner.scan() itself throws below, so the catch
        // block always has a valid startNanos to compute wallMs from -- reset immediately
        // after a successful scan, before any real game work begins
        long startNanos = System.nanoTime();
        // seat 1 / seat 2 assignment, swapped on odd games so play/draw advantage cancels; computed
        // up front (not inside the try below) purely from config/seatSwapped, so it's available to
        // the catch block too for best-effort card-play recording on a game that errored out
        // partway through
        SeatPlan seats = assignSeats(config, seatSwapped);
        Game game = null;
        CardPlayCollector cardPlayCollector = cardTracker == null ? null : new CardPlayCollector();
        if (cardPlayCollector != null) {
            DataCollectorServices.register(cardPlayCollector);
        }
        try {
            // DARRELLBEST-FORK: nothing else in this standalone-process code path scans
            // Mage.Sets into the card DB the way CardTestPlayerAPIImpl does for the JUnit
            // test base classes. Without this, every deck import fails to find any card
            // (including basic lands) because CardRepository is empty. CardScanner.scan()
            // is idempotent (guarded by its own static "scanned" flag), so calling it once
            // per process is cheap after the first -- but its first call does a one-time
            // multi-second card-DB build, so startNanos is reset right after it returns:
            // folding that build cost into game 0's wallTimeMs would corrupt every
            // turn-time percentile SummaryReporter computes from it.
            CardScanner.scan();
            startNanos = System.nanoTime();

            RandomUtil.setSeed(seed);

            Match match;
            if (config.isFreeForAll()) {
                // DARRELLBEST-FORK: a real commander pod. RangeOfInfluence.ALL, not ONE: with
                // ONE a player can only see and target its immediate neighbours, which is not
                // how a commander pod is played and would make the multiplayer attack-splitting
                // this game type exists to measure untestable -- the bot would be unable to
                // choose the third opponent at all.
                game = new CommanderFreeForAll(MultiplayerAttackOption.MULTIPLE, RangeOfInfluence.ALL,
                        MulliganType.GAME_DEFAULT.getMulligan(0), 40, 7);
                match = new CommanderFreeForAllMatch(
                        new MatchOptions("bench match", "bench game type", true));
            } else if (BenchConfig.GAME_TYPE_COMMANDER.equals(config.gameType)) {
                // Commander life/hand size per the test framework's own Commander base
                // (CardTestCommanderDuelBase): 40 life, 7 cards. CommanderDuel's
                // constructor takes no minimum-deck-size argument -- its super
                // (GameCommanderImpl) hardcodes 100, unlike TwoPlayerDuel below.
                game = new CommanderDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                        MulliganType.GAME_DEFAULT.getMulligan(0), 40, 7);
                match = new CommanderDuelMatch(
                        new MatchOptions("bench match", "bench game type", false));
            } else {
                game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                        MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
                match = new TwoPlayerMatch(
                        new MatchOptions("bench match", "bench game type", false));
            }

            Player[] players = addPlayers(game, match, config, seats);
            Player seat1 = players[0];
            Player seat2 = players[1];

            GameOptions options = new GameOptions();
            options.testMode = false;
            // DARRELLBEST-FORK: turnCap <= 0 means "play to a real result". GameImpl only enforces
            // a cap when stopOnTurn is non-null, and this field is an Integer for exactly that
            // reason, but the bench previously always autoboxed an int so "no cap" was inexpressible.
            //
            // Why it matters: a capped game that hits the cap is not a data point, it is a discarded
            // one. The Kairi v1-vs-v2 comparison lost 15 of 30 games to a 30-turn cap and became
            // uninterpretable -- control decks simply do not resolve that fast. Uncapped runs are
            // slower per game but every game counts.
            //
            // The risk this accepts: nothing then bounds a game that never progresses, and two such
            // loops were observed today (a free sacrifice outlet re-triggering the search, and a
            // separate stall inside a single search). One looping game will consume whatever
            // wall-clock budget the caller allows and cost every game that would have followed it,
            // so callers should keep an outer timeout rather than relying on the engine to stop.
            options.stopOnTurn = config.turnCap > 0 ? config.turnCap : null;
            options.stopAtStep = PhaseStep.UNTAP;
            game.setGameOptions(options);

            // Wall-clock bound on this one game (see startWatchdog). Nothing starts and no
            // thread is created when --maxGameSeconds is 0, which is the default.
            AtomicBoolean budgetExceeded = new AtomicBoolean(false);
            Thread watchdog = config.maxGameSeconds > 0
                    ? startWatchdog(game, options, config.maxGameSeconds, budgetExceeded, gameIndex)
                    : null;
            try {
                game.start(seat1.getId());
            } finally {
                if (watchdog != null) {
                    watchdog.interrupt();
                }
            }

            int turns = game.getState().getTurnNum();
            String winnerKey = null;
            int winnerSeat = 0;
            // DARRELLBEST-FORK: scan every seat rather than the two duel seats, so a pod's
            // winner is found wherever it sits.
            for (int i = 0; i < players.length; i++) {
                if (players[i].hasWon()) {
                    winnerKey = seats.seats.get(i).playerKey;
                    winnerSeat = i + 1;
                    break;
                }
            }

            Termination termination;
            if (winnerKey != null) {
                termination = Termination.WIN;
            } else if (budgetExceeded.get()) {
                // Checked before CAP: when a game both blew its wall-clock budget and reached
                // the turn cap, the budget is the tighter and more actionable of the two, and
                // "this game was too slow to finish" is the fact worth surfacing.
                termination = Termination.TIMEOUT;
            } else if (config.turnCap > 0 && turns >= config.turnCap) {
                // engine treats the turn cap as a draw; distinguish that from a genuine
                // mutual-loss draw by checking whether the cap was actually reached
                termination = Termination.CAP;
            } else {
                termination = Termination.DRAW;
            }

            recordCardPlays(cardTracker, cardPlayCollector, seats);

            long wallMs = (System.nanoTime() - startNanos) / 1_000_000L;
            return new GameResult(gameIndex, seed, winnerKey, winnerSeat, turns, wallMs,
                    termination, null, seatSwapped, seats.seatOfSideA(), seats.seats.size(),
                    EvalParamsLoader.describe(config.paramsA), EvalParamsLoader.describe(config.paramsB));

        } catch (Throwable e) {
            // best-effort: whatever was cast before the error is still useful signal for a
            // never-cast list, so record it here too rather than only on the success path
            recordCardPlays(cardTracker, cardPlayCollector, seats);

            long wallMs = (System.nanoTime() - startNanos) / 1_000_000L;
            int turns = game == null ? 0 : game.getState().getTurnNum();
            String message = e.getClass().getSimpleName() + ": " + e.getMessage();
            // full stack trace, not just the message: an ERROR row with only "NullPointerException:
            // null" leaves nothing to debug once the game state that caused it is gone
            logger.error("Bench game " + gameIndex + " (seed " + seed + ") failed: " + message, e);
            // paramsA/paramsB describe the CONFIG, not this game's outcome, so an errored game still
            // records which weights it was asked to run with -- that is what makes an ERROR row
            // attributable to a sweep leg after the fact.
            return new GameResult(gameIndex, seed, null, 0, turns, wallMs,
                    Termination.ERROR, message, seatSwapped,
                    describeQuietly(config.paramsA), describeQuietly(config.paramsB));
        } finally {
            if (cardPlayCollector != null) {
                DataCollectorServices.unregister(cardPlayCollector);
            }
        }
    }

    /**
     * Starts a daemon thread that bounds one game in WALL-CLOCK time, and returns it so the
     * caller can interrupt it once the game is over.
     * <p>
     * How it stops the game, and why it works: {@code GameImpl.setGameOptions} stores the
     * {@link GameOptions} instance BY REFERENCE (it does not copy), so the object handed to
     * the engine before {@code game.start()} is the same one the engine keeps consulting
     * while the game runs. {@code GameImpl.checkStopOnTurnOption()} runs at the start of
     * every turn and, when {@code stopOnTurn} matches the current turn and
     * {@code stopAtStep == UNTAP}, sets {@code winnerId = null} and ends the game -- exactly
     * the "call it a draw and move on" semantics wanted here. So the watchdog needs to do
     * nothing more than write one field.
     * <p>
     * Two details that make or break it:
     * <ul>
     * <li>That engine check is an EXACT match ({@code stopOnTurn.equals(turnNum)}), not
     *     {@code >=}, so the value written must be a FUTURE turn ({@code current + 1}).
     *     Writing the current or a past turn number would never fire.</li>
     * <li>Turns here can take minutes, so a single write can still be missed if the turn
     *     advanced between the read and the write. The loop therefore re-arms whenever the
     *     game has moved strictly PAST the turn it armed. It must not re-arm at or before
     *     that turn: pushing the target forward every poll while the game is still
     *     approaching it would walk the stop point ahead of the game forever.</li>
     * </ul>
     * This thread deliberately touches no engine mutator -- it only reads
     * {@code getState().getTurnNum()} and writes a plain field on an options object. Engine
     * code that must run on the game thread guards itself with
     * {@code ThreadUtils.ensureRunInGameThread()} (reachable only from
     * {@code GameImpl.checkConcede} and {@code MatchImpl}, neither of which is on this path),
     * and BenchRunner runs games on the real "main" thread precisely to satisfy it.
     * <p>
     * WHAT THIS DOES NOT CATCH, and it matters: the stop is only ever evaluated at a turn
     * boundary (UNTAP). A game wedged INSIDE a single turn -- one enormous AI search that
     * never returns, the no-progress loops this bot has hit before -- never reaches the next
     * UNTAP, so this watchdog will never fire for it and the game runs forever. An outer
     * process-level {@code timeout} on the whole worker remains necessary; this option
     * shortens slow games, it does not make a run unconditionally safe.
     */
    private static Thread startWatchdog(Game game, GameOptions options, int maxSeconds,
                                        AtomicBoolean budgetExceeded, int gameIndex) {
        long deadlineNanos = System.nanoTime() + maxSeconds * 1_000_000_000L;
        Thread watchdog = new Thread(() -> {
            int armedTurn = -1;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(WATCHDOG_POLL_MS);
                    if (System.nanoTime() < deadlineNanos) {
                        continue;
                    }
                    int currentTurn = game.getState().getTurnNum();
                    if (armedTurn >= 0 && currentTurn <= armedTurn) {
                        continue; // already armed and the game has not passed it yet
                    }
                    armedTurn = currentTurn + 1;
                    // The engine reads this field from the game thread without synchronization
                    // and GameOptions.stopOnTurn is not volatile, so this hands over on the
                    // JMM's good graces rather than a guarantee. In practice the game thread
                    // re-reads it through a megamorphic call chain it cannot hoist the field
                    // across, and the re-arm above covers a miss anyway. Making it a guarantee
                    // would mean editing GameOptions in the engine module; not worth it for a
                    // benchmark-only knob.
                    options.stopOnTurn = armedTurn;
                    if (budgetExceeded.compareAndSet(false, true)) {
                        logger.info("Bench game " + gameIndex + " exceeded its " + maxSeconds
                                + "s budget on turn " + currentTurn + "; stopping at turn " + armedTurn);
                    }
                }
            } catch (InterruptedException e) {
                // normal: the game finished and the caller interrupted us
                Thread.currentThread().interrupt();
            }
        }, "bench-game-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        return watchdog;
    }

    /**
     * {@link EvalParamsLoader#describe} that never throws, for the error path only.
     * <p>
     * The normal path cannot fail here (the params were already resolved by {@link #assignSeats}
     * before the game started, and the loader caches), but the error path must not turn a game's
     * recorded failure into a second, different exception thrown out of {@code run()} -- the
     * original error is the one worth keeping.
     */
    private static String describeQuietly(String paramsPath) {
        try {
            return EvalParamsLoader.describe(paramsPath);
        } catch (RuntimeException e) {
            return "unresolved:" + paramsPath;
        }
    }

    /**
     * Folds one finished (or errored-out) game's per-seat cast counts into {@code cardTracker},
     * translating the raw "Seat1"/"Seat2" log labels to the deck that was actually on each seat
     * for this game. No-op when card tracking isn't enabled for this run.
     */
    /**
     * DARRELLBEST-FORK: takes the whole seat plan rather than two deck names, so a pod attributes
     * every seat's plays to that seat's deck. Attributing only seats 1 and 2 would have quietly
     * dropped half a four-player game's card data.
     */
    private static void recordCardPlays(CardPlayTracker cardTracker, CardPlayCollector cardPlayCollector,
                                        SeatPlan seats) {
        if (cardTracker == null || cardPlayCollector == null) {
            return;
        }
        try {
            Map<String, Map<String, Integer>> snapshot = cardPlayCollector.snapshot();
            for (int i = 0; i < seats.seats.size(); i++) {
                cardTracker.recordGame(seats.seats.get(i).deck,
                        snapshot.getOrDefault("Seat" + (i + 1), Collections.emptyMap()));
            }
        } catch (RuntimeException e) {
            // optional instrumentation must never break or double-fail a benchmark game
            logger.warn("Bench game card-play recording failed: " + e, e);
        }
    }

    /**
     * Builds both seats, in seat order, from one {@link SeatPlan}.
     * <p>
     * Package-private and returning the real {@link Player} objects so that BenchGameTest can drive
     * the exact call {@code run()} makes and inspect what each seat actually received -- see
     * {@link SeatPlan} for why that test is the one guarding the seat swap.
     *
     * @return {@code [seat1, seat2]}
     */
    static Player[] addPlayers(Game game, Match match, BenchConfig config, SeatPlan seats)
            throws Exception {
        Player[] players = new Player[seats.seats.size()];
        for (int i = 0; i < players.length; i++) {
            players[i] = addPlayer(game, match, config, seats.seats.get(i), "Seat" + (i + 1));
        }
        return players;
    }

    private static Player addPlayer(Game game, Match match, BenchConfig config,
                                    SeatPlan.Seat seat, String name) throws Exception {
        String typeKey = seat.playerKey;
        String deckName = seat.deck;
        // The player key, the deck and the weights all come off the SAME Seat object, so this
        // method cannot pair one side's deck with the other side's weights however the seats were
        // assigned -- see SeatPlan.
        Player player = PlayerFactory.create(typeKey, name, RangeOfInfluence.ONE, config.skill,
                seat.evalParams);

        Deck deck = Deck.load(loadDeckList(config.deckDir, deckName), false, false);
        int minMaindeck = BenchConfig.GAME_TYPE_COMMANDER.equals(config.gameType)
                ? MIN_MAINDECK_COMMANDER : MIN_MAINDECK_TWOPLAYER;
        if (deck.getMaindeckCards().size() < minMaindeck) {
            throw new IllegalArgumentException("Deck '" + deckName + "' loaded only "
                    + deck.getMaindeckCards().size() + " cards (minimum " + minMaindeck
                    + " for gameType '" + config.gameType + "')");
        }

        game.loadCards(deck.getCards(), player.getId());
        game.loadCards(deck.getSideboard(), player.getId());
        game.addPlayer(player, deck);
        // mandatory: MatchImpl.addPlayer sets the MatchPlayer, and SimulatedPlayerMCTS
        // dereferences it during rollouts
        match.addPlayer(player, deck);
        return player;
    }

    static DeckCardLists loadDeckList(String deckDir, String deckName) {
        String key = deckDir + "/" + deckName;
        DeckCardLists cached = DECK_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        File file = new File(deckDir, deckName);
        if (!file.exists()) {
            throw new IllegalArgumentException("Deck file not found: " + file.getAbsolutePath());
        }
        // 3-arg overload with a real StringBuilder: the 2-arg overload silently drops
        // unresolvable lines into a throwaway buffer instead of failing, so a deck with bad
        // lines would load short and every game in the run would silently use the wrong deck.
        // saveAutoFixedFile=false because true opens the source .dck file for writing --
        // a benchmark run must never mutate repo-tracked deck files as a side effect of
        // reading them.
        StringBuilder errors = new StringBuilder();
        DeckCardLists list = DeckImporter.importDeckFromFile(file.getAbsolutePath(), errors, false);

        // The importer also emits a message for benign auto-fixes -- e.g. a Secret Lair
        // collector number missing from this build's card database gets silently swapped for
        // another printing of the same card, and the game plays fine either way. Rejecting on
        // "the importer said anything at all" (the old behavior here) treated that exactly
        // like a card that failed to resolve at all, which is wrong: it blocked the vast
        // majority of real decks over messages that were never fatal. The invariant that
        // actually matters is whether every declared card made it into the parsed list, so
        // compare the file's own declared quantities against what DeckCardLists actually
        // holds instead of keying off message presence.
        int[] declaredCounts = parseDeclaredCounts(file);
        int declaredMain = declaredCounts[0];
        int declaredSideboard = declaredCounts[1];
        int actualMain = list.getCards().size();
        int actualSideboard = list.getSideboard().size();
        if (actualMain != declaredMain || actualSideboard != declaredSideboard) {
            throw new IllegalArgumentException("Deck '" + deckName + "' failed to import: expected "
                    + declaredMain + " maindeck / " + declaredSideboard + " sideboard cards from the file, "
                    + "but only " + actualMain + " maindeck / " + actualSideboard + " sideboard cards actually "
                    + "loaded (some card(s) could not be resolved at all). Importer messages: " + errors);
        }
        if (errors.length() > 0) {
            // counts matched, so nothing here is fatal -- typically printing substitutions
            // (e.g. a Secret Lair collector number resolved to a different printing of the
            // same card) -- but still worth surfacing so the cause of any behavior difference
            // between the substituted printing and the original is visible if ever needed.
            logger.info("Deck '" + deckName + "' imported with " + declaredMain + " maindeck / "
                    + declaredSideboard + " sideboard cards (all accounted for) after auto-fixes: " + errors);
        }

        DECK_CACHE.put(key, list);
        return list;
    }

    /**
     * Sums the leading quantity declared on each card line of a .dck file, split into
     * maindeck and sideboard totals. A line counts as a card line under exactly the grammar
     * DckDeckImporter itself uses to recognize one (DECK_LINE_PATTERN); everything else --
     * NAME:, AUTHOR:, LAYOUT, blank lines, comments -- contributes to neither total, which is
     * just another way of saying those lines declare zero cards.
     */
    private static int[] parseDeclaredCounts(File file) {
        int mainTotal = 0;
        int sideboardTotal = 0;
        try (Scanner scanner = new Scanner(file)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                Matcher m = DECK_LINE_PATTERN.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                int count = Integer.parseInt(m.group(2));
                if ("SB:".equals(m.group(1))) {
                    sideboardTotal += count;
                } else {
                    mainTotal += count;
                }
            }
        } catch (FileNotFoundException e) {
            // loadDeckList already checked file.exists() immediately before calling this, so
            // this can only happen from a racing external deletion; surface it the same way
            // the earlier existence check would have.
            throw new IllegalArgumentException("Deck file not found: " + file.getAbsolutePath(), e);
        }
        return new int[]{mainTotal, sideboardTotal};
    }
}
