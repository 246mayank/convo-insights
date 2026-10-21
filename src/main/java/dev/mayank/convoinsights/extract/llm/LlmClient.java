package dev.mayank.convoinsights.extract.llm;

import java.util.Map;

/**
 * One chat completion against some model provider. Implementations return the raw text the model
 * produced; deciding whether that text is usable is the caller's job.
 */
public interface LlmClient {

    /**
     * @param jsonSchema JSON schema the provider should constrain its output to, if it supports that
     * @throws LlmException for timeouts and transport or HTTP failures
     */
    String complete(String systemPrompt, String userMessage, Map<String, Object> jsonSchema);

    /** Provider and model name, recorded on every insight, for example {@code ollama/qwen2.5:1.5b}. */
    String modelName();
}
