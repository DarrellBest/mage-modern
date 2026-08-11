package mage.bench;

/**
 * Immutable run parameters for a benchmark run, built from CLI arguments.
 *
 * @author Darrell Best
 */
public final class BenchConfig {

    public static final String GAME_TYPE_TWOPLAYER = "twoplayer";
    public static final String GAME_TYPE_COMMANDER = "commander";

    public final int games;
    public final long baseSeed;
    public final String deckA;
    public final String deckB;
    public final String playerA;
    public final String playerB;
    public final int skill;
    public final String model;
    public final int turnCap;
    public final String out;
    public final String deckDir;
    public final String ollamaUrl;
    public final String gameType;
    /** Path to write the per-deck card-play report to, or null when --trackCards was not given
     * (the default): off by default, and BenchRunner/BenchGame register no extra instrumentation
     * at all when this is null, so an ordinary run pays zero cost for this feature. */
    public final String trackCards;

    public BenchConfig(int games, long baseSeed, String deckA, String deckB,
                       String playerA, String playerB, int skill, String model,
                       int turnCap, String out, String deckDir, String ollamaUrl,
                       String gameType, String trackCards) {
        this.games = games;
        this.baseSeed = baseSeed;
        this.deckA = deckA;
        this.deckB = deckB;
        this.playerA = playerA;
        this.playerB = playerB;
        this.skill = skill;
        this.model = model;
        this.turnCap = turnCap;
        this.out = out;
        this.deckDir = deckDir;
        this.ollamaUrl = ollamaUrl;
        this.gameType = gameType;
        this.trackCards = trackCards;
    }

    public static BenchConfig parse(String[] args) {
        int games = 20;
        long baseSeed = 12345L;
        String deckA = "RB Aggro.dck";
        String deckB = "RB Aggro.dck";
        String playerA = "kanna";
        String playerB = "cp7";
        int skill = 6;
        String model = "xmage-ai-qwen3.6:latest";
        int turnCap = 50;
        String out = "bench-results.jsonl";
        String deckDir = "Mage.Tests";
        String ollamaUrl = "http://localhost:11434";
        String gameType = GAME_TYPE_TWOPLAYER;
        String trackCards = null;

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
            } else if ("model".equals(key)) {
                model = value;
            } else if ("turnCap".equals(key)) {
                turnCap = parseInt(key, value);
            } else if ("out".equals(key)) {
                out = value;
            } else if ("deckDir".equals(key)) {
                deckDir = value;
            } else if ("ollamaUrl".equals(key)) {
                ollamaUrl = value;
            } else if ("gameType".equals(key)) {
                gameType = parseGameType(value);
            } else if ("trackCards".equals(key)) {
                trackCards = value;
            } else {
                throw new IllegalArgumentException("Unknown argument '--" + key + "'");
            }
        }
        return new BenchConfig(games, baseSeed, deckA, deckB, playerA, playerB,
                skill, model, turnCap, out, deckDir, ollamaUrl, gameType, trackCards);
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
        if (GAME_TYPE_TWOPLAYER.equals(value) || GAME_TYPE_COMMANDER.equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("--gameType must be one of: "
                + GAME_TYPE_TWOPLAYER + ", " + GAME_TYPE_COMMANDER + "; got '" + value + "'");
    }

    /**
     * True when either seat uses an LLM-backed player, i.e. the run needs Ollama.
     */
    public boolean usesLlm() {
        return "kanna".equals(playerA) || "kanna".equals(playerB);
    }
}
