package mage.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Shared Gson for the bridge. XMage's {@code mage.view.*} objects are plain
 * serializable beans; most serialize cleanly, but a few hold back-references
 * that can cycle, so callers serialize defensively (try/catch) and the bridge
 * degrades to an error message rather than crashing the socket.
 */
public final class Json {

    public static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private Json() {
    }

    /** Serialize, never throw: returns null on failure so the caller can report it. */
    public static String tryToJson(Object o) {
        try {
            return GSON.toJson(o);
        } catch (Throwable t) {
            return null;
        }
    }
}
