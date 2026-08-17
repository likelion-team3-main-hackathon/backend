ALTER TABLE chat_messages
    DROP CONSTRAINT chk_chat_message_response_type;

ALTER TABLE chat_messages
    ADD CONSTRAINT chk_chat_message_response_type
    CHECK (
        response_type IS NULL
        OR response_type IN (
            'ANSWER',
            'CLARIFICATION',
            'ACTION_PROPOSAL',
            'ACTION_RESULT',
            'ACTION_EXECUTED',
            'ACTION_CANCELLED'
        )
    );
