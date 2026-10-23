# Official run v2: the same 4,000 conversations on the current code

Re-run of [run 1](run-official.md) after the PII redaction fixes ([audit](redaction-audit-20261023.md)): the same
4,000 TWCS conversations (lines 15201–19200), the same model and prompt, a fresh database (`insights_v2`) and fresh
topics (`*.v2`), so nothing from earlier runs could be deduped away.

| | Run 1 (21 Oct, redaction v1) | **Run 2 (23 Oct, redaction v2)** |
|---|---:|---:|
| Conversations stored | 4,000 / 4,000 | **4,000 / 4,000** (4,000 distinct eventIds, 0 stored twice) |
| Extracted by the LLM | 4,000 (0 fallbacks) | **4,000 (0 fallbacks)** |
| Dead-lettered | 0 | **0** |
| Model latency p50 / p95 | 4,222 / 6,808 ms | **4,113 / 6,617 ms** |
| Throughput (first to last insight) | 0.204 events/s (incl. a 21.5 min laptop sleep) | **0.225 events/s** (17,766 s) |
| PII redactions | 18,479 | **19,237** |

| | |
|---|---|
| Code | `9d0b18e` (redaction v2) |
| Model | `ollama/qwen2.5:3b`, prompt v1, temperature 0, 1 consumer thread |
| Machine | i7-12700H, 16 GB, CPU only, Ollama 0.40.8 |
| Started / finished | 23 Oct 16:07 / 21:03 IST |
| Command | `scripts/official-run-v2.sh` |

The wrapper shell that launched the run was cut off by the tool runner a few hours in; the service and its consumer kept
running (all 4,000 events were already on Kafka), and the report numbers were taken from the database with the same SQL as
`scripts/pilot.sh` once the count reached 4,000. Snapshots: `run-official-v2-prometheus.txt`, `run-official-v2-stats.json`.
