package mage.bench;

/**
 * Immutable run parameters for a benchmark run, built from CLI arguments.
 *
 * @author Darrell Best
 */
public final class BenchConfig {

    public static final String GAME_TYPE_TWOPLAYER = "twoplayer";
    public static final String GAME_TYPE_COMMANDER = "commander";
    /** DARRELLBEST-FORK: multiplayer commander pod (3+ seats), free for all. */
    public static final String GAME_TYPE_COMMANDER_FFA = "commanderffa";

    public final int games;
    public final long baseSeed;
    public final String deckA;
    public final String deckB;
    public final String playerA;
    public final String playerB;
    public final int skill;
    public final int turnCap;
    /**
     * Wall-clock budget per game in seconds, or 0 (the default) for unlimited.
     * <p>
     * Bounds a benchmark game in TIME, which {@link #turnCap} does not: turns are not a
     * proxy for seconds here. A measured Kairi mirror spent 504 seconds on 37 turns while a
     * Krenko game reached the same turn count in well under a minute, and one observed
     * configuration produced no completed game at all in 20 minutes. A game that exceeds
     * this budget is stopped at the next turn boundary and recorded as
     * {@link Termination#TIMEOUT} -- see BenchGame's watchdog, including what it cannot catch.
     */
    public final int maxGameSeconds;
    public final String out;
    public final String deckDir;
    public final String gameType;
    /**
     * DARRELLBEST-FORK: every seat's deck, in seat order, for a Free For All. Element 0 is side A
     * (the bot under test); every later element is a side B opponent. Null for a duel, where
     * {@link #deckA}/{@link #deckB} carry the same information.
     * <p>
     * Side A is deliberately ONE seat against N-1 opponents rather than an even split. The
     * multiplayer-only parameters exist to answer "how does one tuned bot fare in a real pod",
     * and a pod half-full of the bot under test measures it partly against itself.
     */
    public final java.util.List<String> deckList;
    /** Path to write the per-deck card-play report to, or null when --trackCards was not given
     * (the default): off by default, and BenchRunner/BenchGame register no extra instrumentation
     * at all when this is null, so an ordinary run pays zero cost for this feature. */
    public final String trackCards;
    /**
     * DARRELLBEST-FORK: path to a {@link EvalParamsLoader} properties file giving side A's evaluator
     * weights, or null (the default) for {@code CommanderEvalParams.DEFAULT}. Side A means the same
     * side as {@link #deckA}/{@link #playerA} -- BenchGame carries all three across the seat swap
     * together.
     */
    public final String paramsA;
    /** DARRELLBEST-FORK: side B's evaluator weights; see {@link #paramsA}. */
    public final String paramsB;

    public BenchConfig(int games, long baseSeed, String deckA, String deckB,
                       String playerA, String playerB, int skill,
                       int turnCap, int maxGameSeconds, String out, String deckDir,
                       String gameType, String trackCards,
                       String paramsA, String paramsB) {
        this(games, baseSeed, deckA, deckB, playerA, playerB, skill, turnCap, maxGameSeconds,
                out, deckDir, gameType, trackCards, paramsA, paramsB, null);
    }

    public BenchConfig(int games, long baseSeed, String deckA, String deckB,
                       String playerA, String playerB, int skill,
                       int turnCap, int maxGameSeconds, String out, String deckDir,
                       String gameType, String trackCards,
                       String paramsA, String paramsB, java.util.List<String> deckList) {
        this.deckList = deckList;
        this.games = games;
        this.baseSeed = baseSeed;
        this.deckA = deckA;
        this.deckB = deckB;
        this.playerA = playerA;
        this.playerB = playerB;
        this.skill = skill;
        this.turnCap = turnCap;
        this.maxGameSeconds = maxGameSeconds;
        this.out = out;
        this.deckDir = deckDir;
        this.gameType = gameType;
        this.trackCards = trackCards;
        this.paramsA = paramsA;
        this.paramsB = paramsB;
    }

