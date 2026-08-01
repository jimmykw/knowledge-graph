CREATE TABLE IF NOT EXISTS chat_messages (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(36)  NOT NULL,
    seq             INTEGER     NOT NULL,
    message_type    VARCHAR(16) NOT NULL,
    content         CLOB        NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_chat_messages_conv ON chat_messages (conversation_id, seq);