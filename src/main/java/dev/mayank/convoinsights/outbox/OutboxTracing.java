package dev.mayank.convoinsights.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Carries a trace across the outbox. The writer records the current W3C traceparent on the row;
 * the relay, later and on another thread, starts its publish span as a child of it, so one trace
 * covers webhook or Kafka consume, model call, DB commit and the downstream publish.
 */
@Component
public class OutboxTracing {

    private final Tracer tracer;

    @Autowired
    public OutboxTracing(ObjectProvider<Tracer> tracer) {
        this(tracer.getIfAvailable());
    }

    OutboxTracing(Tracer tracer) {
        this.tracer = tracer;
    }

    public static OutboxTracing disabled() {
        return new OutboxTracing((Tracer) null);
    }

    /** {@code 00-<trace id>-<span id>-01}, or null when nothing is being traced. */
    public String currentTraceParent() {
        if (tracer == null) return null;
        Span span = tracer.currentSpan();
        if (span == null) return null;
        TraceContext c = span.context();
        return "00-" + c.traceId() + "-" + c.spanId() + "-" + (Boolean.TRUE.equals(c.sampled()) ? "01" : "00");
    }

    /** Runs {@code work} inside a new span whose parent is {@code traceParent}; the span ends when {@code work} returns. */
    public <T> T inChildSpan(String traceParent, String name, Supplier<T> work) {
        String[] p = traceParent == null ? null : traceParent.split("-");
        if (tracer == null || p == null || p.length != 4) return work.get();
        TraceContext parent = tracer.traceContextBuilder()
                .traceId(p[1]).spanId(p[2]).sampled("01".equals(p[3])).build();
        Span span = tracer.spanBuilder().setParent(parent).name(name).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return work.get();
        } finally {
            span.end();
        }
    }
}
