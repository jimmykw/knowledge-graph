package net.jimmykw.knowledgegraph.resolve;

import static net.jimmykw.knowledgegraph.resolve.ResolutionFixtures.exact;
import static net.jimmykw.knowledgegraph.resolve.ResolutionFixtures.fuzzy;
import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.entity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.vavr.collection.List;
import io.vavr.control.Option;
import lombok.val;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedEntity;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.EntityQuestion;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.GroupQuestion;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.MergeExisting;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NewGroup;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Question;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.ResolutionResponse;
import net.jimmykw.knowledgegraph.util.JsonPrompter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

class ResolutionPrompterTest {

    private static final List<ExtractedEntity> ENTITIES = List.of(entity("Watson", "Person"), entity("IBM Corp.", "Organization"),
            entity("IBM", "Company"), entity("Apple Watch", "Technology"));
    private static final EntityQuestion WATSON = new EntityQuestion(0, List.of(exact(0, "4:db:11", "Watson", "Technology"),
            exact(0, "4:db:12", "Thomas J. Watson Jr.", "Person", "Watson Jr.")));
    private static final EntityQuestion APPLE_WATCH = new EntityQuestion(3, List.of(fuzzy(3, "4:db:12", "Apple Watch", "Product")));
    private static final GroupQuestion IBM_GROUP = new GroupQuestion(List.of(1, 2));

    private ChatClient client;
    private ResolutionPrompter prompter;

    @BeforeEach
    void setUp() {
        client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        prompter = new ResolutionPrompter(new JsonPrompter<>(client, new BeanOutputConverter<>(ResolutionResponse.class)));
    }

    private void respondWith(String... contents) {
        when(client.prompt(any(Prompt.class)).call().content()).thenReturn(contents[0], java.util.Arrays.copyOfRange(contents, 1, contents.length));
    }

    private static String decisions(String... decisions) {
        return "{\"decisions\": [" + String.join(",", decisions) + "]}";
    }

    private static String decision(String entity, String match, String canonicalName) {
        return "{\"entity\": \"%s\", \"match\": %s, \"canonicalName\": %s}".formatted(entity, quoted(match), quoted(canonicalName));
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private ResolutionRecords.Resolved decide(Question... batch) {
        return prompter.decide(List.of(batch), ENTITIES).getOrElseThrow(failure -> new AssertionError(failure));
    }

    @Test
    void theModelSeesShortReferencesNeverElementIds() {
        respondWith(decisions());
        decide(WATSON, IBM_GROUP, APPLE_WATCH);

        val captor = ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.atLeastOnce()).prompt(captor.capture());
        assertThat(captor.getValue().getContents())
                .contains("n1: \"Watson\" [Person]", "c1: \"Watson\" [Technology]", "c2: \"Thomas J. Watson Jr.\" [Person]",
                        "aliases: Watson Jr.", "edges: -[USES]-> Thing", "n2: \"IBM Corp.\" [Organization]", "options: n3",
                        "n4: \"Apple Watch\" [Technology]")
                .doesNotContain("4:db:");
        assertThat(captor.getValue().getContents()).containsOnlyOnce("c2: \"Thomas J. Watson Jr.\"");
    }

    @Test
    void aValidMatchMergesWithTheCanonicalNameInItsKnownCasing() {
        respondWith(decisions(decision("n1", "c2", "thomas j. watson jr.")));

        val resolved = decide(WATSON);

        assertThat(resolved.merges()).containsExactly(new MergeExisting(0, WATSON.candidates().last(), Option.some("Thomas J. Watson Jr."), "llm"));
        assertThat(resolved.unresolved()).isEmpty();
    }

    @Test
    void aCanonicalNameThatIsNotAKnownNameKeepsTheCandidateName() {
        respondWith(decisions(decision("n1", "c2", "Tom Watson the Younger")));

        assertThat(decide(WATSON).merges()).singleElement().satisfies(merge -> assertThat(merge.canonicalName()).isEmpty());
    }

    @Test
    void aNullMatchCreatesANewNode() {
        respondWith(decisions(decision("n1", null, null)));

        val resolved = decide(WATSON);

        assertThat(resolved.merges()).isEmpty();
        assertThat(resolved.newGroups()).containsExactly(new NewGroup(List.of(0), Option.none()));
        assertThat(resolved.unresolved()).isEmpty();
    }

    @Test
    void aReferenceThatWasNotOfferedForThatEntityIsUnresolved() {
        // c1 exists in the batch (offered to n1) but n2 was only offered c2.
        respondWith(decisions(decision("n1", "c9", null), decision("n2", "c1", null)));

        val resolved = decide(WATSON, APPLE_WATCH);

        assertThat(resolved.merges()).isEmpty();
        assertThat(resolved.unresolved()).containsExactlyInAnyOrder(0, 3);
        assertThat(resolved.newGroups()).extracting(NewGroup::members).containsExactly(List.of(0), List.of(3));
    }

    @Test
    void aMissingDecisionIsUnresolved() {
        respondWith(decisions(decision("n2", "c2", "Apple Watch")));

        val resolved = decide(WATSON, APPLE_WATCH);

        assertThat(resolved.unresolved()).containsExactly(0);
        assertThat(resolved.merges()).extracting(MergeExisting::idx).containsExactly(3);
    }

    @Test
    void groupMembersMatchedToEachOtherBecomeOneNewNode() {
        respondWith(decisions(decision("n1", "n2", "IBM"), decision("n2", null, null)));

        val resolved = decide(IBM_GROUP);

        assertThat(resolved.newGroups()).containsExactly(new NewGroup(List.of(1, 2), Option.some("IBM")));
        assertThat(resolved.unresolved()).isEmpty();
    }

    @Test
    void unmatchedGroupMembersStaySeparate() {
        respondWith(decisions(decision("n1", null, null), decision("n2", "null", null)));

        assertThat(decide(IBM_GROUP).newGroups()).extracting(NewGroup::members).containsExactly(List.of(1), List.of(2));
    }

    @Test
    void aGroupMemberPointingAtItselfOrAnUnofferedReferenceIsUnresolved() {
        respondWith(decisions(decision("n1", "n1", null), decision("n2", "c1", null)));

        val resolved = decide(IBM_GROUP);

        assertThat(resolved.unresolved()).containsExactlyInAnyOrder(1, 2);
        assertThat(resolved.newGroups()).extracting(NewGroup::members).containsExactly(List.of(1), List.of(2));
    }

    @Test
    void anUnparseableReplyFailsTheBatchAfterOneRetry() {
        respondWith("not json", "still not json");

        assertThat(prompter.decide(List.of(WATSON), ENTITIES).isFailure()).isTrue();
    }

    @Test
    void referencesNumberEntitiesAndDistinctCandidatesInBatchOrder() {
        val refs = ResolutionPrompter.References.of(List.of(WATSON, IBM_GROUP, APPLE_WATCH));

        assertThat(refs.entity(0)).isEqualTo("n1");
        assertThat(refs.entity(2)).isEqualTo("n3");
        assertThat(refs.entity(3)).isEqualTo("n4");
        assertThat(refs.candidate("4:db:12")).isEqualTo("c2");
        assertThat(refs.candidate("unknown")).isEmpty();
    }
}
