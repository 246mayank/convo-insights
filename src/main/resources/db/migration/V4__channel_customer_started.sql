-- Metadata for cross-channel ingest and the analytics topology:
-- which channel a conversation came from, who the customer is, and when it started (event time).
ALTER TABLE insights ADD COLUMN channel VARCHAR(16);
ALTER TABLE insights ADD COLUMN customer_id VARCHAR(64);
ALTER TABLE insights ADD COLUMN started_at TIMESTAMPTZ;
CREATE INDEX ix_insights_customer ON insights (customer_id);
