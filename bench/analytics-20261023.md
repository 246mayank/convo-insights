# Analytics check: Kafka Streams aggregates vs the database

The analytics topology (`AnalyticsTopology`, exactly-once v2, event-time day buckets) consumed `insights.enriched`
after a full republish of the 16,300 stored insights (4,000 LLM-extracted from the official run, the rest
rules-extracted from chaos and pilot runs). The topic also still held the earlier, pre-backfill copies of most
of those insights, so every eventId arrived at least twice.

| Check | Result |
|---|---|
| Brand-day buckets compared, Streams store vs `GROUP BY brand, day, sentiment` in Postgres | **3,859 across 107 brands** |
| Buckets whose positive / neutral / negative counts differ | **0** |
| Spike alerts active (>= 20 conversations and >= 80% negative in a brand-day) | **1**: Uber_Support, 2017-12-02, 18 of 21 negative (85.7%) |

Duplicates on the input changed nothing because the topology aggregates from a KTable keyed by eventId
(a re-delivered insight replaces its earlier version; the aggregate subtracts the old contribution first).

How it was produced (23 Oct 2026, local stack): backfill `channel`, `customer_id`, `started_at` from
`data/conversations.jsonl` by eventId; run the `republish` profile; start the service; compare
`GET /api/brands/{brand}/sentiment` for every brand with the SQL aggregate; read `alerts.sentiment-spike` from the beginning.
