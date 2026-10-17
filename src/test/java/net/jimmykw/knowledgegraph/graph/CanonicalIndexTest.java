package net.jimmykw.knowledgegraph.graph;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.val;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedEntity;
import org.junit.jupiter.api.Test;

class CanonicalIndexTest {

    private static final ExtractedEntity IBM = new ExtractedEntity("IBM", "Organization", "A company");
    private static final ExtractedEntity WATSON = new ExtractedEntity("Watson", "Person", "A person");

    @Test
    void emptyIndexHasNoEntities() {
        val index = CanonicalIndex.empty();
        assertThat(index.size()).isZero();
        assertThat(index.entities()).isEmpty();
        assertThat(index.lookup("IBM", "Organization")).isEmpty();
    }

    @Test
    void putOfExistingNormalizedNameAndLabelKeepsFirstId() {
        val index = CanonicalIndex.empty()
                .put(IBM, "id-1")
                .put(new ExtractedEntity("  ibm ", "Organization", "dup"), "id-2");

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.lookup("IBM", "Organization")).contains("id-1");
        assertThat(index.entities()).containsExactly(IBM);
    }

    @Test
    void lookupIsExactOnNormalizedNameAndLabel() {
        val index = CanonicalIndex.empty().put(IBM, "id-7");
        assertThat(index.lookup("  ibm", "Organization")).contains("id-7");
    }

    @Test
    void lookupFallsBackToNameWhenLabelDiffers() {
        val index = CanonicalIndex.empty().put(IBM, "id-7");
        assertThat(index.lookup("IBM", "Company")).contains("id-7");
    }

    @Test
    void exactLabelMatchWinsOverNameOnlyFallback() {
        val index = CanonicalIndex.empty()
                .put(new ExtractedEntity("Apple", "Fruit", ""), "id-1")
                .put(new ExtractedEntity("Apple", "Organization", ""), "id-2");
        assertThat(index.lookup("Apple", "Organization")).contains("id-2");
        assertThat(index.lookup("Apple", "Fruit")).contains("id-1");
    }

    @Test
    void lookupMissReturnsNone() {
        val index = CanonicalIndex.empty().put(IBM, "id-7");
        assertThat(index.lookup("Nobody", "Person")).isEmpty();
    }

    @Test
    void entitiesKeepInsertionOrder() {
        val index = CanonicalIndex.empty().put(WATSON, "id-1").put(IBM, "id-2");
        assertThat(index.entities()).containsExactly(WATSON, IBM);
        assertThat(index.size()).isEqualTo(2);
    }
}
