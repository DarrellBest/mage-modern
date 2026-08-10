package mage.bench;

import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.cards.repository.CardScanner;
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
import org.apache.log4j.Logger;

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

    private static final Logger logger = Logger.getLogger(BenchGame.class);

    private static final Map<String, DeckCardLists> DECK_CACHE = new HashMap<>();

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

    private static Player addPlayer(Game game, TwoPlayerMatch match, BenchConfig config,
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
        // 3-arg overload with a real StringBuilder: the 2-arg overload silently drops
        // unresolvable lines into a throwaway buffer instead of failing, so a deck with bad
        // lines would load short and every game in the run would silently use the wrong deck.
        // saveAutoFixedFile=false because true opens the source .dck file for writing --
        // a benchmark run must never mutate repo-tracked deck files as a side effect of
        // reading them.
        StringBuilder errors = new StringBuilder();
        DeckCardLists list = DeckImporter.importDeckFromFile(file.getAbsolutePath(), errors, false);
        if (errors.length() > 0) {
            throw new IllegalArgumentException("Deck '" + deckName + "' failed to import: " + errors);
        }
        DECK_CACHE.put(key, list);
        return list;
    }
}
