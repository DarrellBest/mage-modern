package mage.bench;

import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.cards.repository.CardScanner;
import mage.constants.MultiplayerAttackOption;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.CommanderDuel;
import mage.game.CommanderDuelMatch;
import mage.game.Game;
import mage.game.GameOptions;
import mage.game.TwoPlayerDuel;
import mage.game.TwoPlayerMatch;
import mage.game.match.Match;
import mage.game.match.MatchOptions;
import mage.game.mulligan.MulliganType;
import mage.player.ai.kanna.ComputerPlayerKanna;
import mage.players.Player;
import mage.util.RandomUtil;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
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

    // Mirrors DckDeckImporter's own card-line grammar (group 1 = "SB:" prefix, group 2 =
    // declared quantity), so parseDeclaredCounts below counts a line as a card line under
    // exactly the same rule the importer used to decide whether to try resolving it -- not a
    // second, potentially-drifting definition of "card line".
    private static final Pattern DECK_LINE_PATTERN =
            Pattern.compile("(SB:)?\\s*(\\d+)\\s*\\[([^]:]+):([^]:]+)\\]\\s*(.*)\\s*$");

    private BenchGame() {
    }

    public static GameResult run(BenchConfig config, int gameIndex, long seed, boolean seatSwapped) {
        // placeholder value in case CardScanner.scan() itself throws below, so the catch
        // block always has a valid startNanos to compute wallMs from -- reset immediately
        // after a successful scan, before any real game work begins
        long startNanos = System.nanoTime();
        // shared by both seats: correct for a mismatched matchup (kanna vs cp7), but a
        // kanna-vs-kanna run merges both players' LLM stats into one BenchMetrics instance
        BenchMetrics metrics = new BenchMetrics();
        Game game = null;
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
            if (BenchConfig.GAME_TYPE_COMMANDER.equals(config.gameType)) {
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
            int winnerSeat = 0;
            if (seat1.hasWon()) {
                winnerKey = seat1Key;
                winnerSeat = 1;
            } else if (seat2.hasWon()) {
                winnerKey = seat2Key;
                winnerSeat = 2;
            }

            Termination termination;
            if (winnerKey != null) {
                termination = Termination.WIN;
            } else if (turns >= config.turnCap) {
                // engine treats the turn cap as a draw; distinguish that from a genuine
                // mutual-loss draw by checking whether the cap was actually reached
                termination = Termination.CAP;
            } else {
                termination = Termination.DRAW;
            }

            long wallMs = (System.nanoTime() - startNanos) / 1_000_000L;
            return new GameResult(gameIndex, seed, winnerKey, winnerSeat, turns, wallMs,
                    termination, null, seatSwapped, metrics.snapshot());

        } catch (Throwable e) {
            long wallMs = (System.nanoTime() - startNanos) / 1_000_000L;
            int turns = game == null ? 0 : game.getState().getTurnNum();
            String message = e.getClass().getSimpleName() + ": " + e.getMessage();
            // full stack trace, not just the message: an ERROR row with only "NullPointerException:
            // null" leaves nothing to debug once the game state that caused it is gone
            logger.error("Bench game " + gameIndex + " (seed " + seed + ") failed: " + message, e);
            return new GameResult(gameIndex, seed, null, 0, turns, wallMs,
                    Termination.ERROR, message, seatSwapped, metrics.snapshot());
        }
    }

    private static Player addPlayer(Game game, Match match, BenchConfig config,
                                    String typeKey, String name, String deckName,
                                    BenchMetrics metrics) throws Exception {
        Player player = PlayerFactory.create(typeKey, name, RangeOfInfluence.ONE, config.skill);
        if (player instanceof ComputerPlayerKanna) {
            ComputerPlayerKanna kanna = (ComputerPlayerKanna) player;
            kanna.setBenchMetrics(metrics);
            kanna.setModel(config.model);
            // DARRELLBEST-FORK (keep on merge/rebase from upstream): pass the bare base
            // URL. OllamaClient (Mage.Player.AI.Kanna) owns the "/api/chat" suffix itself
            // now, so appending it again here doubled the path to ".../api/chat/api/chat"
            // and 404'd every call -- silent, because Kanna's decision methods catch
            // Throwable and fall back to heuristics, producing a complete-looking game in
            // which the model was never actually consulted.
            kanna.setOllamaUrl(config.ollamaUrl);
        }

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
