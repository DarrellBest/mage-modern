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
