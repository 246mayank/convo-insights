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
import java.util.List;
import java.util.Map;

/**
 * Ollama's {@code /api/chat} with structured outputs: passing a JSON schema as {@code format}
 * makes the server constrain decoding to that schema.
 */
public class OllamaClient implements LlmClient {

    private final RestClient http;
    private final String model;
    private final int numCtx;
    private final int numPredict;

    public OllamaClient(String baseUrl, String model, Duration timeout, int numCtx, int numPredict) {
        this(RestClient.builder(), baseUrl, model, timeout, numCtx, numPredict);
    }

    /** With Spring's auto-configured builder, each model call becomes a client span in the trace. */
    public OllamaClient(RestClient.Builder builder, String baseUrl, String model, Duration timeout, int numCtx, int numPredict) {
        var jdk = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(timeout);
        this.http = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.model = model;
        this.numCtx = numCtx;
        this.numPredict = numPredict;
    }

    @Override
    public String complete(String systemPrompt, String userMessage, Map<String, Object> jsonSchema) {
        var body = Map.of(
                "model", model,
                "stream", false,
                "format", jsonSchema,
                "options", Map.of("temperature", 0, "num_ctx", numCtx, "num_predict", numPredict),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)));
        try {
            ChatResponse response = http.post().uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ChatResponse.class);
            if (response == null || response.message() == null || response.message().content() == null) {
                throw new LlmException(FallbackReason.MALFORMED_JSON, "empty response from Ollama", null);
            }
            return response.message().content();
        } catch (ResourceAccessException e) {
            FallbackReason reason = isTimeout(e) ? FallbackReason.TIMEOUT : FallbackReason.HTTP_ERROR;
            throw new LlmException(reason, "Ollama call failed: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new LlmException(FallbackReason.HTTP_ERROR, "Ollama call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String modelName() {
        return "ollama/" + model;
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) return true;
        }
        return false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String role, String content) {
    }
}
