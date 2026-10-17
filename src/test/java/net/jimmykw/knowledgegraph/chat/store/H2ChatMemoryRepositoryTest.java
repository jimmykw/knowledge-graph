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

    private static int dbCounter;

    private static H2ChatMemoryRepository freshRepository() {
        val ds = new DriverManagerDataSource("jdbc:h2:mem:repo-test-" + (dbCounter++) + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        return new H2ChatMemoryRepository(new JdbcTemplate(ds));
    }

    @Test
    void conversationsAreIsolatedAndOrderedBySeq() {
        val repo = freshRepository();
        repo.saveAll("a", List.of(new UserMessage("a1"), new AssistantMessage("a2"), new UserMessage("a3")));
        repo.saveAll("b", List.of(new UserMessage("b1")));

        assertThat(repo.findByConversationId("a")).extracting(message -> message.getText()).containsExactly("a1", "a2", "a3");
        assertThat(repo.findByConversationId("b")).extracting(message -> message.getText()).containsExactly("b1");
        assertThat(repo.findByConversationId("missing")).isEmpty();
    }

    @Test
    void saveAllReplacesThePreviousMessagesOfAConversation() {
        val repo = freshRepository();
        repo.saveAll("a", List.of(new UserMessage("old1"), new AssistantMessage("old2")));
        repo.saveAll("a", List.of(new UserMessage("old1"), new AssistantMessage("old2"), new UserMessage("new3")));

        assertThat(repo.findByConversationId("a")).extracting(message -> message.getText()).containsExactly("old1", "old2", "new3");
    }

    @Test
    void messageTypesSurviveTheRoundTrip() {
        val repo = freshRepository();
        repo.saveAll("a", List.of(new UserMessage("q"), new AssistantMessage("r")));

        assertThat(repo.findByConversationId("a")).extracting(message -> message.getMessageType().name())
                .containsExactly("USER", "ASSISTANT");
    }

    @Test
    void deleteByConversationIdOnlyRemovesThatConversation() {
        val repo = freshRepository();
        repo.saveAll("a", List.of(new UserMessage("a1")));
        repo.saveAll("b", List.of(new UserMessage("b1")));

        repo.deleteByConversationId("a");

        assertThat(repo.findByConversationId("a")).isEmpty();
        assertThat(repo.findByConversationId("b")).hasSize(1);
    }

    @Test
    void findConversationIdsListsEachConversationOnce() {
        val repo = freshRepository();
        assertThat(repo.findConversationIds()).isEmpty();
        repo.saveAll("a", List.of(new UserMessage("1"), new UserMessage("2")));
        repo.saveAll("b", List.of(new UserMessage("3")));

        assertThat(repo.findConversationIds()).containsExactlyInAnyOrder("a", "b");
    }

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
