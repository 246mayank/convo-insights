package dev.mayank.convoinsights.it;

import dev.mayank.convoinsights.extract.llm.LlmClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A fake model for integration tests. It answers based on markers in the conversation text, so
 * each test controls the model's behaviour through the event it sends, and it records every
 * prompt it was given so tests can assert on what reached the "model".
 */
public class ScriptedLlmClient implements LlmClient {

    public static final String MALFORMED_MARKER = "#malformed";

    private final List<String> seenMessages = new CopyOnWriteArrayList<>();

    @Override
    public String complete(String systemPrompt, String userMessage, Map<String, Object> jsonSchema) {
        seenMessages.add(userMessage);
        if (userMessage.contains(MALFORMED_MARKER)) {
            return "Sorry, I can't produce JSON for this one.";
        }
        return "{\"intent\":\"TECHNICAL_SUPPORT\",\"sentiment\":\"NEGATIVE\","
                + "\"summary\":\"Customer reports something is broken.\",\"confidence\":0.9}";
    }

    @Override
    public String modelName() {
        return "scripted/test";
    }

    public List<String> seenMessages() {
        return seenMessages;
    }
}
