package mage.player.ai.kanna;

import com.google.gson.JsonObject;

/**
 * A parsed tool call from the model.
 *
 * @author Darrell Best
 */
public final class ToolCall {

    public final String name;
    public final JsonObject arguments;

    public ToolCall(String name, JsonObject arguments) {
        this.name = name;
        this.arguments = arguments;
    }
}
