package org.mage.test.mulligan;

import mage.cards.Card;
import mage.cards.CardSetInfo;
import mage.cards.basiclands.Forest;
import mage.cards.c.CrawWurm;
import mage.cards.decks.Deck;
import mage.cards.l.LlanowarElves;
import mage.cards.s.Squire;
import mage.cards.s.SolRing;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.constants.Rarity;
import mage.game.Game;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import mage.player.ai.ComputerPlayer;
import mage.player.ai.commander.ComputerPlayerCommander;
import mage.player.ai.commander.score.CommanderEvalParams;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * DARRELLBEST-FORK: exercises {@link ComputerPlayer#chooseMulligan} -- the deck- and hand-aware
 * keep decision (pro-gameplay principles 22-24, docs/ai/pro-gameplay-principles.md) -- directly
 * against real hand/library state.
 * <p>
 * Nothing else in the test suite reaches this code path. The other tests in this package
 * ({@code LondonMulliganTest} and its siblings) script every mulligan decision through a
 * {@code StubPlayer}, which never calls {@code ComputerPlayer.chooseMulligan}. Every ordinary
 * card test builds its players through {@code CardTestPlayerAPIImpl}, which calls
 * {@code setTestMode(true)} on them specifically so this method's first line short-circuits to
 * "always keep" (see the {@code isTestMode()} guard) -- that is what lets thousands of unrelated
 * card tests ignore mulligans entirely, but it also means none of them exercise this logic. So a
 * real {@link ComputerPlayer}, with {@code isTestMode} left at its default {@code false}, is
 * built here and driven directly.
 * <p>
 * Hand and library are placed by hand rather than dealt, so each case is an exact, deterministic
 * (lands in hand, lands in deck, curve) triple with no dependency on shuffle/draw order.
 */
public class ComputerPlayerMulliganTest {

    private ComputerPlayer player;

    /**
     * {1}{W} 1/2 vanilla, mana value 2 -- stands in for "an early, castable spell". The owner id
     * passed in is a throwaway: {@link #setUpGame} loads every card through
     * {@code Game.loadCards}, which unconditionally overwrites each card's owner to the real
     * player, so nothing here needs to know that player's id yet.
     */
    private static Card cheapSpell(UUID throwawayOwnerId) {
        return new Squire(throwawayOwnerId, new CardSetInfo("Squire", "TEST", "2", Rarity.COMMON));
    }

    /**
     * {4}{G}{G} 6/4 vanilla, mana value 6 -- stands in for "a spell no early hand can cast".
     */
    private static Card expensiveSpell(UUID throwawayOwnerId) {
        return new CrawWurm(throwawayOwnerId, new CardSetInfo("Craw Wurm", "TEST", "3", Rarity.COMMON));
    }

    private static Card land(UUID throwawayOwnerId) {
        return new Forest(throwawayOwnerId, new CardSetInfo("Forest", "TEST", "1", Rarity.LAND));
    }

    /**
     * {1} artifact, Tap: Add {C}{C} -- a mana ROCK. Stands in for "a RAMP source" for the owner's
     * 2-land keep rule ({@link ComputerPlayer#chooseMulligan}'s {@code hasRampSource} check).
     */
    private static Card manaRock(UUID throwawayOwnerId) {
        return new SolRing(throwawayOwnerId, new CardSetInfo("Sol Ring", "TEST", "4", Rarity.UNCOMMON));
    }

    /**
     * {G} 1/1, Tap: Add {G} -- a mana DORK. The other RAMP shape {@code hasRampSource} accepts.
     */
    private static Card manaDork(UUID throwawayOwnerId) {
        return new LlanowarElves(throwawayOwnerId, new CardSetInfo("Llanowar Elves", "TEST", "5", Rarity.COMMON));
    }

    /**
     * Builds a fresh {@link ComputerPlayer} (real AI logic, {@code isTestMode} left at its
     * default {@code false} -- see the class javadoc), puts {@code handCards} directly into its
     * hand and {@code restOfLibrary} into its library, registers both in a new {@link Game}, and
     * returns that game. {@link #player} is left pointed at the built player for the caller to
     * assert on.
     */
    private Game setUpGame(List<Card> handCards, List<Card> restOfLibrary) {
        player = new ComputerPlayer("mulligan-test", RangeOfInfluence.ONE);

        Game game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                MulliganType.LONDON.getMulligan(0), 60, 20, 7);

        List<Card> allCards = new ArrayList<>(handCards);
        allCards.addAll(restOfLibrary);
        game.loadCards(new HashSet<>(allCards), player.getId());

        // useDeck() (called by addPlayer) puts every maindeck card into the library and leaves
        // hand empty -- exactly the "dealt but not drawn" state we want before hand-picking which
        // cards actually end up in hand.
        Deck deck = new Deck();
        deck.getCards().addAll(allCards);
        game.addPlayer(player, deck);

        for (Card card : handCards) {
            player.getLibrary().remove(card.getId(), game);
            player.getHand().add(card);
        }

        assertEquals("test setup sanity: hand size", handCards.size(), player.getHand().size());
        assertEquals("test setup sanity: library size", restOfLibrary.size(), player.getLibrary().size());
        return game;
    }

    // ------------------------------------------------------------------------------------------
    // 24-land, 60-card constructed deck (land ratio 0.4; expected lands in 7 = 2.8; keep window
    // works out to [2, 4] lands -- see the worked math in the chooseMulligan javadoc).
    // ------------------------------------------------------------------------------------------

    @Test
    public void keeps3LandGoodCurveHandFrom24LandSixtyCardDeck() {
        UUID id = UUID.randomUUID();
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < 3; i++) hand.add(land(id));
        for (int i = 0; i < 4; i++) hand.add(cheapSpell(id));
        List<Card> rest = new ArrayList<>();
        for (int i = 0; i < 21; i++) rest.add(land(id));        // 24 deck lands total, 3 already in hand
        for (int i = 0; i < 32; i++) rest.add(cheapSpell(id));  // 36 deck spells total, 4 already in hand

        Game game = setUpGame(hand, rest);
        assertFalse("3 lands in 7 (window [2,4] for a 24/60 deck) with castable spells should be kept",
                player.chooseMulligan(game));
    }

    @Test
    public void mulligansLandScrewedHandFrom24LandSixtyCardDeck() {
        UUID id = UUID.randomUUID();
        List<Card> hand = new ArrayList<>();
        hand.add(land(id));
        for (int i = 0; i < 6; i++) hand.add(cheapSpell(id));
        List<Card> rest = new ArrayList<>();
        for (int i = 0; i < 23; i++) rest.add(land(id));
        for (int i = 0; i < 30; i++) rest.add(cheapSpell(id));

        Game game = setUpGame(hand, rest);
        assertTrue("1 land in 7 is below the [2,4] window for a 24/60 deck -- must mulligan",
                player.chooseMulligan(game));
    }

    @Test
    public void mulligansFloodedHandFrom24LandSixtyCardDeck() {
        UUID id = UUID.randomUUID();
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < 6; i++) hand.add(land(id));
        hand.add(cheapSpell(id));
        List<Card> rest = new ArrayList<>();
        for (int i = 0; i < 18; i++) rest.add(land(id));
        for (int i = 0; i < 35; i++) rest.add(cheapSpell(id));

        Game game = setUpGame(hand, rest);
        assertTrue("6 lands in 7 is above the [2,4] window for a 24/60 deck -- must mulligan "
                        + "(the OLD fixed rule allowed up to hand.size()-2 = 5 lands too, so this "
                        + "particular case agrees with the old behavior; see the no-plan case below "
                        + "for where the two rules actually diverge)",
                player.chooseMulligan(game));
    }

    @Test
    public void mulligansEnoughLandsButNoEarlyPlayFrom24LandSixtyCardDeck() {
        UUID id = UUID.randomUUID();
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < 3; i++) hand.add(land(id));
        for (int i = 0; i < 4; i++) hand.add(expensiveSpell(id));
        List<Card> rest = new ArrayList<>();
        for (int i = 0; i < 21; i++) rest.add(land(id));
        for (int i = 0; i < 32; i++) rest.add(cheapSpell(id));

        Game game = setUpGame(hand, rest);
        // Land count alone (3, inside [2,4]) is fine -- this is the case the OLD rule got wrong.
        // The old fixed check (lands >= 2 && lands <= hand.size() - 2) looks only at land count
        // and would have kept this hand; principle 24's castability check is what makes it differ.
        assertTrue("3 lands is enough by count, but all 4 spells cost 6 -- zero early plays, must "
                        + "mulligan even though land count alone looks fine",
                player.chooseMulligan(game));
    }

    // ------------------------------------------------------------------------------------------
    // 36-land, 99-card Commander deck (land ratio ~0.364; expected lands in 7 ~ 2.55; window
    // [2, 4] -- the same window as the 24/60 deck above. That's expected, not a bug: both are
    // "normal" ~36-40% land ratios and land in the same integer window at hand size 7. The next
    // case (40/99) is chosen specifically to show a ratio difference that DOES cross a window
    // boundary.
    // ------------------------------------------------------------------------------------------

    @Test
    public void keepsGoodHandFrom36LandNinetyNineCardCommanderDeck() {
        // DARRELLBEST-FORK: this hand is exactly 2 lands, which the owner's hard floor rule now
        // gates on RAMP rather than on the deck's ratio window (see chooseMulligan's javadoc) --
        // one of the 5 nonland cards is a Sol Ring instead of a cheap spell so this case still
        // demonstrates a KEEP. The plain "2 lands, no ramp" shape that this test used to be is
        // now its own dedicated mulligan case below (mulligansTwoLandHandWithNoRampRegardlessOfRatio).
        UUID id = UUID.randomUUID();
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < 2; i++) hand.add(land(id));
        hand.add(manaRock(id));
        for (int i = 0; i < 4; i++) hand.add(cheapSpell(id));
        List<Card> rest = new ArrayList<>();
        for (int i = 0; i < 34; i++) rest.add(land(id));        // 36 deck lands total
        for (int i = 0; i < 58; i++) rest.add(cheapSpell(id));  // 99 - 36 - 5 = 58 more spells

        Game game = setUpGame(hand, rest);
        assertFalse("2 lands in 7 (window [2,4] for a 36/99 deck) with castable spells AND a mana "
                        + "rock (ramp) should be kept -- the owner's 2-land hard rule requires ramp, "
                        + "which this hand has",
                player.chooseMulligan(game));
    }

    // ------------------------------------------------------------------------------------------
    // DARRELLBEST-FORK: the owner's hard floor rules ("it shouldnt keep a 1 land hand and only a
    // 2 land hand if it has ramp of some kind including mana rocks"), layered on top of the ratio
    // window above. These apply regardless of deck ratio -- the 36/99 deck used below is the same
    // one as the KEEP case just above specifically to isolate ramp presence as the only variable.
    // ------------------------------------------------------------------------------------------

    @Test
    public void mulligansOneLandHandEvenWithRamp() {
        // 1 land, 7 cards, WITH a mana rock -- must still mulligan. The hard 0/1-land floor has
        // no ramp exception; only the 2-land rule does.
        UUID id = UUID.randomUUID();
        List<Card> hand = new ArrayList<>();
        hand.add(land(id));
        hand.add(manaRock(id));
        for (int i = 0; i < 5; i++) hand.add(cheapSpell(id));
        List<Card> rest = new ArrayList<>();
        for (int i = 0; i < 35; i++) rest.add(land(id));        // 36 deck lands total, 1 already in hand
        for (int i = 0; i < 57; i++) rest.add(cheapSpell(id));  // 92-card library: 35 lands + 57 spells

        Game game = setUpGame(hand, rest);
        assertTrue("1 land in 7 must ALWAYS mulligan (owner's hard floor), even with a mana rock "
                        + "in hand -- ramp only ever saves a 2-land hand, never a 1-land one",
                player.chooseMulligan(game));
    }

    @Test
    public void keepsTwoLandHandWithManaDorkRamp() {
        // 2 lands, WITH a mana dork (Llanowar Elves, not a rock) -- must keep. Confirms the ramp
        // check accepts dorks too, not just artifacts.
        UUID id = UUID.randomUUID();
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < 2; i++) hand.add(land(id));
        hand.add(manaDork(id));
        for (int i = 0; i < 4; i++) hand.add(cheapSpell(id));
        List<Card> rest = new ArrayList<>();
        for (int i = 0; i < 34; i++) rest.add(land(id));
        for (int i = 0; i < 58; i++) rest.add(cheapSpell(id));

        Game game = setUpGame(hand, rest);
        assertFalse("2 lands with a mana dork (Llanowar Elves) in hand should be kept",
                player.chooseMulligan(game));
    }

    @Test
    public void mulligansTwoLandHandWithNoRampRegardlessOfRatio() {
        // Same 36/99 deck and same 2-land count as the KEEP case above, no ramp anywhere in
        // hand -- must mulligan even though this land count sits inside the deck's own ratio
        // window ([2,4] for this deck, same math as keepsGoodHandFrom36LandNinetyNineCardCommanderDeck).
        UUID id = UUID.randomUUID();
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < 2; i++) hand.add(land(id));
        for (int i = 0; i < 5; i++) hand.add(cheapSpell(id));
        List<Card> rest = new ArrayList<>();
        for (int i = 0; i < 34; i++) rest.add(land(id));
        for (int i = 0; i < 58; i++) rest.add(cheapSpell(id));

        Game game = setUpGame(hand, rest);
        assertTrue("2 lands with NO ramp must mulligan even though the land count alone sits "
                        + "inside this deck's ratio window -- the owner's hard rule overrules the "
                        + "window for exactly-2-land hands",
                player.chooseMulligan(game));
    }

    // ------------------------------------------------------------------------------------------
    // 40-land, 99-card Commander deck (land ratio ~0.404; expected lands in 7 ~ 2.83; window
    // [2, 4]). Chosen specifically to show the new rule being STRICTER than the old fixed one:
    // the old rule kept any hand with <= hand.size()-2 = 5 lands, no matter the deck. The
    // ratio-aware window here rejects a 5-land hand from this deck as too land-heavy.
    // ------------------------------------------------------------------------------------------

    @Test
    public void mulligansFiveLandHandFrom40LandNinetyNineCardCommanderDeck() {
        UUID id = UUID.randomUUID();
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < 5; i++) hand.add(land(id));
        for (int i = 0; i < 2; i++) hand.add(cheapSpell(id));
        List<Card> rest = new ArrayList<>();
        for (int i = 0; i < 35; i++) rest.add(land(id));        // 40 deck lands total
        for (int i = 0; i < 57; i++) rest.add(cheapSpell(id));  // 99 - 40 - 2 = 57 more spells

        Game game = setUpGame(hand, rest);
        assertTrue("5 lands in 7 is above the [2,4] window for a 40/99 deck -- the OLD fixed rule "
                        + "(keep iff lands <= hand.size()-2 = 5) would have KEPT this hand; the "
                        + "deck-aware window correctly rejects it as too land-heavy for this deck's ratio",
                player.chooseMulligan(game));
    }

    // ------------------------------------------------------------------------------------------
    // Principle 22 floor, and the ratio math staying sane at an extreme (all-spell) deck.
    // ------------------------------------------------------------------------------------------

    @Test
    public void neverMulligansBelowFiveCardsRegardlessOfContent() {
        UUID id = UUID.randomUUID();
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < 5; i++) hand.add(cheapSpell(id)); // zero lands -- would fail every check below
        List<Card> rest = new ArrayList<>();
        for (int i = 0; i < 20; i++) rest.add(land(id));

        Game game = setUpGame(hand, rest);
        assertFalse("hand.size() == 5 must always keep, even with zero lands (principle 22 floor, "
                        + "unchanged from before this fix -- the hand.size() < 6 guard returns before "
                        + "any of the new ratio/castability logic runs)",
                player.chooseMulligan(game));
    }

    @Test
    public void handlesAllSpellNoLandDeckSanely() {
        UUID id = UUID.randomUUID();
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < 7; i++) hand.add(cheapSpell(id)); // deck has ZERO land cards anywhere
        List<Card> rest = new ArrayList<>(); // empty library too

        Game game = setUpGame(hand, rest);
        // Ratio here is 0/7 = 0, the most extreme case reachable through the public chooseMulligan
        // entry point: hand.size() >= 6 is already enforced by the guard above the ratio math, so
        // library.size() + hand.size() can never actually be 0 through this method (the
        // Math.max(1, deckTotal) divide-by-zero guard in the implementation is defensive for that
        // invariant moving in the future, not exercised by this specific case). DARRELLBEST-FORK:
        // this now short-circuits on the owner's hard 0/1-land floor before the ratio window is
        // even computed (see chooseMulligan's javadoc) -- what this case checks is simply that a
        // 0-land hand against a landless deck still resolves to a clean mulligan instead of
        // throwing (e.g. on the deckTotal divide-by-zero guard, defensive as it is).
        assertTrue("a hand with zero lands, from a deck that (in this constructed case) has no "
                        + "land cards at all, must still resolve to a clean decision",
                player.chooseMulligan(game));
    }

    // ------------------------------------------------------------------------------------------
    // DARRELLBEST-FORK: the actual LIVE class, not just the shared base it inherits from.
    //
    // Every case above drives a plain mage.player.ai.ComputerPlayer, which is correct for pinning
    // the deck-aware rule itself, but the server's "Computer - commander" player type is
    // mage.player.ai.commander.ComputerPlayerCommander -- three subclasses down
    // (ComputerPlayerCommander -> ComputerPlayer7 -> ComputerPlayer6, all in the commander
    // package) -- and ComputerPlayer6 now overrides chooseMulligan itself (to attach a MULLIGAN
    // audit record; see its javadoc for why zero such records existed across 28 sampled live
    // games despite the deck-aware rule already being able to mulligan a bad hand). This is the
    // only test in the suite that calls chooseMulligan on that actual class rather than on the
    // shared base, so it is what would fail if a future edit to the override changed the routing
    // or the decision instead of only adding visibility to it.
    // ------------------------------------------------------------------------------------------

    /**
     * Builds a real {@link ComputerPlayerCommander} (same construction path
     * {@code ComputerPlayer6.chooseMulligan}'s own javadoc describes as the live server's bot),
     * puts {@code handCards}/{@code restOfLibrary} into it the same way {@link #setUpGame} does
     * for the base class, and returns the game. {@link #player} is left pointed at the built
     * player, exactly like {@link #setUpGame} -- the field's declared type ({@code ComputerPlayer})
     * is a supertype of {@code ComputerPlayerCommander}, so the same field serves both helpers.
     */
    private Game setUpCommanderGame(List<Card> handCards, List<Card> restOfLibrary) {
        ComputerPlayerCommander commanderPlayer = new ComputerPlayerCommander(
                "commander-mulligan-test", RangeOfInfluence.ONE, 6, CommanderEvalParams.TUNED);
        player = commanderPlayer;

        Game game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                MulliganType.LONDON.getMulligan(0), 60, 20, 7);

        List<Card> allCards = new ArrayList<>(handCards);
        allCards.addAll(restOfLibrary);
        game.loadCards(new HashSet<>(allCards), commanderPlayer.getId());

        Deck deck = new Deck();
        deck.getCards().addAll(allCards);
        game.addPlayer(commanderPlayer, deck);

        for (Card card : handCards) {
            commanderPlayer.getLibrary().remove(card.getId(), game);
            commanderPlayer.getHand().add(card);
        }

        assertEquals("test setup sanity: hand size", handCards.size(), commanderPlayer.getHand().size());
        assertEquals("test setup sanity: library size", restOfLibrary.size(), commanderPlayer.getLibrary().size());
        return game;
    }

    /**
     * The exact live symptom this issue was opened against: a 0-land 7-card hand kept, with
     * nothing in the log to say why. Run through the real commander class, from a 40/99-deck
     * ratio (window [2,4], same as {@link #mulligansFiveLandHandFrom40LandNinetyNineCardCommanderDeck}).
     */
    @Test
    public void commanderBotMulligansAZeroLandSevenCardHand() {
        UUID id = UUID.randomUUID();
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < 7; i++) hand.add(cheapSpell(id)); // 0 lands
        List<Card> rest = new ArrayList<>();
        for (int i = 0; i < 40; i++) rest.add(land(id));        // 40 deck lands total
        for (int i = 0; i < 52; i++) rest.add(cheapSpell(id));  // 99 - 40 - 7 = 52 more spells

        Game game = setUpCommanderGame(hand, rest);
        assertTrue("0 lands in 7 is below the [2,4] window for a 40/99 deck -- the live "
                        + "'Computer - commander' class must mulligan this hand, not keep it "
                        + "(this is the exact hand reported kept live)",
                player.chooseMulligan(game));
    }

    /**
     * The negative control for the test above: a good hand from the same commander bot class
     * must still be KEPT, so the override added purely for audit logging is proven bit-identical
     * to the inherited decision in both directions, not merely "always says mulligan".
     */
    @Test
    public void commanderBotKeepsAGoodHandFromTheSameDeck() {
        UUID id = UUID.randomUUID();
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < 3; i++) hand.add(land(id));
        for (int i = 0; i < 4; i++) hand.add(cheapSpell(id));
        List<Card> rest = new ArrayList<>();
        for (int i = 0; i < 37; i++) rest.add(land(id));        // 40 deck lands total, 3 already in hand
        for (int i = 0; i < 55; i++) rest.add(cheapSpell(id));  // 92-card library: 37 lands + 55 spells

        Game game = setUpCommanderGame(hand, rest);
        assertFalse("3 lands in 7 (window [2,4] for a 40/99 deck) with castable spells should be "
                        + "kept by the live 'Computer - commander' class",
                player.chooseMulligan(game));
    }
}
