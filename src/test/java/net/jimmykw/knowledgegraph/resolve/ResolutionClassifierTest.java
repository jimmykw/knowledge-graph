package net.jimmykw.knowledgegraph.resolve;

import static net.jimmykw.knowledgegraph.resolve.ResolutionFixtures.exact;
import static net.jimmykw.knowledgegraph.resolve.ResolutionFixtures.fuzzy;
import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.entity;
import static org.assertj.core.api.Assertions.assertThat;

import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import lombok.val;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.EntityQuestion;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.GroupQuestion;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.MergeExisting;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NewGroup;
import org.junit.jupiter.api.Test;

class ResolutionClassifierTest {

    @Test
    void noCandidatesAndNoSiblingIsNew() {
        val result = ResolutionClassifier.classify(List.of(entity("IBM", "Organization")), HashMap.empty());

        assertThat(result.questions()).isEmpty();
        assertThat(result.automatic().newGroups()).containsExactly(new NewGroup(List.of(0), io.vavr.control.Option.none()));
    }

    @Test
    void aSoleExactCandidateWithTheSameLabelMergesWithoutTheLlm() {
        val candidate = exact(0, "n-1", "Apple", "Organization");

        val result = ResolutionClassifier.classify(List.of(entity("Apple Inc.", "Organization")), HashMap.of(0, List.of(candidate,
                fuzzy(0, "n-2", "Apple Watch", "Product"))));

        assertThat(result.questions()).isEmpty();
        assertThat(result.automatic().merges()).singleElement().satisfies(merge -> {
            assertThat(merge.target()).isEqualTo(candidate);
            assertThat(merge.canonicalName()).isEmpty();
            assertThat(merge.reason()).isEqualTo("match_key");
        });
    }

    @Test
    void anAliasHitIsReportedAsExact() {
        val candidate = exact(0, "n-1", "IBM", "Organization", "International Business Machines");

        assertThat(ResolutionClassifier.reason(entity("international business machines", "Organization"), candidate)).isEqualTo("exact");
    }

    @Test
    void anExactCandidateWithAnotherLabelGoesToTheLlm() {
        val candidates = List.of(exact(0, "n-1", "Watson", "Technology"));

        val result = ResolutionClassifier.classify(List.of(entity("Watson", "Person")), HashMap.of(0, candidates));

        assertThat(result.automatic().merges()).isEmpty();
        assertThat(result.questions()).containsExactly(new EntityQuestion(0, candidates));
    }

    @Test
    void severalExactCandidatesOrFuzzyOnlyCandidatesGoToTheLlm() {
        val duplicates = List.of(exact(0, "n-1", "IBM", "Organization"), exact(0, "n-2", "IBM", "Organization"));
        val fuzzyOnly = List.of(fuzzy(1, "n-3", "IBM Research", "Organization"));

        val result = ResolutionClassifier.classify(List.of(entity("IBM", "Organization"), entity("IBM Labs", "Organization")),
                HashMap.of(0, duplicates, 1, fuzzyOnly));

        assertThat(result.questions()).containsExactly(new EntityQuestion(0, duplicates), new EntityQuestion(1, fuzzyOnly));
    }

    @Test
    void batchSiblingsSharingAMatchKeyWithDifferentLabelsAreAGroupQuestion() {
        val entities = List.of(entity("IBM", "Organization"), entity("Lonely", "Person"), entity("IBM Corp.", "Company"));

        val result = ResolutionClassifier.classify(entities, HashMap.empty());

        assertThat(result.questions()).containsExactly(new GroupQuestion(List.of(0, 2)));
        assertThat(result.automatic().newGroups()).extracting(NewGroup::members).containsExactly(List.of(1));
    }

    @Test
    void batchSiblingsSharingAMatchKeyAndALabelBecomeOneNewNode() {
        val entities = List.of(entity("Apple", "Organization"), entity("Apple Inc.", "Organization"));

        val result = ResolutionClassifier.classify(entities, HashMap.empty());

        assertThat(result.questions()).isEmpty();
        assertThat(result.automatic().newGroups()).extracting(NewGroup::members).containsExactly(List.of(0, 1));
    }

    @Test
    void anEmptyCandidateListCountsAsNoCandidates() {
        val result = ResolutionClassifier.classify(List.of(entity("IBM", "Organization")), HashMap.of(0, List.empty()));

        assertThat(result.automatic().newGroups()).hasSize(1);
        assertThat(result.automatic().merges()).extracting(MergeExisting::idx).isEmpty();
    }
}
