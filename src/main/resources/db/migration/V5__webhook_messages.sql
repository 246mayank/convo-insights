-- Messages received from the Twilio Conversations webhook (onMessageAdded). Twilio may deliver the
-- same webhook more than once, so the message SID is the primary key and repeats are ignored.
CREATE TABLE webhook_messages (
    message_sid      VARCHAR(64)  PRIMARY KEY,
    conversation_sid VARCHAR(64)  NOT NULL,
    msg_index        INT,
    speaker          VARCHAR(16)  NOT NULL,
    customer_hash    VARCHAR(32),
    body             TEXT         NOT NULL,
    source           VARCHAR(32),
    sent_at          TIMESTAMPTZ  NOT NULL,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_webhook_messages_conversation ON webhook_messages (conversation_sid, msg_index, sent_at);