    public static BenchConfig parse(String[] args) {
        int games = 20;
        long baseSeed = 12345L;
        String deckA = "RB Aggro.dck";
        String deckB = "RB Aggro.dck";
        String playerA = "commander";
        String playerB = "cp7";
        int skill = 6;
        int turnCap = 50;
        int maxGameSeconds = 0; // unlimited: preserves the pre-existing behaviour by default
        String out = "bench-results.jsonl";
        String deckDir = "Mage.Tests";
        String gameType = GAME_TYPE_TWOPLAYER;
        String trackCards = null;
        java.util.List<String> deckList = null;
        String paramsA = null; // null = CommanderEvalParams.DEFAULT
        String paramsB = null;

        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (!arg.startsWith("--") || eq < 0) {
                throw new IllegalArgumentException("Bad argument '" + arg + "', expected --name=value");
            }
            String key = arg.substring(2, eq);
            String value = arg.substring(eq + 1);
            if ("games".equals(key)) {
                games = parseInt(key, value);
            } else if ("seed".equals(key)) {
                baseSeed = parseLong(key, value);
            } else if ("deckA".equals(key)) {
                deckA = value;
            } else if ("deckB".equals(key)) {
                deckB = value;
            } else if ("playerA".equals(key)) {
                playerA = value;
            } else if ("playerB".equals(key)) {
                playerB = value;
            } else if ("skill".equals(key)) {
                skill = parseInt(key, value);
            } else if ("turnCap".equals(key)) {
                turnCap = parseInt(key, value);
            } else if ("maxGameSeconds".equals(key)) {
                maxGameSeconds = parseInt(key, value);
                if (maxGameSeconds < 0) {
                    throw new IllegalArgumentException("--maxGameSeconds must be >= 0 (0 = unlimited), got " + value);
                }
            } else if ("out".equals(key)) {
                out = value;
            } else if ("deckDir".equals(key)) {
                deckDir = value;
            } else if ("gameType".equals(key)) {
                gameType = parseGameType(value);
            } else if ("trackCards".equals(key)) {
                trackCards = value;
            } else if ("decks".equals(key)) {
                deckList = new java.util.ArrayList<>();
                for (String d : value.split(",")) {
                    if (!d.trim().isEmpty()) {
                        deckList.add(d.trim());
                    }
                }
            } else if ("paramsA".equals(key)) {
                paramsA = value;
            } else if ("paramsB".equals(key)) {
                paramsB = value;
            } else {
                throw new IllegalArgumentException("Unknown argument '--" + key + "'");
            }
        }
        if (deckList != null) {
            if (deckList.size() < 2 || deckList.size() > 6) {
                throw new IllegalArgumentException("--decks needs 2 to 6 deck names, got " + deckList.size());
            }
            if (deckList.size() > 2 && !GAME_TYPE_COMMANDER_FFA.equals(gameType)) {
                throw new IllegalArgumentException("--decks with more than 2 decks requires --gameType="
                        + GAME_TYPE_COMMANDER_FFA + ", got '" + gameType + "'");
            }
            deckA = deckList.get(0);
            deckB = deckList.get(1);
        } else if (GAME_TYPE_COMMANDER_FFA.equals(gameType)) {
            throw new IllegalArgumentException("--gameType=" + GAME_TYPE_COMMANDER_FFA
                    + " requires --decks=<seat1>,<seat2>,... (one deck per seat)");
        }
        return new BenchConfig(games, baseSeed, deckA, deckB, playerA, playerB,
                skill, turnCap, maxGameSeconds, out, deckDir, gameType, trackCards,
                paramsA, paramsB, deckList);
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be an integer, got '" + value + "'");
        }
    }

    private static long parseLong(String key, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be an integer, got '" + value + "'");
        }
    }

    private static String parseGameType(String value) {
        if (GAME_TYPE_TWOPLAYER.equals(value) || GAME_TYPE_COMMANDER.equals(value)
                || GAME_TYPE_COMMANDER_FFA.equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("--gameType must be one of: "
                + GAME_TYPE_TWOPLAYER + ", " + GAME_TYPE_COMMANDER + ", " + GAME_TYPE_COMMANDER_FFA
                + "; got '" + value + "'");
    }

    /** DARRELLBEST-FORK: true when this config describes a multiplayer pod rather than a duel. */
    public boolean isFreeForAll() {
        return GAME_TYPE_COMMANDER_FFA.equals(gameType);
    }

    /** DARRELLBEST-FORK: number of seats -- 2 for any duel, {@code deckList.size()} for a pod. */
    public int seatCount() {
        return deckList == null ? 2 : deckList.size();
    }
}
