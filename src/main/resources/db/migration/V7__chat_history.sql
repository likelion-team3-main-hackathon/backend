CREATE TABLE chat_conversations (
  chat_conversation_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  title VARCHAR(100) NOT NULL DEFAULT '새 대화',
  last_message_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6),
  CONSTRAINT fk_chat_conversation_user
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE TABLE chat_messages (
  chat_message_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  chat_conversation_id BIGINT NOT NULL,
  sender_role VARCHAR(20) NOT NULL,
  content TEXT NOT NULL,
  response_type VARCHAR(30),
  pending_ai_action_id BIGINT,
  has_image BOOLEAN NOT NULL DEFAULT FALSE,
  image_object_key VARCHAR(500),
  image_content_type VARCHAR(100),
  created_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_chat_message_conversation
    FOREIGN KEY (chat_conversation_id)
    REFERENCES chat_conversations(chat_conversation_id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_message_pending_action
    FOREIGN KEY (pending_ai_action_id)
    REFERENCES pending_ai_actions(pending_ai_action_id) ON DELETE SET NULL,
  CONSTRAINT chk_chat_message_sender_role
    CHECK (sender_role IN ('USER', 'ASSISTANT')),
  CONSTRAINT chk_chat_message_response_type
    CHECK (response_type IS NULL OR response_type IN (
      'ANSWER', 'CLARIFICATION', 'ACTION_PROPOSAL', 'ACTION_RESULT'
    ))
);

CREATE INDEX idx_chat_conversations_user_recent
  ON chat_conversations(user_id, deleted_at, last_message_at);

CREATE INDEX idx_chat_messages_conversation_recent
  ON chat_messages(chat_conversation_id, chat_message_id);
