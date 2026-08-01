package net.jimmykw.knowledgegraph.chat.store;

import java.util.List;

import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;

@Slf4j
@RequiredArgsConstructor
public class H2ChatMemoryRepository implements ChatMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<String> findConversationIds() {
        return Try.of(() -> jdbcTemplate.queryForList(
                        "SELECT DISTINCT conversation_id FROM chat_messages", String.class))
                .onFailure(ex -> log.warn("Failed to load conversation ids", ex))
                .getOrElse(List.of());
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return Try.of(() -> jdbcTemplate.query(
                        "SELECT message_type, content FROM chat_messages WHERE conversation_id = ? ORDER BY seq",
                        (rs, rowNum) -> toMessage(rs.getString("message_type"), rs.getString("content")),
                        conversationId))
                .onFailure(ex -> log.warn("Failed to load conversation history for {}", conversationId, ex))
                .getOrElse(List.of());
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Try.run(() -> {
                    jdbcTemplate.update("DELETE FROM chat_messages WHERE conversation_id = ?", conversationId);
                    for (int seq = 0; seq < messages.size(); seq++) {
                        val message = messages.get(seq);
                        jdbcTemplate.update(
                                "INSERT INTO chat_messages (conversation_id, seq, message_type, content) VALUES (?, ?, ?, ?)",
                                conversationId, seq, message.getMessageType().name(), message.getText());
                    }
                })
                .onFailure(ex -> log.error("Failed to persist conversation history for {}", conversationId, ex));
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        Try.of(() -> jdbcTemplate.update(
                        "DELETE FROM chat_messages WHERE conversation_id = ?", conversationId))
                .onFailure(ex -> log.warn("Failed to delete conversation history for {}", conversationId, ex));
    }

    private static Message toMessage(String messageType, String content) {
        val type = MessageType.valueOf(messageType);
        return switch (type) {
            case USER -> new UserMessage(content);
            case ASSISTANT -> new AssistantMessage(content);
            case SYSTEM -> new SystemMessage(content);
            case TOOL -> new UserMessage(content);
        };
    }
}