package net.jimmykw.knowledgegraph.chat.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class H2ChatMemoryRepositoryTest {

    @Test
    void blockedFlagSurvivesRoundTrip() {
        val ds = new DriverManagerDataSource("jdbc:h2:mem:blocked-test;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds); // idempotent on an existing table
        val repo = new H2ChatMemoryRepository(new JdbcTemplate(ds));
        repo.saveAll("c", List.of(new UserMessage("q1"), new AssistantMessage("a1"),
                BlockedMessages.user("q2"), BlockedMessages.assistant("a2")));
        val loaded = repo.findByConversationId("c");
        assertThat(loaded).extracting(BlockedMessages::isBlocked).containsExactly(false, false, true, true);
        assertThat(loaded).extracting(m -> m.getText()).containsExactly("q1", "a1", "q2", "a2");
    }
}
