# ADR 0003: A local model, chosen by a measured bake-off

**Status:** accepted · 21 Oct 2026

## Context

The extractor needs a model that runs reproducibly in development and CI, costs nothing per call, has no rate limits,
and doesn't send customer text off the machine. Model size trades accuracy for latency, and accuracy on synthetic
data may not carry over to real support threads.

## Decision

Run open models locally with Ollama, and pick the size by measurement rather than by assumption. Every candidate went
through the same harness (`EvalMain`) on two labelled sets: 200 synthetic Bitext messages (stratified across 11
categories) and 48 real TWCS conversations hand-labelled for intent and sentiment.

| Extractor | Golden accuracy | Real accuracy | p95 (real) |
|---|---:|---:|---:|
| qwen2.5:3b | 85.0% | 39.6% | 5.5 s |
| qwen2.5:1.5b | 75.0% | 27.1% | 3.0 s |
| rules | 62.0% | 33.3% | |
| qwen2.5:0.5b | 41.0% | 10.4% | 1.6 s |

**qwen2.5:3b is the default**: the only model that beats the rules on both sets. (Numbers re-measured after redaction v2; the first run gave 86.0% / 43.8% for 3B, same ranking.)

## Consequences

- About 4 s per conversation on CPU (official run: p50 4.2 s, p95 6.8 s, 0.204 conversations/s). Extra consumer
  threads mostly queue inside Ollama (0.23/s with 3 threads, p95 16.4 s), so production needs GPU serving.
- The same harness runs in CI as a regression gate, against a baseline measured on the CI runner.
- A hosted model (Gemini) is available behind the same `LlmClient` interface; PII redaction (ADR 0005) is what makes
  that acceptable.

## Lessons

- A "better" prompt with few-shot examples (v2) lowered accuracy to 50.0% (golden) and 14.6% (real), because the 1.5B
  model copied words from the examples. It was rejected; v1 stays. Prompt changes are measured, not assumed.
- The real set was looked at while writing v2, so it's a development set. A separate held-out set is being labelled.
