# Throughput pilot (full pipeline, real model)

Kafka -> consumer -> PII redaction -> Ollama -> validation -> Postgres + outbox, on real TWCS conversations.
Throughput = (events - 1) / (last insight created_at - first created_at). Latency = time waiting on the model,
which includes queueing inside Ollama when several consumer threads share it.
Machine: i7-12700H, 16 GB, CPU only, Ollama 0.40.7 (default parallelism). Command: `scripts/pilot.sh M C MODEL`.

| Model | Consumer threads | Events | Seconds | Events/s | p50 model ms | p95 model ms | Fallback | Commit |
|---|---:|---:|---:|---:|---:|---:|---|---|
| qwen2.5:3b | 1 | 100 | 550.2 | **0.18** | 4115 | 6934 | 0.0% (none) | `dfd395e` |
| qwen2.5:3b | 3 | 100 | 438.6 | **0.226** | 12712 | 16418 | 0.0% (none) | `dfd395e` |
