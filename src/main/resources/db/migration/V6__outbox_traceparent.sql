-- W3C traceparent of the transaction that wrote the row, so the relay's publish continues the
-- same trace even though it runs later on another thread.
ALTER TABLE outbox ADD COLUMN trace_parent VARCHAR(64);
