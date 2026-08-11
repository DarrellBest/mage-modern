package mage.bench;

import mage.player.ai.commander.score.CommanderEvalParams;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Covers the loader's contract, and most of all its REFUSALS.
 * <p>
 * The failure cases here are the point of the class. A params file that is silently
 * misinterpreted -- a mistyped weight name ignored, a bad value defaulted -- produces a sweep leg
 * that ran with weights nobody chose and reports it as a measurement. Every one of those is
 * asserted to throw, and to name the offending key.
 *
 * @author Darrell Best
 */
public class EvalParamsLoaderTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String write(String name, String content) throws Exception {
        File file = new File(folder.getRoot(), name);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file.getAbsolutePath();
    }

    // --- valid files ---

    @Test
    public void validFile_appliesEveryKeyItNames() throws Exception {
        String path = write("full.properties",
                "handCardScore=150\n"
                        + "creaturePowerMultiplier=450\n"
                        + "lifeAboveMultiplier=7\n"
                        + "permanentOnBattlefieldBonus=42\n"
                        + "tappedLandPenalty=-33\n"
                        + "abilityScoreDivisor=4\n"
                        + "lifeScores=0,10,20,30\n");

        CommanderEvalParams params = EvalParamsLoader.load(path);

        assertEquals(150, params.getHandCardScore());
        assertEquals(450, params.getCreaturePowerMultiplier());
        assertEquals(7, params.getLifeAboveMultiplier());
        assertEquals(42, params.getPermanentOnBattlefieldBonus());
        assertEquals(-33, params.getTappedLandPenalty());
        assertEquals(4, params.getAbilityScoreDivisor());
        // lifeScores defines the table AND its length-derived maximum
        assertEquals(3, params.getMaxTabulatedLife());
        assertEquals(0, params.getLifeScoreAt(0));
        assertEquals(30, params.getLifeScoreAt(3));
    }

    @Test
    public void oneKeyFile_leavesEveryOtherWeightAtItsDefault() throws Exception {
        String path = write("one.properties", "handCardScore=150\n");

        CommanderEvalParams params = EvalParamsLoader.load(path);

        assertEquals(150, params.getHandCardScore());
        // everything else must be untouched -- this is what makes a single-parameter sweep leg mean
        // "only this weight changed"
        CommanderEvalParams stock = CommanderEvalParams.DEFAULT;
        assertEquals(stock.getCreaturePowerMultiplier(), params.getCreaturePowerMultiplier());
        assertEquals(stock.getPermanentOnBattlefieldBonus(), params.getPermanentOnBattlefieldBonus());
        assertEquals(stock.getLifeAboveMultiplier(), params.getLifeAboveMultiplier());
        assertEquals(stock.getDetrimentalOwnAuraPenalty(), params.getDetrimentalOwnAuraPenalty());
        assertEquals(stock.getAbilityScoreDivisor(), params.getAbilityScoreDivisor());
        assertEquals(stock.getMaxTabulatedLife(), params.getMaxTabulatedLife());
        assertEquals(stock.getLifeScoreAt(stock.getMaxTabulatedLife()),
                params.getLifeScoreAt(params.getMaxTabulatedLife()));
    }

    @Test
    public void commentsAndBlankLinesAndWhitespace_areTolerated() throws Exception {
        String path = write("comments.properties",
                "# a sweep leg\n"
                        + "\n"
                        + "  handCardScore = 150  \n"
                        + "! another comment style\n"
                        + "lifeScores = 0, 10 ,20\n");

        CommanderEvalParams params = EvalParamsLoader.load(path);

        assertEquals(150, params.getHandCardScore());
        assertEquals(2, params.getMaxTabulatedLife());
        assertEquals(20, params.getLifeScoreAt(2));
    }

    @Test
    public void negativeAndZeroValues_areAccepted() throws Exception {
        // several weights are negative by design (penalties), so a leading '-' must parse
        String path = write("negative.properties",
                "cannotAttackPenalty=-500\n"
                        + "tappedOtherPenalty=0\n");

        CommanderEvalParams params = EvalParamsLoader.load(path);

        assertEquals(-500, params.getCannotAttackPenalty());
        assertEquals(0, params.getTappedOtherPenalty());
    }

    // --- refusals ---

    @Test
    public void unknownKey_throwsAndNamesTheKeyAndTheValidOnes() throws Exception {
        // the exact scenario the loud-failure requirement exists for: a case typo. Silently
        // ignoring this would make the run report "handCardScore has no effect".
        String path = write("typo.properties", "handcardScore=150\n");

        try {
            EvalParamsLoader.load(path);
            fail("expected IllegalArgumentException for an unknown key");
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            assertTrue("should name the offending key: " + message, message.contains("handcardScore"));
            assertTrue("should name the file: " + message, message.contains("typo.properties"));
            assertTrue("should list the valid keys: " + message, message.contains("creaturePowerMultiplier"));
            assertTrue("should suggest the intended key: " + message,
                    message.contains("Did you mean 'handCardScore'?"));
        }
    }

    @Test
    public void unknownKey_throwsEvenWhenEveryOtherKeyIsValid() throws Exception {
        // a bad key hidden among good ones is the realistic case, and the one a "best effort"
        // loader would be most tempted to skip
        String path = write("mixed.properties",
                "handCardScore=150\n"
                        + "creaturePowerMultiplier=450\n"
                        + "notAWeightAtAll=1\n");

        try {
            EvalParamsLoader.load(path);
            fail("expected IllegalArgumentException for an unknown key");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("notAWeightAtAll"));
        }
    }

    @Test
    public void builderMethodThatIsNotAWeight_isNotAcceptedAsAKey() throws Exception {
        // guards the reflection rule itself: build() and toBuilder() are public methods on/near the
        // builder but are not weights, and must not become settable "parameters"
        for (String notAWeight : new String[]{"build", "toBuilder", "builder", "equals", "hashCode"}) {
            String path = write(notAWeight + ".properties", notAWeight + "=1\n");
            try {
                EvalParamsLoader.load(path);
                fail("expected IllegalArgumentException for key '" + notAWeight + "'");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains(notAWeight));
            }
        }
    }

    @Test
    public void nonIntegerValue_throwsAndNamesTheKey() throws Exception {
        String path = write("badint.properties", "handCardScore=lots\n");

        try {
            EvalParamsLoader.load(path);
            fail("expected IllegalArgumentException for a non-integer value");
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            assertTrue("should name the key: " + message, message.contains("handCardScore"));
            assertTrue("should show the bad value: " + message, message.contains("lots"));
            assertTrue("should say what was expected: " + message, message.contains("integer"));
        }
    }

    @Test
    public void emptyValue_throws() throws Exception {
        String path = write("emptyvalue.properties", "handCardScore=\n");

        try {
            EvalParamsLoader.load(path);
            fail("expected IllegalArgumentException for an empty value");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("handCardScore"));
        }
    }

    @Test
    public void floatValue_throwsRatherThanTruncating() throws Exception {
        // every weight is an int; quietly truncating 150.9 to 150 would make the file and the run
        // disagree about what was measured
        String path = write("float.properties", "handCardScore=150.9\n");

        try {
            EvalParamsLoader.load(path);
            fail("expected IllegalArgumentException for a non-integer value");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("150.9"));
        }
    }

    @Test
    public void malformedLifeScores_throws() throws Exception {
        String path = write("badlife.properties", "lifeScores=0,1000,oops,3000\n");

        try {
            EvalParamsLoader.load(path);
            fail("expected IllegalArgumentException for a malformed lifeScores list");
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            assertTrue("should name the key: " + message, message.contains("lifeScores"));
            assertTrue("should point at the bad element: " + message, message.contains("oops"));
        }
    }

    @Test
    public void lifeScoresWithTrailingComma_throws() throws Exception {
        // "0,1000," is a truncated list, not a 2-element one -- accepting it would silently shorten
        // the life curve, which changes MaxTabulatedLife and thus the whole life evaluation
        String path = write("trailing.properties", "lifeScores=0,1000,\n");

        try {
            EvalParamsLoader.load(path);
            fail("expected IllegalArgumentException for a trailing comma");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("lifeScores"));
        }
    }

    @Test
    public void emptyLifeScores_throws() throws Exception {
        String path = write("emptylife.properties", "lifeScores=\n");

        try {
            EvalParamsLoader.load(path);
            fail("expected IllegalArgumentException for an empty lifeScores list");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("lifeScores"));
        }
    }

    @Test
    public void valueRejectedByTheBuilder_throwsWithTheBuildersOwnReason() throws Exception {
        // abilityScoreDivisor is an integer divisor on the evaluator's hot path; the builder refuses
        // 0 and the loader must surface that rather than an InvocationTargetException
        String path = write("divzero.properties", "abilityScoreDivisor=0\n");

        try {
            EvalParamsLoader.load(path);
            fail("expected IllegalArgumentException for a zero divisor");
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            assertTrue("should name the key: " + message, message.contains("abilityScoreDivisor"));
            assertTrue("should carry the builder's reason: " + message, message.contains("non-zero"));
        }
    }

    @Test
    public void fileWithNoKeys_throws() throws Exception {
        // a file that changes nothing yields DEFAULT, and a sweep leg that silently ran with DEFAULT
        // reports a false "no effect"
        String path = write("empty.properties", "# everything commented out\n#handCardScore=150\n");

        try {
            EvalParamsLoader.load(path);
            fail("expected IllegalArgumentException for a file with no keys");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("contains no keys"));
        }
    }

    @Test
    public void missingFile_throwsWithTheAbsolutePath() {
        String path = new File(folder.getRoot(), "nope.properties").getAbsolutePath();

        try {
            EvalParamsLoader.load(path);
            fail("expected IllegalArgumentException for a missing file");
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            assertTrue("should say it is missing: " + message, message.contains("not found"));
            assertTrue("should give the absolute path: " + message, message.contains("nope.properties"));
        }
    }

    // --- null handling, caching and descriptors ---

    @Test
    public void paramsFor_nullPath_staysNull() {
        // null must NOT become DEFAULT here: PlayerFactory distinguishes "no params given" from
        // "params given" to reject tuned weights on a bot that cannot use them
        assertNull(EvalParamsLoader.paramsFor(null));
        assertEquals("default", EvalParamsLoader.describe(null));
        assertTrue(EvalParamsLoader.overrides(null).isEmpty());
    }

    @Test
    public void paramsFor_cachesByPath_soEveryGameSharesOneInstance() throws Exception {
        String path = write("cached.properties", "handCardScore=150\n");

        CommanderEvalParams first = EvalParamsLoader.paramsFor(path);
        CommanderEvalParams second = EvalParamsLoader.paramsFor(path);

        assertSame("the same file must resolve to the same shared instance", first, second);
    }

    @Test
    public void describe_carriesThePathAndAHashOfTheResolvedValues() throws Exception {
        String pathOne = write("a.properties", "handCardScore=150\n");
        String pathTwo = write("b.properties", "handCardScore=151\n");
        String pathThree = write("c.properties", "handCardScore=150\n");

        String one = EvalParamsLoader.describe(pathOne);
        String two = EvalParamsLoader.describe(pathTwo);
        String three = EvalParamsLoader.describe(pathThree);

        assertTrue("should carry the file path: " + one, one.contains("a.properties"));
        assertTrue("should carry a hash: " + one, one.contains("#"));
        String hashOne = one.substring(one.indexOf('#'));
        String hashTwo = two.substring(two.indexOf('#'));
        String hashThree = three.substring(three.indexOf('#'));
        assertNotEquals("different weights must hash differently", hashOne, hashTwo);
        assertEquals("identical weights must hash identically", hashOne, hashThree);
        assertNotEquals("tuned weights must not hash as default", "default", one);
    }

    @Test
    public void overrides_reportExactlyWhatTheFileSet() throws Exception {
        String path = write("overrides.properties",
                "creaturePowerMultiplier=450\nhandCardScore=150\n");

        Map<String, String> overrides = EvalParamsLoader.overrides(path);

        assertEquals(2, overrides.size());
        assertEquals("150", overrides.get("handCardScore"));
        assertEquals("450", overrides.get("creaturePowerMultiplier"));
    }

    @Test
    public void validKeys_areDiscoveredFromTheBuilder() {
        // Deliberately a FLOOR, not an exact count. The weight set is under active development (it
        // grew from 27 to 28 while this loader was being written), and a weight added to
        // CommanderEvalParams.Builder becoming loadable with no change here is the whole point of
        // discovering the keys by reflection -- pinning an exact number would turn that feature into
        // a build break for whoever adds the next weight. What must hold is that the long-standing
        // weights are all reachable and the list is stable in order.
        assertTrue("expected at least the 27 original weights, got " + EvalParamsLoader.validKeys().size(),
                EvalParamsLoader.validKeys().size() >= 27);
        assertTrue(EvalParamsLoader.validKeys().contains("handCardScore"));
        assertTrue(EvalParamsLoader.validKeys().contains("lifeScores"));
        assertTrue(EvalParamsLoader.validKeys().contains("detrimentalOwnAuraPenalty"));
        // sorted, so error messages are stable
        assertEquals("abilityScoreDivisor", EvalParamsLoader.validKeys().get(0));
    }

    @Test
    public void everyDiscoveredKey_isActuallySettableAndLandsInItsField() throws Exception {
        // The strong form of the previous test, and the one that keeps its value as weights are
        // added: build a file that sets EVERY key the loader advertises, load it, and check each
        // value arrived. A key that this loader lists but cannot actually apply -- or applies to the
        // wrong field -- would be worse than an unknown key, because it would be advertised as
        // valid in the error messages.
        StringBuilder file = new StringBuilder();
        for (String key : EvalParamsLoader.validKeys()) {
            file.append(key).append('=').append("lifeScores".equals(key) ? "0,10" : "7").append('\n');
        }
        String path = write("everykey.properties", file.toString());

        CommanderEvalParams params = EvalParamsLoader.load(path);

        for (String key : EvalParamsLoader.validKeys()) {
            if ("lifeScores".equals(key)) {
                assertEquals("lifeScores should define the table length", 1, params.getMaxTabulatedLife());
                assertEquals(0, params.getLifeScoreAt(0));
                assertEquals(10, params.getLifeScoreAt(1));
                continue;
            }
            String getterName = "get" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
            java.lang.reflect.Method getter;
            try {
                getter = CommanderEvalParams.class.getMethod(getterName);
            } catch (NoSuchMethodException e) {
                continue; // a weight the evaluator reads some other way; still proven loadable above
            }
            assertEquals("key '" + key + "' did not reach " + getterName + "()",
                    7, ((Integer) getter.invoke(params)).intValue());
        }
    }
}
