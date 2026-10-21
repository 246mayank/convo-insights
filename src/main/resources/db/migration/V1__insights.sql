CREATE TABLE insights (
    id              BIGSERIAL PRIMARY KEY,
    event_id        VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    brand           VARCHAR(64),
    source          VARCHAR(32)  NOT NULL,
    turn_count      INT          NOT NULL,
    summary         VARCHAR(500) NOT NULL,
    sentiment       VARCHAR(16)  NOT NULL,
    intent          VARCHAR(32)  NOT NULL,
    confidence      DOUBLE PRECISION NOT NULL,
    extractor       VARCHAR(16)  NOT NULL,
    fallback_reason VARCHAR(32),
    model           VARCHAR(64),
    prompt_version  VARCHAR(16),
    llm_latency_ms  BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Dedupe key: one insight per source event, enforced by the database, not the app.
CREATE UNIQUE INDEX ux_insights_event_id ON insights (event_id);
CREATE INDEX ix_insights_conversation ON insights (conversation_id);
CREATE INDEX ix_insights_intent ON insights (intent);
CREATE INDEX ix_insights_sentiment ON insights (sentiment);
CREATE INDEX ix_insights_brand ON insights (brand);
CREATE INDEX ix_insights_created_at ON insights (created_at);
