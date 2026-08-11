package mage.bench;

import mage.cards.Card;
import mage.cards.decks.Deck;
import mage.cards.repository.CardScanner;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.game.CommanderDuel;
import mage.game.CommanderDuelMatch;
import mage.game.Game;
import mage.game.match.Match;
import mage.game.match.MatchOptions;
import mage.game.mulligan.MulliganType;
import mage.player.ai.commander.ComputerPlayer6;
import mage.player.ai.commander.score.CommanderEvalParams;
import mage.players.Player;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * PROOF that {@code --paramsA} reaches the seat playing {@code --deckA}, in both the swapped and
 * the unswapped case.
 * <p>
 * This is the failure the whole feature is most exposed to and the one that would be invisible in
 * the output: the harness swaps seats on odd games, and if the evaluator weights did not swap with
 * their deck, half of every sweep would attribute results to the wrong parameter set. The win rate
 * would still be a number, the run would still finish, and nothing would look wrong -- it would
 * simply be a blend of the two parameter sets rather than a comparison of them.
 * <p>
 * <b>How it is proved, rather than assumed.</b> These tests drive the real construction path --
 * {@code BenchGame.assignSeats} followed by {@code BenchGame.addPlayers}, the exact pair of calls
 * {@code BenchGame.run} makes -- against a real {@link CommanderDuel} and real 100-card decks, then
 * inspect the ACTUAL {@link ComputerPlayer6} objects that came back, asking each one for the weights
 * it will score positions with ({@code getEvalParams()}) and the deck it was registered with. No
 * mirror of the swap logic is recomputed here to compare against; the assertions name the concrete
 * pairing (Krenko plays with side A's weights, Kairi with side B's) that must hold.
 * <p>
 * A game is deliberately never STARTED. Games are not reproducible from a seed (see PlayerFactory's
 * javadoc), take ~58s each, and would prove nothing here anyway: the question is what each seat was
 * built with, which is settled the moment the players exist.
 *
 * @author Darrell Best
 */
public class BenchGameSeatSwapTest {

    private static final String BENCHDECKS_DIR = ".." + File.separator
            + "Mage.Tests" + File.separator + "benchdecks";

    /** Distinctive marker values, so a weight can be traced to the side that specified it. */
    private static final int SIDE_A_MARKER = 111;
    private static final int SIDE_B_MARKER = 222;

    private static final String DECK_A = "Krenko-R-EDH.dck";
    private static final String DECK_B = "Kairi.dck";
    private static final String COMMANDER_A = "Krenko, Mob Boss";
    private static final String COMMANDER_B = "Kairi, the Swirling Sky";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void scanCards() {
        CardScanner.scan();
    }

    private String writeParams(String name, int handCardScore) throws Exception {
        File file = new File(folder.getRoot(), name);
        Files.write(file.toPath(), ("handCardScore=" + handCardScore + "\n").getBytes(StandardCharsets.UTF_8));
        return file.getAbsolutePath();
    }

    private BenchConfig config(String paramsA, String paramsB) {
        return BenchConfig.parse(new String[]{
                "--gameType=commander",
                "--playerA=commander",
                "--playerB=commander",
                "--deckDir=" + BENCHDECKS_DIR,
                "--deckA=" + DECK_A,
                "--deckB=" + DECK_B,
                "--skill=2",
                "--paramsA=" + paramsA,
                "--paramsB=" + paramsB
        });
    }

