# convo-insights

An event-driven Spring Boot service. It consumes customer-support conversations from Kafka, extracts a **summary, sentiment and intent** for each one, stores the result in PostgreSQL, publishes it to a downstream topic and serves it over a REST API.

> Work in progress (Oct 2026). Numbers in this README are measured from the repo and updated as features land.

## Architecture

```
TWCS csv ──build_conversations.py──▶ conversations.jsonl ──replay──▶ [conversations.raw]
                                                                        │ key = conversationId
                                                                        ▼
                                        ConversationConsumer (manual ack, 3 threads)
                                          │ parse + validate ──(invalid)──▶ [conversations.raw.DLT]
                                          │ dedupe on eventId
                                          │ InsightExtractor
                                          ▼
                                   PostgreSQL insights ◀── GET /api/insights
                                          │
                                          ▼
                                   [insights.enriched] ──▶ ack offset
```

## Delivery semantics

- **At least once.** Auto-commit is off and the listener uses `MANUAL_IMMEDIATE`. The offset is acknowledged only after the insight row is committed **and** the enriched record has been acked by the broker.
- **Idempotent.** `eventId` is a content hash computed by the producer, and `insights.event_id` has a unique index. A redelivered event skips extraction and re-publishes the stored insight. A concurrent duplicate that loses the insert race reuses the winner's row.
- **Bounded retries.** Exponential backoff (1 s, 2 s, 4 s), then the record goes to `conversations.raw.DLT`. Records that can never succeed (unparseable or invalid) skip the retries.

## Run it

Requires JDK 21 and Docker.

```bash
docker compose up -d                       # Kafka (KRaft) + Postgres + topic creation
./mvnw -DskipTests package
java -jar target/convo-insights-0.0.1-SNAPSHOT.jar           # consumer + REST on :8080

# build conversations from the Kaggle "Customer Support on Twitter" corpus, then replay them
python scripts/build_conversations.py path/to/twcs.csv data/conversations.jsonl --limit 5000
java -jar target/convo-insights-0.0.1-SNAPSHOT.jar --spring.profiles.active=replay \
     --replay.file=data/conversations.jsonl --replay.limit=1000

curl localhost:8080/api/insights/stats
curl "localhost:8080/api/insights?intent=REFUND&sentiment=NEGATIVE&size=5"
```

## Data

- **Replay:** [Customer Support on Twitter](https://www.kaggle.com/datasets/thoughtvector/customer-support-on-twitter) (CC BY-NC-SA 4.0). Real tweets, threaded into conversations through `in_response_to_tweet_id`. This repo does not include the data.
