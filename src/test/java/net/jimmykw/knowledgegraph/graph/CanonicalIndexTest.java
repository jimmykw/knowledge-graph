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
                .put(IBM, 1L)
                .put(new ExtractedEntity("  ibm ", "Organization", "dup"), 2L);

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.lookup("IBM", "Organization")).contains(1L);
        assertThat(index.entities()).containsExactly(IBM);
    }

    @Test
    void lookupIsExactOnNormalizedNameAndLabel() {
        val index = CanonicalIndex.empty().put(IBM, 7L);
        assertThat(index.lookup("  ibm", "Organization")).contains(7L);
    }

    @Test
    void lookupFallsBackToNameWhenLabelDiffers() {
        val index = CanonicalIndex.empty().put(IBM, 7L);
        assertThat(index.lookup("IBM", "Company")).contains(7L);
    }

    @Test
    void exactLabelMatchWinsOverNameOnlyFallback() {
        val index = CanonicalIndex.empty()
                .put(new ExtractedEntity("Apple", "Fruit", ""), 1L)
                .put(new ExtractedEntity("Apple", "Organization", ""), 2L);
        assertThat(index.lookup("Apple", "Organization")).contains(2L);
        assertThat(index.lookup("Apple", "Fruit")).contains(1L);
    }

    @Test
    void lookupMissReturnsNone() {
        val index = CanonicalIndex.empty().put(IBM, 7L);
        assertThat(index.lookup("Nobody", "Person")).isEmpty();
    }

    @Test
    void entitiesKeepInsertionOrder() {
        val index = CanonicalIndex.empty().put(WATSON, 1L).put(IBM, 2L);
        assertThat(index.entities()).containsExactly(WATSON, IBM);
        assertThat(index.size()).isEqualTo(2);
    }
}
