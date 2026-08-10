package mage.bench;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OllamaPreflightTest {

    private static final String TAGS_JSON =
            "{\"models\":[{\"name\":\"qwen3.6:latest\"},{\"name\":\"gemma4:31b\"}]}";

    @Test
    public void findsAModelThatIsPresent() {
        assertTrue(OllamaPreflight.modelPresent(TAGS_JSON, "qwen3.6:latest"));
        assertTrue(OllamaPreflight.modelPresent(TAGS_JSON, "gemma4:31b"));
    }

    @Test
    public void rejectsAModelThatIsAbsent() {
        assertFalse(OllamaPreflight.modelPresent(TAGS_JSON, "llama9:latest"));
    }

    @Test
    public void emptyModelList_rejectsEverything() {
        assertFalse(OllamaPreflight.modelPresent("{\"models\":[]}", "qwen3.6:latest"));
    }

    @Test
    public void malformedJson_rejectsRatherThanThrowing() {
        assertFalse(OllamaPreflight.modelPresent("not json", "qwen3.6:latest"));
    }
}
