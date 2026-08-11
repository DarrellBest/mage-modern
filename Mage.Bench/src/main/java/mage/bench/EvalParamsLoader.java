package mage.bench;

import mage.player.ai.commander.score.CommanderEvalParams;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DARRELLBEST-FORK: loads a {@link CommanderEvalParams} from a {@code java.util.Properties} file, so
 * a parameter sweep can change evaluator weights per run without recompiling.
 * <p>
 * File format: one {@code key=value} per line, where the key is exactly a
 * {@code CommanderEvalParams.Builder} setter name and the value an integer --
 * {@code lifeScores} being the one exception, a comma-separated list of integers. Only the keys
 * PRESENT are applied, on top of {@link CommanderEvalParams#DEFAULT}, so a file may name a single
 * weight:
 * <pre>
 *   handCardScore=150
 *   creaturePowerMultiplier=450
 *   lifeScores=0,1000,2000,3000
 * </pre>
 * <p>
 * <b>Why this lives in Mage.Bench and not next to {@code CommanderEvalParams}.</b> Reading files is
 * a benchmark's job. The AI module is loaded by the live server, where an evaluator that can reach
 * for a file is a configuration surface nobody asked for; keeping the parse here means the AI module
 * depends on nothing but the value object.
 * <p>
 * <b>Every malformed input is fatal, deliberately.</b> An unknown key is the dangerous one: a sweep
 * that sets {@code handcardScore=150} (lower-case c) and silently gets DEFAULT reports "this
 * parameter has no effect" for a parameter it never changed, and that conclusion is indistinguishable
 * from a real measurement. So an unrecognised key, a non-integer value, an unparseable
 * {@code lifeScores} list, and a file with no keys at all each throw
 * {@link IllegalArgumentException} naming the file and the offending key. There is no lenient mode.
 * <p>
 * <b>The valid key set is derived from {@code CommanderEvalParams.Builder} by reflection</b>, not
 * copied into a list here. A hand-maintained copy drifts: the 28th weight added to the builder would
 * be rejected by this loader as "unknown" until somebody remembered to update two files. Reflection
 * makes that class of bug unrepresentable -- the rule is "public method on Builder returning Builder
 * and taking exactly one int or int[]", which matches its 27 setters and nothing else
 * ({@code build()} returns {@code CommanderEvalParams}, so it is not a candidate).
 *
 * @author Darrell Best
 */
public final class EvalParamsLoader {

    /** Builder setter name -&gt; the setter, for every weight the builder exposes. */
    private static final Map<String, Method> SETTERS;
    /** Sorted valid key names, for error messages. */
    private static final List<String> VALID_KEYS;

    static {
        Map<String, Method> setters = new TreeMap<>();
        for (Method method : CommanderEvalParams.Builder.class.getMethods()) {
            if (method.getReturnType() != CommanderEvalParams.Builder.class
                    || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> paramType = method.getParameterTypes()[0];
            if (paramType == int.class || paramType == int[].class) {
                setters.put(method.getName(), method);
            }
        }
        if (setters.isEmpty()) {
            // Reflection found nothing: the builder was renamed or restructured, and every params
            // file in existence is about to be rejected as "unknown key". Fail at class-load time
            // with the real reason rather than 27 confusing per-key errors later.
            throw new IllegalStateException("No CommanderEvalParams.Builder setters found by reflection; "
                    + "EvalParamsLoader's discovery rule no longer matches the builder");
        }
        SETTERS = Collections.unmodifiableMap(setters);
        VALID_KEYS = Collections.unmodifiableList(new ArrayList<>(setters.keySet()));
    }

    /**
     * Cache keyed by the path string as given. A run asks for the same two files once per game and
     * the answer cannot change mid-run; caching also means the {@link CommanderEvalParams} instance
     * is shared by reference across every game and every player copy, which is what the value
     * object was designed for.
     */
    private static final Map<String, CommanderEvalParams> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> OVERRIDES_CACHE = new ConcurrentHashMap<>();

    private EvalParamsLoader() {
    }

    /** @return every weight name a params file may set, sorted. */
    public static List<String> validKeys() {
        return VALID_KEYS;
    }

    /**
     * Reads {@code path} and applies its keys on top of {@link CommanderEvalParams#DEFAULT}. Always
     * hits the disk; {@link #paramsFor(String)} is the cached, null-tolerant form callers normally
     * want.
     *
     * @throws IllegalArgumentException if the file is missing, unreadable, empty of keys, names a
     *                                  key that is not a weight, or gives a value that is not an
     *                                  integer (or, for {@code lifeScores}, a list of them)
     */
    public static CommanderEvalParams load(String path) {
        if (path == null) {
            throw new IllegalArgumentException("params file path must not be null");
        }
        Path file = Paths.get(path);
        Properties properties = new Properties();
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Params file not found: " + file.toAbsolutePath());
        }
        // Reader, not InputStream: Properties.load(InputStream) decodes ISO-8859-1. Values here are
        // ASCII integers either way, but reading UTF-8 keeps a BOM or a stray non-ASCII character
        // producing a sane "unknown key" message instead of mojibake.
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalArgumentException("Params file could not be read: " + file.toAbsolutePath()
                    + " -- " + e, e);
        }

        if (properties.stringPropertyNames().isEmpty()) {
            // Same reasoning as the unknown-key check: a file that sets nothing yields DEFAULT, and a
            // sweep leg that silently ran with DEFAULT is a false "no effect" result. "No overrides"
            // is already expressible by not passing the flag at all.
            throw new IllegalArgumentException("Params file " + file.toAbsolutePath()
                    + " contains no keys. A file that changes nothing is almost certainly a mistake "
                    + "(wrong file, or all lines commented out); omit the option instead. Valid keys: "
                    + String.join(", ", VALID_KEYS));
        }

        // Sorted, so that a file with two bad keys always reports the same one first -- an error
        // message that varies run to run is a bad error message.
        List<String> keys = new ArrayList<>(properties.stringPropertyNames());
        Collections.sort(keys);

        CommanderEvalParams.Builder builder = CommanderEvalParams.DEFAULT.toBuilder();
        Map<String, String> overrides = new LinkedHashMap<>();
        for (String key : keys) {
            String rawValue = properties.getProperty(key).trim();
            Method setter = SETTERS.get(key);
            if (setter == null) {
                throw new IllegalArgumentException(unknownKeyMessage(file, key));
            }
            Object argument = setter.getParameterTypes()[0] == int[].class
                    ? parseIntList(file, key, rawValue)
                    : parseInt(file, key, rawValue);
            try {
                setter.invoke(builder, argument);
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException("Params file " + file.toAbsolutePath()
                        + ": could not apply key '" + key + "' -- " + e, e);
            } catch (InvocationTargetException e) {
                // The builder validates some values itself (abilityScoreDivisor != 0, non-empty
                // lifeScores). Surface its message with the file and key attached rather than a bare
                // InvocationTargetException.
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new IllegalArgumentException("Params file " + file.toAbsolutePath()
                        + ": key '" + key + "' has an invalid value '" + rawValue + "' -- "
                        + cause.getMessage(), cause);
            }
            overrides.put(key, rawValue);
        }

        CommanderEvalParams params = builder.build();
        OVERRIDES_CACHE.put(path, Collections.unmodifiableMap(overrides));
        return params;
    }

    /**
     * The null-tolerant, cached form used by the harness.
     *
     * @param path a params file, or null when the caller passed none
     * @return the loaded params, or <b>null</b> when {@code path} is null
     *         <p>
     *         Null propagates rather than being replaced by {@link CommanderEvalParams#DEFAULT} here
     *         so that "no params file was given" stays distinguishable from "a params file was
     *         given" all the way down to {@link PlayerFactory}, which must reject tuned weights on a
     *         bot that cannot use them. Substituting DEFAULT at this layer would make every seat
     *         look like it had been handed weights, and a plain {@code --playerA=cp7} run with no
     *         params at all would fail that check.
     */
    public static CommanderEvalParams paramsFor(String path) {
        if (path == null) {
            return null;
        }
        // Not computeIfAbsent: load() throws for a bad file, and an exception thrown from inside
        // computeIfAbsent's mapping function on a ConcurrentHashMap is fine but obscures the stack.
        CommanderEvalParams cached = CACHE.get(path);
        if (cached != null) {
            return cached;
        }
        CommanderEvalParams params = load(path);
        CACHE.put(path, params);
        return params;
    }

    /**
     * The raw {@code key=value} pairs the file actually set, in key order, for run output and logs.
     * Empty when {@code path} is null.
     */
    public static Map<String, String> overrides(String path) {
        if (path == null) {
            return Collections.emptyMap();
        }
        paramsFor(path); // populates OVERRIDES_CACHE as a side effect of loading
        Map<String, String> overrides = OVERRIDES_CACHE.get(path);
        return overrides == null ? Collections.<String, String>emptyMap() : overrides;
    }

    /**
     * A short, stable identifier for the weights on one side of a matchup, for recording in run
     * output: {@code "default"} when no file was given, otherwise the file's absolute path plus a
     * hash of the RESOLVED values.
     * <p>
     * The hash matters as much as the path. Sweeps reuse file names and edit files in place, so
     * "which file" does not by itself answer "which weights"; two result sets whose rows carry the
     * same path but different hashes came from different parameters, and that is exactly the
     * confusion this string exists to prevent.
     */
    public static String describe(String path) {
        if (path == null) {
            return "default";
        }
        CommanderEvalParams params = paramsFor(path);
        return Paths.get(path).toAbsolutePath() + "#" + hash(params);
    }

    /** {@link #describe} plus the overrides themselves, for the one-off run header. */
    public static String describeVerbose(String path) {
        if (path == null) {
            return "default (no params file)";
        }
        return describe(path) + " " + overrides(path);
    }

    private static String unknownKeyMessage(Path file, String key) {
        StringBuilder message = new StringBuilder("Params file ").append(file.toAbsolutePath())
                .append(": unknown key '").append(key).append("'.");
        for (String valid : VALID_KEYS) {
            if (valid.equalsIgnoreCase(key)) {
                message.append(" Did you mean '").append(valid).append("'?");
                break;
            }
        }
        message.append(" A key that is not an evaluator weight is refused rather than ignored: "
                        + "ignoring it would make this run report 'no effect' for a weight it never "
                        + "changed. Valid keys: ")
                .append(String.join(", ", VALID_KEYS));
        return message.toString();
    }

    private static int parseInt(Path file, String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Params file " + file.toAbsolutePath() + ": key '" + key
                    + "' must be an integer, got '" + value + "'", e);
        }
    }

    private static int[] parseIntList(Path file, String key, String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Params file " + file.toAbsolutePath() + ": key '" + key
                    + "' must be a comma-separated list of integers, but is empty");
        }
        String[] parts = value.split(",", -1); // -1: a trailing comma yields an empty part and fails
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            try {
                result[i] = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Params file " + file.toAbsolutePath() + ": key '" + key
                        + "' must be a comma-separated list of integers, but element " + (i + 1)
                        + " of '" + value + "' is '" + part + "'", e);
            }
        }
        return result;
    }

    /**
     * First 8 hex chars of the SHA-256 of the params' full {@code toString()} -- which names every
     * field, so any weight difference changes the hash. SHA-256 rather than {@code hashCode()}
     * because this string is written into result files that outlive the process and get compared
     * across machines.
     */
    private static String hash(CommanderEvalParams params) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(params.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", bytes[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
