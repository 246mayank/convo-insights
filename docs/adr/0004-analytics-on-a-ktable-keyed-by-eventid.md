# ADR 0004: Streaming analytics on a KTable keyed by eventId, bucketed by event time

**Status:** accepted · 23 Oct 2026

## Context

The analytics (sentiment per brand per day, negative-spike alerts, customer profiles) read `insights.enriched`. That
topic is at-least-once (ADR 0001): the relay can re-send, and a republish sends everything again. A windowed count over
the raw stream would count every re-send. Replayed data is from 2017, so processing-time windows would put a month of
conversations into one minute.

## Decision

- Read the topic as a **KTable keyed by eventId** (the latest version of each insight), and aggregate from that table
  with an adder and a subtractor. When an insight arrives again, Kafka Streams subtracts its old contribution and adds
  the new one, so counts are exact under redelivery and under updates.
- Bucket by **event time** (`startedAt`, when the customer wrote), as `brand|yyyy-MM-dd`.
- **Spike alerts** = a KTable filter (≥ 20 conversations and ≥ 80% negative in a brand-day). When a bucket drops below
  the threshold, the filter emits a tombstone, so `alerts.sentiment-spike` always holds the current set of active alerts.
- Run with `processing.guarantee=exactly_once_v2`: Kafka-to-Kafka is where Kafka's own exactly-once applies.
- Serve the state stores directly (interactive queries) instead of copying aggregates into a second database.

## Consequences

- Verified: 3,859 brand-day buckets across 107 brands matched a SQL `GROUP BY` over PostgreSQL exactly, with every
  insight delivered at least twice. One real spike: Uber_Support, 2017-12-02, 18 of 21 negative.
- No windows to close, so late data simply updates its bucket.
- The eventId table grows with every event. Production needs retention (a TTL or windowed store), which is not built.
- With several instances, each holds only some partitions' state; queries would be routed by key metadata (not built).

## Alternatives rejected

- **Windowed KStream counts with grace periods:** double-count re-sent insights.
- **Aggregate in PostgreSQL on read:** simple, but it shifts load onto the operational database and isn't streaming.
