package dev.mayank.convoinsights.extract.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.mayank.convoinsights.domain.FallbackReason;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini ({@code generateContent}) behind the same {@link LlmClient} interface as Ollama.
 * Gemini takes an OpenAPI-style response schema, so our JSON schema is translated: upper-case
 * types, and no numeric bounds (the validator checks those anyway). The API key comes from the
 * {@code GEMINI_API_KEY} environment variable and is sent as a header, never in the URL.
 */
public class GeminiClient implements LlmClient {

    static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    private final RestClient http;
    private final String model;
    private final int maxOutputTokens;

    public GeminiClient(RestClient.Builder builder, String apiKey, String model, Duration timeout, int maxOutputTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not set");
        }
        var jdk = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(timeout);
        this.http = builder.clone().baseUrl(BASE_URL).requestFactory(factory)
                .defaultHeader("x-goog-api-key", apiKey).build();
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public String complete(String systemPrompt, String userMessage, Map<String, Object> jsonSchema) {
        var body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userMessage)))),
                "generationConfig", Map.of(
                        "temperature", 0,
                        "maxOutputTokens", maxOutputTokens,
                        "responseMimeType", "application/json",
                        "responseSchema", toGeminiSchema(jsonSchema)));
        try {
            Response r = http.post().uri("/models/{model}:generateContent", model)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Response.class);
            if (r == null || r.candidates() == null || r.candidates().isEmpty()
                    || r.candidates().getFirst().content() == null
                    || r.candidates().getFirst().content().parts() == null
                    || r.candidates().getFirst().content().parts().isEmpty()) {
                throw new LlmException(FallbackReason.MALFORMED_JSON, "empty response from Gemini", null);
            }
            return r.candidates().getFirst().content().parts().getFirst().text();
        } catch (ResourceAccessException e) {
            throw new LlmException(isTimeout(e) ? FallbackReason.TIMEOUT : FallbackReason.HTTP_ERROR,
                    "Gemini call failed: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new LlmException(FallbackReason.HTTP_ERROR, "Gemini call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String modelName() {
        return "gemini/" + model;
    }

    /** JSON schema (lower-case types, min/max) to Gemini's OpenAPI subset (upper-case types, no bounds). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> toGeminiSchema(Map<String, Object> schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        schema.forEach((k, v) -> {
            switch (k) {
                case "type" -> out.put("type", v.toString().toUpperCase());
                case "properties" -> {
                    Map<String, Object> props = new LinkedHashMap<>();
                    ((Map<String, Object>) v).forEach((name, p) -> props.put(name, toGeminiSchema((Map<String, Object>) p)));
                    out.put("properties", props);
                }
                case "minimum", "maximum" -> { /* not supported; the validator enforces ranges */ }
                default -> out.put(k, v);
            }
        });
        return out;
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) return true;
        }
        return false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<Candidate> candidates) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(Content content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Content(List<Part> parts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Part(String text) {
    }
}