    /** Builds both seats exactly as BenchGame.run does, for the given swap state. */
    private Player[] buildSeats(BenchConfig config, boolean seatSwapped, Match match) throws Exception {
        Game game = new CommanderDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                MulliganType.GAME_DEFAULT.getMulligan(0), 40, 7);
        return BenchGame.addPlayers(game, match, config, BenchGame.assignSeats(config, seatSwapped),
                new BenchMetrics());
    }

    private static int handCardScoreOf(Player player) {
        assertTrue("bench 'commander' bot must be a ComputerPlayer6, was " + player.getClass(),
                player instanceof ComputerPlayer6);
        return ((ComputerPlayer6) player).getEvalParams().getHandCardScore();
    }

    /** @return the commander (the deck's single sideboard card) the given seat was registered with */
    private static String commanderOf(Match match, Player player) {
        Deck deck = match.getPlayer(player.getId()).getDeck();
        assertNotNull("seat should have been registered with a deck", deck);
        assertEquals("commander decks carry exactly one sideboard card, the commander",
                1, deck.getSideboard().size());
        for (Card card : deck.getSideboard()) {
            return card.getName();
        }
        throw new AssertionError("unreachable");
    }

    @Test
    public void unswapped_sideAWeightsSitWithSideADeck() throws Exception {
        BenchConfig config = config(writeParams("a.properties", SIDE_A_MARKER),
                writeParams("b.properties", SIDE_B_MARKER));
        Match match = new CommanderDuelMatch(new MatchOptions("t", "t", false));

        Player[] players = buildSeats(config, false, match);

        // seat 1 is side A: deckA and paramsA together
        assertEquals("Seat1", players[0].getName());
        assertEquals(COMMANDER_A, commanderOf(match, players[0]));
        assertEquals(SIDE_A_MARKER, handCardScoreOf(players[0]));

        // seat 2 is side B: deckB and paramsB together
        assertEquals("Seat2", players[1].getName());
        assertEquals(COMMANDER_B, commanderOf(match, players[1]));
        assertEquals(SIDE_B_MARKER, handCardScoreOf(players[1]));
    }

    @Test
    public void swapped_sideAWeightsFollowSideADeckToTheOtherSeat() throws Exception {
        BenchConfig config = config(writeParams("a.properties", SIDE_A_MARKER),
                writeParams("b.properties", SIDE_B_MARKER));
        Match match = new CommanderDuelMatch(new MatchOptions("t", "t", false));

        Player[] players = buildSeats(config, true, match);

        // the SEATS swapped: side B now plays first. Its deck and its weights moved together.
        assertEquals("Seat1", players[0].getName());
        assertEquals(COMMANDER_B, commanderOf(match, players[0]));
        assertEquals("side B's deck must be scored with side B's weights, not side A's",
                SIDE_B_MARKER, handCardScoreOf(players[0]));

        assertEquals("Seat2", players[1].getName());
        assertEquals(COMMANDER_A, commanderOf(match, players[1]));
        assertEquals("side A's deck must be scored with side A's weights, not side B's",
                SIDE_A_MARKER, handCardScoreOf(players[1]));
    }

    @Test
    public void acrossBothSwapStates_deckAndWeightsAreNeverCrossed() throws Exception {
        // The invariant stated directly, over both games of a swap pair: whichever seat holds
        // Krenko is running side A's weights and whichever holds Kairi is running side B's. This is
        // the assertion that a single inverted ternary would break.
        BenchConfig config = config(writeParams("a.properties", SIDE_A_MARKER),
                writeParams("b.properties", SIDE_B_MARKER));

        for (boolean seatSwapped : new boolean[]{false, true}) {
            Match match = new CommanderDuelMatch(new MatchOptions("t", "t", false));
            Player[] players = buildSeats(config, seatSwapped, match);
            for (Player player : players) {
                String commander = commanderOf(match, player);
                int marker = handCardScoreOf(player);
                if (COMMANDER_A.equals(commander)) {
                    assertEquals("seatSwapped=" + seatSwapped + ": deckA's seat must hold paramsA",
                            SIDE_A_MARKER, marker);
                } else if (COMMANDER_B.equals(commander)) {
                    assertEquals("seatSwapped=" + seatSwapped + ": deckB's seat must hold paramsB",
                            SIDE_B_MARKER, marker);
                } else {
                    throw new AssertionError("unexpected deck on a seat: " + commander);
                }
            }
        }
    }

    @Test
    public void seatPlanCarriesTheSideLabelThroughTheSwap() throws Exception {
        // The same invariant one level down, at the single point where the swap is expressed: the
        // Seat object that moves between seats carries key, deck and params as one unit.
        BenchConfig config = config(writeParams("a.properties", SIDE_A_MARKER),
                writeParams("b.properties", SIDE_B_MARKER));

        BenchGame.SeatPlan straight = BenchGame.assignSeats(config, false);
        assertEquals("A", straight.seat1.side);
        assertEquals(DECK_A, straight.seat1.deck);
        assertEquals(SIDE_A_MARKER, straight.seat1.evalParams.getHandCardScore());
        assertEquals("B", straight.seat2.side);
        assertEquals(DECK_B, straight.seat2.deck);
        assertEquals(SIDE_B_MARKER, straight.seat2.evalParams.getHandCardScore());

        BenchGame.SeatPlan swapped = BenchGame.assignSeats(config, true);
        assertEquals("B", swapped.seat1.side);
        assertEquals(DECK_B, swapped.seat1.deck);
        assertEquals(SIDE_B_MARKER, swapped.seat1.evalParams.getHandCardScore());
        assertEquals("A", swapped.seat2.side);
        assertEquals(DECK_A, swapped.seat2.deck);
        assertEquals(SIDE_A_MARKER, swapped.seat2.evalParams.getHandCardScore());

        // The weights object that side A's seat holds is literally the same instance either way --
        // the loader caches per path, so this is the params file itself following side A across the
        // swap, not two equal-looking copies.
        assertSame(straight.seat1.evalParams, swapped.seat2.evalParams);
        assertSame(straight.seat2.evalParams, swapped.seat1.evalParams);
    }

    @Test
    public void withNoParamsFiles_bothSeatsGetStockWeights() throws Exception {
        // the default path must stay exactly as it was: no params anywhere, null all the way down
        BenchConfig config = BenchConfig.parse(new String[]{
                "--gameType=commander",
                "--playerA=commander",
                "--playerB=commander",
                "--deckDir=" + BENCHDECKS_DIR,
                "--deckA=" + DECK_A,
                "--deckB=" + DECK_B,
                "--skill=2"
        });

        BenchGame.SeatPlan seats = BenchGame.assignSeats(config, false);
        assertNull(seats.seat1.evalParams);
        assertNull(seats.seat2.evalParams);

        Match match = new CommanderDuelMatch(new MatchOptions("t", "t", false));
        Player[] players = buildSeats(config, false, match);
        assertEquals(CommanderEvalParams.DEFAULT.getHandCardScore(),
                handCardScoreOf(players[0]));
        assertEquals(CommanderEvalParams.DEFAULT.getHandCardScore(),
                handCardScoreOf(players[1]));
    }

    @Test
    public void onlyOneSideTuned_leavesTheOtherOnStockWeights() throws Exception {
        // the common sweep shape: one tuned candidate against the stock baseline
        BenchConfig config = BenchConfig.parse(new String[]{
                "--gameType=commander",
                "--playerA=commander",
                "--playerB=commander",
                "--deckDir=" + BENCHDECKS_DIR,
                "--deckA=" + DECK_A,
                "--deckB=" + DECK_B,
                "--skill=2",
                "--paramsA=" + writeParams("a.properties", SIDE_A_MARKER)
        });
        Match match = new CommanderDuelMatch(new MatchOptions("t", "t", false));

        Player[] players = buildSeats(config, true, match);

        // swapped: the tuned side A sits in seat 2, with its own deck
        assertEquals(COMMANDER_A, commanderOf(match, players[1]));
        assertEquals(SIDE_A_MARKER, handCardScoreOf(players[1]));
        assertEquals(COMMANDER_B, commanderOf(match, players[0]));
        assertEquals(CommanderEvalParams.DEFAULT.getHandCardScore(),
                handCardScoreOf(players[0]));
    }

    @Test
    public void everyGameRecordsWhichWeightsItRanWith() throws Exception {
        // a results file that does not say which parameters produced it is a trap once several
        // sweeps are compared, so the descriptor is on the row itself, not only in the run log
        String paramsA = writeParams("a.properties", SIDE_A_MARKER);
        BenchConfig config = BenchConfig.parse(new String[]{
                "--playerA=commander",
                "--playerB=commander",
                "--deckDir=" + BENCHDECKS_DIR,
                "--deckA=fixture-too-small.dck",
                "--deckB=fixture-too-small.dck",
                "--paramsA=" + paramsA
        });

        // this game errors out on the deck-size check; the params must still be recorded, otherwise
        // an ERROR row could not be attributed to a sweep leg after the fact
        GameResult result = BenchGame.run(config, 0, config.baseSeed, false);

        assertEquals(Termination.ERROR, result.termination);
        assertTrue("row should name side A's params file: " + result.paramsA,
                result.paramsA.contains("a.properties"));
        assertTrue("row should carry a hash of side A's resolved values: " + result.paramsA,
                result.paramsA.contains("#"));
        assertEquals("side B ran stock weights", "default", result.paramsB);
    }
}
