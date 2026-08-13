package mage.player.ai.commander;

import mage.game.Game;
import org.apache.log4j.Logger;

/**
 * DARRELLBEST-FORK: one JSON object per AI decision, on its own stream, so a game can be
 * reconstructed without regex-mining an 190MB text log.
 * <p>
 * <b>Why this exists.</b> Answering "how did the bot do last night" meant parsing the human-readable
 * play lines, and every attempt got something wrong: the phase name contains a space so a naive
 * pattern silently matched nothing; the player is called "Seat1" on the bench and "Computer 3" live
 * so bench-shaped patterns found zero rows; and worst, the server runs several tables at once, so
 * slicing the log by timestamp interleaved a turn-1 game with a turn-32 game and attributed one
 * bot's win to another. Each of those produced a confident, wrong answer.
 * <p>
 * The game id is the fix for the last one. It is present on every record here, so decisions can
 * never be mixed between concurrent tables.
 * <p>
 * <b>It cannot go silently dead.</b> If {@code mage.ai.audit} has no appender of its own, log4j
 * additivity sends these records to the root logger and they land in the ordinary server log --
 * uglier to read, but still there. That property is deliberate: the mulligan land-protection in this
 * same package was dead code for its whole life because it sat on a method nothing called, and
 * nothing detected it. A logging path that quietly writes nowhere is the same failure.
 */
public final class AuditLog {

    private static final Logger AUDIT = Logger.getLogger("mage.ai.audit");

    private AuditLog() {
    }

    public static boolean enabled() {
        return AUDIT.isInfoEnabled();
    }

    /**
     * @param kind  PLAY, IDLE, ATTACK, BLOCK, MULLIGAN, MODE, DECLINE, RESULT
     * @param game  the live game (never a simulation -- callers check)
     * @param who   the acting player's name
     * @param extra already-formatted {@code "key":value} pairs, or null
     */
    public static void event(String kind, Game game, String who, String detail, Integer score, String extra) {
        if (!AUDIT.isInfoEnabled()) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder(160);
            sb.append("{\"kind\":\"").append(esc(kind)).append('"');
            if (game != null) {
                sb.append(",\"game\":\"").append(game.getId()).append('"');
                sb.append(",\"turn\":").append(game.getState().getTurnNum());
                sb.append(",\"phase\":\"").append(esc(String.valueOf(game.getTurnStepType()))).append('"');
                sb.append(",\"active\":\"").append(esc(nameOf(game, game.getActivePlayerId()))).append('"');
            }
            sb.append(",\"player\":\"").append(esc(who)).append('"');
            if (detail != null) {
                sb.append(",\"detail\":\"").append(esc(detail)).append('"');
            }
            if (score != null) {
                sb.append(",\"score\":").append(score);
            }
            if (extra != null && !extra.isEmpty()) {
                sb.append(',').append(extra);
            }
            sb.append('}');
            AUDIT.info(sb);
        } catch (Exception e) {
            // an audit record must never be able to affect a live game
            AUDIT.debug("audit emit failed", e);
        }
    }

    private static String nameOf(Game game, java.util.UUID id) {
        if (id == null) {
            return "";
        }
        mage.players.Player p = game.getPlayer(id);
        return p == null ? "" : p.getName();
    }

    /** Minimal JSON string escaping -- card names carry quotes, backslashes and control chars. */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
