package dev.mayank.convoinsights.extract.llm;

import dev.mayank.convoinsights.domain.FallbackReason;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakingLlmClientTest {

    /** A model whose behaviour the test switches between calls. */
    static class FlakyModel implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        volatile FallbackReason failWith;
        volatile String answer = "{}";

        @Override
        public String complete(String s, String u, Map<String, Object> schema) {
            calls.incrementAndGet();
            if (failWith != null) throw new LlmException(failWith, "simulated " + failWith, null);
            return answer;
        }

        @Override
        public String modelName() {
            return "flaky";
        }
    }

    private static void call(LlmClient c) {
        try {
            c.complete("s", "u", Map.of());
        } catch (LlmException ignored) {
        }
    }

    @Test
    void opensAfterRepeatedTimeoutsAndStopsCallingTheModel() {
        var model = new FlakyModel();
        var client = new CircuitBreakingLlmClient(model, CircuitBreakingLlmClient.defaultBreaker("t1", Duration.ofMinutes(1)));
        model.failWith = FallbackReason.TIMEOUT;
        for (int i = 0; i < 5; i++) call(client);

        assertThat(client.breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsWhenOpened = model.calls.get();
        assertThatThrownBy(() -> client.complete("s", "u", Map.of()))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).reason()).isEqualTo(FallbackReason.CIRCUIT_OPEN);
        assertThat(model.calls.get()).as("an open breaker must not call the model").isEqualTo(callsWhenOpened);
    }

    @Test
    void halfOpenTrialsCloseTheBreakerWhenTheModelRecovers() throws Exception {
        var model = new FlakyModel();
        var client = new CircuitBreakingLlmClient(model, CircuitBreakingLlmClient.defaultBreaker("t2", Duration.ofMillis(200)));
        model.failWith = FallbackReason.HTTP_ERROR;
        for (int i = 0; i < 5; i++) call(client);
        assertThat(client.breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        model.failWith = null; // the model is back
        Thread.sleep(400);     // cool-down elapses, the breaker moves to half-open
        for (int i = 0; i < 3; i++) assertThat(client.complete("s", "u", Map.of())).isEqualTo("{}");
        assertThat(client.breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void badAnswersDoNotOpenTheBreaker() {
        var model = new FlakyModel();
        var client = new CircuitBreakingLlmClient(model, CircuitBreakingLlmClient.defaultBreaker("t3", Duration.ofMinutes(1)));
        model.failWith = FallbackReason.MALFORMED_JSON; // model is up but answering badly
        for (int i = 0; i < 20; i++) call(client);
        assertThat(client.breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
