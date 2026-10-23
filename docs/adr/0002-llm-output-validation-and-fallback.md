# ADR 0002: Validate every LLM answer and fall back to rules

**Status:** accepted · 21 Oct 2026

## Context

The model runs on CPU and is small (3B parameters). Even with a JSON schema passed to Ollama as `format`, output
can be truncated at the token limit, use the wrong scale (confidence 90 instead of 0.9), or name an intent that
doesn't exist. The model can also be slow or down. None of this may stall a Kafka partition, because a stalled
partition blocks every conversation behind it.

## Decision

- `LlmOutputValidator` parses the answer and checks every field (intent and sentiment in their enums, a summary of
  1–300 characters, confidence in [0, 1]). Two narrow repairs only: strip text around the outermost `{…}` (code
  fences, chatter), and read a 1–100 confidence as a percentage.
- Anything else is rejected with a reason, and `RuleBasedExtractor` produces the insight. The row stores
  `extractor=RULES` and `fallback_reason` (`MALFORMED_JSON`, `SCHEMA_VIOLATION`, `TIMEOUT`, `HTTP_ERROR`, `CIRCUIT_OPEN`).
- `LlmInsightExtractor` never throws for model problems. Only infrastructure failures (for example the database)
  reach the Kafka error handler and its retries.
- A **circuit breaker** (resilience4j) opens after 50% failures in the last 20 calls (minimum 5), skips the model for
  30 s, then lets 3 trial calls through. Malformed answers don't count as failures: the model is up, just answering badly.
- Prompts are versioned files; each insight records its prompt version and model.

## Consequences

- The stream keeps moving through bad answers, timeouts and outages. Every fallback is visible per row and in
  metrics (`insights_fallback_total{reason}`).
- Fallback insights are lower quality (rules score 62.0% vs 85.0% on the golden set). Dashboards should watch the fallback rate.
- In the official run of 4,000 real conversations the fallback rate was 0%.

## Alternatives rejected

- **Retry the model until it answers well:** unbounded latency, and a deterministic bad answer never improves.
- **Send failures to the dead-letter topic:** loses an insight for a problem that the rules can cover.
- **Trust structured output without validation:** the first probe returned a confidence of 90 under a schema that only said "number", and constrained decoding can't stop truncation at the token limit. The schema narrows the output; the validator decides.
