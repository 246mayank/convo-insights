-- Transactional outbox: rows are written in the same transaction as the insight they describe,
-- then relayed to Kafka by OutboxRelay. A row with sent_at IS NULL is a pending publish.
CREATE TABLE outbox (
    id          BIGSERIAL PRIMARY KEY,
    topic       VARCHAR(128) NOT NULL,
    record_key  VARCHAR(128) NOT NULL,
    payload     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at     TIMESTAMPTZ,
    attempts    INT          NOT NULL DEFAULT 0,
    last_error  VARCHAR(500)
);

-- The relay only ever scans pending rows, oldest first.
CREATE INDEX ix_outbox_pending ON outbox (id) WHERE sent_at IS NULL;
