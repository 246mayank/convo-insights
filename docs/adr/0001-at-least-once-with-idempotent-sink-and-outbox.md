# ADR 0001: At-least-once delivery, an idempotent sink, and a transactional outbox

**Status:** accepted · 21 Oct 2026

## Context

Each conversation on `conversations.raw` must become exactly one stored insight and be published downstream, even
if the service is killed at any moment. Two facts constrain the design:

1. Kafka redelivers anything not committed, so a consumer either risks loss (commit first) or duplicates (commit last).
2. PostgreSQL and Kafka can't take part in one transaction, so "save the insight and publish it" can't be atomic
   by itself (the dual-write problem).

## Decision

- **Commit offsets manually, after the database commit** (`enable.auto.commit=false`, `AckMode.MANUAL_IMMEDIATE`).
  A crash before the commit means redelivery, never loss.
- **Make storage idempotent.** `eventId` is a SHA-256 content hash computed by the producer, and `insights.event_id`
  has a unique index. A redelivery finds the row and is skipped before the LLM call. Two racing deliveries both pass
  the lookup; the database rejects the second insert, and that path reuses the winner's row.
- **Publish through an outbox.** The insight and its outbox rows (enriched insight, Segment Track call) are inserted
  in one transaction. `OutboxRelay` claims pending rows with `FOR UPDATE SKIP LOCKED`, sends them, waits for broker
  acks, and marks them sent in the same transaction.

## Consequences

- Stored insights: exactly one per eventId, verified by a chaos run of 6 mid-stream SIGKILLs (3,000 / 3,000, 0 duplicates).
- Downstream topics are **at-least-once**: a crash between send and mark re-sends. Records are keyed by eventId so
  consumers can dedupe (Segment dedupes on `messageId`, which we set to the eventId).
- Publishing lags the commit by up to one relay poll (200 ms).
- The LLM call runs outside the transaction, so no connection is held for seconds. The cost is the insert race above,
  which the unique index settles.

## Alternatives rejected

- **Publish inside the listener, then commit** (dual write): a crash between the two loses or orphans the publish.
- **Kafka transactions:** cover Kafka-to-Kafka only; the source of truth is PostgreSQL. (They are used where they do
  apply: the analytics topology runs `exactly_once_v2`.)
- **Two-phase commit:** Kafka doesn't support XA, and 2PC adds coordinator failure modes.
- **Change-data-capture (Debezium) from the WAL:** robust and lower-latency, but adds a connector cluster. It's the next
  step if relay polling becomes a bottleneck.
- **An in-memory dedupe set:** lost on restart, not shared across instances.
