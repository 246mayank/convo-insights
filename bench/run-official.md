# Official run: 4,000 real support conversations through the LLM pipeline

**4,000 / 4,000** real TWCS conversations went through the full pipeline (Kafka → consumer → PII redaction → qwen2.5:3b →
validation → Postgres + outbox → `insights.enriched`), with **0 fallbacks to rules, 0 dead-lettered and 0 lost**.

| | |
|---|---|
| Commit | `381364a` |
| Data | TWCS lines 15201–19200 of `data/conversations.jsonl` (4,000 conversations; top brands: AmazonHelp 421, AppleSupport 413, Uber_Support 210) |
| Model | `ollama/qwen2.5:3b`, prompt v1, temperature 0, 1 consumer thread |
| Machine | i7-12700H, 16 GB RAM, CPU only, Ollama 0.40.7 |
| Started / finished | 21 Oct 21:12 IST / 22 Oct 02:41 IST |
| Command | `OFFSET_LINE=15201 PORT=8080 REPORT=bench/run-official.md scripts/pilot.sh 4000 1 qwen2.5:3b` |

## Results

| Measure | Value | Source |
|---|---|---|
| Insights stored | **4,000** (4,000 distinct eventIds) | `insights` table |
| Extracted by the LLM | **4,000 (100%)**, 0 fallbacks | `insights_processed_total{extractor="LLM"}` = 4000, no `insights_fallback_total` |
| Dead-lettered | **0** | `insights_dlt_total` = 0 |
| Model latency | p50 **4.2 s**, p95 **6.8 s** | `llm_latency_ms` per row |
| Throughput, wall clock | 0.204 events/s (19,619 s) | first to last `created_at` |
| Throughput, excluding the 21.5 min standby | about 0.218 events/s (about 18,330 s) | same, minus 23:00:45–23:22:15 |
| PII redactions | 18,479 across 4,000 conversations (every conversation had at least one, mostly @handles) | `pii_redactions` |
| Duplicates skipped | 12,200 | `insights_duplicates_total` |
| Average thread length | 3.37 turns | `turn_count` |

The **12,200 duplicates** are earlier events (chaos runs, pilots, a debug run) already on the topic. This run's fresh consumer
group read the topic from the start, and every one of them was recognised by `eventId` and skipped without calling the model.

Intent mix: TECHNICAL_SUPPORT 1,181 · DELIVERY 553 · OTHER 460 · SUBSCRIPTION 410 · PAYMENT 279 · ORDER 252 · REFUND 216 ·
SHIPPING 183 · FEEDBACK 163 · INVOICE 100 · ACCOUNT 98 · CONTACT 72 · CANCEL 33.
Sentiment: NEGATIVE 2,903 · POSITIVE 645 · NEUTRAL 452.
These are the model's labels. On the hand-labelled set, 3B intent accuracy is 43.8% (see `eval/BAKEOFF.md`), so treat the mix as indicative.

## Unplanned outage during the run

The laptop entered Modern Standby at **23:00:45** and woke at **23:22:15** (Windows Kernel-Power events 172 / 507). On wake:

- the Kafka coordinator connection was gone and was re-established automatically;
- all 9 pooled Postgres connections were dead; the first write failed (`SQLState 08003`), the error handler retried it, and it succeeded;
- processing resumed with no manual action. Nothing was dead-lettered or lost, and no event was stored twice.

The gap is visible in the dashboard below (throughput drops to zero, then a one-off latency spike as the model reloads).

![Grafana dashboard for the run](../docs/img/dashboard.png)

Raw snapshots taken at the end of the run: `run-official-prometheus.txt` (all meters) and `run-official-stats.json`
(`/api/insights/stats`, which counts every row in the database, not only this run's).
