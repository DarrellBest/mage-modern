package mage.bench;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Fixtures below are real {@code onGameLog} message text captured from an actual bench game
 * (cp7 vs cp7, Kairi.dck vs Krenko-R-EDH.dck, --gameType=commander), read verbatim off the
 * console with {@code PrintGameLogsDataCollector} enabled via
 * {@code DataCollectorServices.init(true, false)} -- not hand-guessed. The only edits are
 * stripping the log4j timestamp/logger boilerplate and the "[LOG][GAME] Tn.Mn: " prefix that
 * {@code PrintGameLogsDataCollector} itself adds (that prefix is not part of the raw message
 * {@code onGameLog} actually receives -- see {@code GameImpl.informPlayers}).
 * <p>
 * The one HTML-wrapped fixture reconstructs the real raw message shape per
 * {@code GameLog.getColoredObjectIdName}/{@code getColoredPlayerName} (confirmed by reading
 * {@code mage.util.GameLog}), since the captured lines above were already stripped to plain
 * text by the reference collector before printing -- this fixture instead proves the
 * collector's own Jsoup stripping handles the real tag shape, not just plain text.
 *
 * @author Darrell Best
 */
public class CardPlayCollectorTest {

    private static final String SEAT1_HTML_CAST =
            "<font color='#20B2AA' object_id='11111111-1111-1111-1111-111111111111'>Seat1</font>"
                    + " casts <font color='#B0C4DE' object_id='22222222-2222-2222-2222-222222222222'>"
                    + "Blade of Selves</font> [2cc] from hand";

    @Test
    public void realCapturedLine_simpleCastFromHand_isAttributedToCaster() {
        CardPlayCollector collector = new CardPlayCollector();
        onLog(collector, "Seat1 casts Blade of Selves [2cc] from hand");

        Map<String, Integer> seat1 = collector.snapshot().get("Seat1");
        assertEquals(Integer.valueOf(1), seat1.get("Blade of Selves"));
    }

    @Test
    public void realHtmlWrappedMessage_isStrippedAndParsedTheSameWay() {
        CardPlayCollector collector = new CardPlayCollector();
        onLog(collector, SEAT1_HTML_CAST);

        Map<String, Integer> seat1 = collector.snapshot().get("Seat1");
        assertEquals(Integer.valueOf(1), seat1.get("Blade of Selves"));
    }

    @Test
    public void realCapturedLine_castFromExileZone_isCounted() {
        CardPlayCollector collector = new CardPlayCollector();
        onLog(collector, "Seat1 casts Coldsteel Heart [f44] from exile zone");

        assertEquals(Integer.valueOf(1), collector.snapshot().get("Seat1").get("Coldsteel Heart"));
    }

    @Test
    public void realCapturedLine_castFromCommandZone_isCounted() {
        CardPlayCollector collector = new CardPlayCollector();
        onLog(collector, "Seat1 casts Kairi, the Swirling Sky [18a] from command");
        onLog(collector, "Seat2 casts Krenko, Mob Boss [c7c] from command");

        assertEquals(Integer.valueOf(1), collector.snapshot().get("Seat1").get("Kairi, the Swirling Sky"));
        assertEquals(Integer.valueOf(1), collector.snapshot().get("Seat2").get("Krenko, Mob Boss"));
    }

    @Test
    public void realCapturedLine_castWithTargeting_cardNameStopsBeforeTargetInfo() {
        CardPlayCollector collector = new CardPlayCollector();
        onLog(collector, "Seat2 casts Tibalt's Trickery [0c1] targeting Roaming Throne [41c] from hand");

        Map<String, Integer> seat2 = collector.snapshot().get("Seat2");
        assertEquals(Integer.valueOf(1), seat2.get("Tibalt's Trickery"));
        // the targeted card must not itself be recorded as cast
        assertFalse(seat2.containsKey("Roaming Throne"));
    }

    @Test
    public void realCapturedLine_castWithModeAndTargeting_cardNameStopsBeforeModeInfo() {
        CardPlayCollector collector = new CardPlayCollector();
        onLog(collector, "Seat1 casts See Double [8e1] (mode 2) targeting Kairi, the Swirling Sky [18a] from hand");

        assertEquals(Integer.valueOf(1), collector.snapshot().get("Seat1").get("See Double"));
    }

    @Test
    public void realCapturedLine_castTargetingAPlayer_isStillCountedForCaster() {
        CardPlayCollector collector = new CardPlayCollector();
        onLog(collector, "Seat1 casts Blue Sun's Zenith [ae2] targeting Seat1 from hand");

        assertEquals(Integer.valueOf(1), collector.snapshot().get("Seat1").get("Blue Sun's Zenith"));
    }

    @Test
    public void repeatedCastsOfSameCard_accumulateCount() {
        CardPlayCollector collector = new CardPlayCollector();
        onLog(collector, "Seat2 casts Swiftfoot Boots [8e5] from hand");
        onLog(collector, "Seat2 casts Swiftfoot Boots [8e5] from hand");
        onLog(collector, "Seat2 casts Swiftfoot Boots [8e5] from hand");

        assertEquals(Integer.valueOf(3), collector.snapshot().get("Seat2").get("Swiftfoot Boots"));
    }

    // --- negative fixtures: real captured log lines that must NOT register as a cast ---

    @Test
    public void realCapturedLine_landDrop_isNotCounted() {
        CardPlayCollector collector = new CardPlayCollector();
        onLog(collector, "Seat1 puts Nykthos, Shrine to Nyx [6e9] from hand onto the Battlefield");

        assertTrue(collector.snapshot().isEmpty());
    }

    @Test
    public void realCapturedLine_abilityActivation_isNotCounted() {
        CardPlayCollector collector = new CardPlayCollector();
        onLog(collector, "Seat1 activates: search your library for an Island or Swamp card, "
                + "put it onto the battlefield, then shuffle. from Polluted Delta [d9f]");

        assertTrue(collector.snapshot().isEmpty());
    }

    @Test
    public void realCapturedLine_triggerReminderTextContainingTheWordCasts_isNotCounted() {
        // Rhystic Study's own trigger condition text contains the literal word "casts" with no
        // bracketed object id anywhere after it in the line -- this is the exact ambiguity the
        // task called out as a risk, confirmed present in real captured output.
        CardPlayCollector collector = new CardPlayCollector();
        onLog(collector, "Seat1 - Ability triggers: Rhystic Study [ac0] - Whenever an opponent "
                + "casts a spell, you may draw a card unless that player pays {1}.");

        assertTrue(collector.snapshot().isEmpty());
    }

    @Test
    public void realCapturedLine_stackDescriptionContainingTheWordCasts_isNotCounted() {
        CardPlayCollector collector = new CardPlayCollector();
        onLog(collector, "Stack push: 2 (top: stack ability (Whenever an opponent casts a spell, "
                + "you may draw a card unless that player pays {1}.))");

        assertTrue(collector.snapshot().isEmpty());
    }

    private static void onLog(CardPlayCollector collector, String message) {
        collector.onGameLog(null, message);
    }
}
