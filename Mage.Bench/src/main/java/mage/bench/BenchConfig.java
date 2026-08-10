package mage.bench;

/**
 * Immutable run parameters for a benchmark run, built from CLI arguments.
 *
 * @author Darrell Best
 */
public final class BenchConfig {

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

    public BenchConfig(int games, long baseSeed, String deckA, String deckB,
                       String playerA, String playerB, int skill, String model,
                       int turnCap, String out, String deckDir, String ollamaUrl) {
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
    }

    public static BenchConfig parse(String[] args) {
        int games = 20;
        long baseSeed = 12345L;
        String deckA = "RB Aggro.dck";
        String deckB = "RB Aggro.dck";
        String playerA = "kanna";
        String playerB = "cp7";
        int skill = 6;
        String model = "qwen3.6:latest";
        int turnCap = 50;
        String out = "bench-results.jsonl";
        String deckDir = "Mage.Tests";
        String ollamaUrl = "http://localhost:11434";

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
            } else {
                throw new IllegalArgumentException("Unknown argument '--" + key + "'");
            }
        }
        return new BenchConfig(games, baseSeed, deckA, deckB, playerA, playerB,
                skill, model, turnCap, out, deckDir, ollamaUrl);
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

    /**
     * True when either seat uses an LLM-backed player, i.e. the run needs Ollama.
     */
    public boolean usesLlm() {
        return "kanna".equals(playerA) || "kanna".equals(playerB);
    }
}
