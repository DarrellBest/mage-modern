package mage.bench;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * DARRELLBEST-FORK: PROOF that a Free For All pod seats exactly one side A and keeps every deck
 * with its own side.
 * <p>
 * The duel's seat swap has {@link BenchGameSeatSwapTest} guarding it because getting it wrong
 * silently blends two parameter sets into one win rate rather than comparing them. A pod can go
 * wrong in the same silent way and in two extra ones: side A could end up in more than one seat
 * (the bot would then be measured partly against itself), and the rotation could pair a deck with
 * another seat's weights. All three produce a run that finishes, logs nothing unusual, and reports
 * a number that means something other than what it claims.
 */
public class BenchGamePodSeatTest {

    private static BenchConfig podConfig(String... decks) {
        return BenchConfig.parse(new String[]{
                "--gameType=commanderffa",
                "--playerA=commander",
                "--playerB=cp7",
                "--decks=" + String.join(",", decks),
        });
    }

    private static List<String> decksOf(BenchGame.SeatPlan plan) {
        return plan.seats.stream().map(s -> s.deck).collect(java.util.stream.Collectors.toList());
    }

    @Test
    public void podSeatsExactlyOneSideA() {
        BenchGame.SeatPlan plan = BenchGame.assignSeats(podConfig("a.dck", "b.dck", "c.dck", "d.dck"), false);
        assertEquals("pod should seat every deck given", 4, plan.seats.size());
        long sideACount = plan.seats.stream().filter(s -> "A".equals(s.side)).count();
        assertEquals("exactly one seat is the bot under test", 1, sideACount);
    }

    @Test
    public void everySeatKeepsItsOwnDeckAndPlayerKey() {
        BenchGame.SeatPlan plan = BenchGame.assignSeats(podConfig("a.dck", "b.dck", "c.dck"), false);
        for (BenchGame.SeatPlan.Seat seat : plan.seats) {
            if ("A".equals(seat.side)) {
                assertEquals("side A plays deck 0", "a.dck", seat.deck);
                assertEquals("commander", seat.playerKey);
            } else {
                assertTrue("side B seats take the later decks",
                        Arrays.asList("b.dck", "c.dck").contains(seat.deck));
                assertEquals("cp7", seat.playerKey);
            }
        }
    }

    @Test
    public void rotationMovesSideAWithoutReorderingTheRest() {
        BenchConfig config = podConfig("a.dck", "b.dck", "c.dck", "d.dck");
        BenchGame.SeatPlan unrotated = BenchGame.assignSeats(config, false);
        BenchGame.SeatPlan rotated = BenchGame.assignSeats(config, true);

        assertEquals("unrotated: side A leads", 1, unrotated.seatOfSideA());
        assertTrue("rotated: side A has moved off seat 1", rotated.seatOfSideA() != 1);
        assertEquals("rotation is a rotation, so the same decks are present",
                new java.util.HashSet<>(decksOf(unrotated)), new java.util.HashSet<>(decksOf(rotated)));

        // the seat side A landed in must still be holding side A's deck: this is the pod version
        // of "params follow their deck" and the failure mode the duel test exists to catch
        BenchGame.SeatPlan.Seat sideA = rotated.seats.get(rotated.seatOfSideA() - 1);
        assertEquals("A", sideA.side);
        assertEquals("a.dck", sideA.deck);
    }

    @Test
    public void seatOfSideAMatchesWhereSideAActuallySits() {
        for (boolean rotate : new boolean[]{false, true}) {
            for (int size = 2; size <= 5; size++) {
                String[] decks = new String[size];
                for (int i = 0; i < size; i++) {
                    decks[i] = "d" + i + ".dck";
                }
                BenchGame.SeatPlan plan = BenchGame.assignSeats(podConfig(decks), rotate);
                int reported = plan.seatOfSideA();
                assertEquals("seatOfSideA must point at the side A seat (size " + size
                                + ", rotate " + rotate + ")",
                        "A", plan.seats.get(reported - 1).side);
            }
        }
    }

    @Test
    public void duelSeatingIsUnchangedByThePodCode() {
        // the pod path must not have altered the two-player case in any way
        BenchConfig duel = BenchConfig.parse(new String[]{
                "--gameType=commander", "--deckA=a.dck", "--deckB=b.dck",
                "--playerA=commander", "--playerB=cp7",
        });
        BenchGame.SeatPlan straight = BenchGame.assignSeats(duel, false);
        assertEquals("a.dck", straight.seat1.deck);
        assertEquals("b.dck", straight.seat2.deck);
        assertEquals(1, straight.seatOfSideA());

        BenchGame.SeatPlan swapped = BenchGame.assignSeats(duel, true);
        assertEquals("b.dck", swapped.seat1.deck);
        assertEquals("a.dck", swapped.seat2.deck);
        assertEquals(2, swapped.seatOfSideA());
    }

    @Test
    public void freeForAllWithoutDecksIsRejected() {
        try {
            BenchConfig.parse(new String[]{"--gameType=commanderffa"});
            fail("a pod with no seat decks should not be accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("--decks"));
        }
    }

    @Test
    public void moreThanTwoDecksWithoutFreeForAllIsRejected() {
        // a silent fallback to a duel here would drop seats 3+ and quietly benchmark the wrong thing
        try {
            BenchConfig.parse(new String[]{"--gameType=commander", "--decks=a.dck,b.dck,c.dck"});
            fail("three decks in a duel should not be accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("commanderffa"));
        }
    }
}
