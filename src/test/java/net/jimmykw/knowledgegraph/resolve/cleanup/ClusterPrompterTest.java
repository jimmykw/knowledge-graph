package net.jimmykw.knowledgegraph.resolve.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import io.vavr.control.Option;
import lombok.val;
import net.jimmykw.knowledgegraph.resolve.cleanup.ClusterPrompter.ClusterMerge;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.ClusterGroup;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.ClusterResponse;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.GraphEntity;
import net.jimmykw.knowledgegraph.util.JsonPrompter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

class ClusterPrompterTest {

    private static final GraphEntity IBM = node("id-1", "IBM", "Organization", "International Business Machines");
    private static final GraphEntity IBM_CORP = node("id-2", "IBM Corp.", "Company");
    private static final GraphEntity WATSON = node("id-3", "Watson", "Person");
    private static final List<GraphEntity> CLUSTER = List.of(IBM, IBM_CORP, WATSON);

    private static GraphEntity node(String id, String name, String label, String... aliases) {
        return new GraphEntity(id, name, label, java.util.List.of("Entity", label), name + " node",
                List.of(aliases).prepend(name).toJavaList(), 1);
    }

    private static ClusterResponse response(java.util.List<ClusterGroup> groups, String... unsure) {
        return new ClusterResponse(groups, java.util.List.of(unsure));
    }

    @Test
    void validGroupsMergeWithAKnownCanonicalName() {
        val outcome = ClusterPrompter.interpret(CLUSTER, response(java.util.List.of(
                new ClusterGroup(java.util.List.of("m1", " m2"), "international business machines")), "m3"));

        assertThat(outcome.merges()).containsExactly(new ClusterMerge(List.of(IBM, IBM_CORP), Option.some("International Business Machines")));
        assertThat(outcome.unsure()).containsExactly(WATSON);
    }

    @Test
    void anUnknownCanonicalNameIsDropped() {
        val outcome = ClusterPrompter.interpret(CLUSTER, response(java.util.List.of(new ClusterGroup(java.util.List.of("m1", "m2"), "Big Blue"))));

        assertThat(outcome.merges()).singleElement().satisfies(merge -> assertThat(merge.canonicalName()).isEmpty());
    }

    @Test
    void groupsThatReuseOrInventReferencesAreNotTrustedAndGoToReview() {
        val outcome = ClusterPrompter.interpret(CLUSTER, response(java.util.List.of(
                new ClusterGroup(java.util.List.of("m1", "m2"), null),
                new ClusterGroup(java.util.List.of("m2", "m3"), null),
                new ClusterGroup(java.util.List.of("m9", "m1"), null))));

        assertThat(outcome.merges()).isEmpty();
        assertThat(outcome.unsure()).containsExactlyInAnyOrder(IBM, IBM_CORP, WATSON);
    }

    @Test
    void singleMemberGroupsAndNullsAreIgnored() {
        val outcome = ClusterPrompter.interpret(CLUSTER, new ClusterResponse(java.util.Arrays.asList(
                new ClusterGroup(java.util.List.of("m1"), "IBM"), null), null));

        assertThat(outcome.merges()).isEmpty();
        assertThat(outcome.unsure()).isEmpty();
    }

    @Test
    void thePromptShowsReferencesAliasesAndEdgesButNoElementIds() {
        val client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt(any(Prompt.class)).call().content()).thenReturn("{\"groups\": [], \"unsure\": []}");
        val prompter = new ClusterPrompter(new JsonPrompter<>(client, new BeanOutputConverter<>(ClusterResponse.class)));

        val prompt = prompter.buildPrompt(CLUSTER, HashMap.of("id-1", List.of("-[ACQUIRED]-> Lotus")));

        assertThat(prompt.getContents()).contains("m1: \"IBM\" [Organization]", "aliases: International Business Machines",
                "edges: -[ACQUIRED]-> Lotus", "m3: \"Watson\" [Person]").doesNotContain("id-1");
        assertThat(prompter.decide(CLUSTER, HashMap.empty()).isSuccess()).isTrue();
    }
}
