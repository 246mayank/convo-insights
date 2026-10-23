# Architecture decision records

| # | Decision |
|---|---|
| [0001](0001-at-least-once-with-idempotent-sink-and-outbox.md) | At-least-once delivery, an idempotent sink, and a transactional outbox |
| [0002](0002-llm-output-validation-and-fallback.md) | Validate every LLM answer and fall back to rules (plus a circuit breaker) |
| [0003](0003-local-model-chosen-by-bake-off.md) | A local model, chosen by a measured bake-off |
| [0004](0004-analytics-on-a-ktable-keyed-by-eventid.md) | Streaming analytics on a KTable keyed by eventId, bucketed by event time |
| [0005](0005-pii-redaction-before-inference.md) | Redact PII before any model sees the text |
