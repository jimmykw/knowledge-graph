package net.jimmykw.knowledgegraph.chat.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import io.vavr.collection.List;
import lombok.val;
import org.junit.jupiter.api.Test;

class GraphCatalogTest {

    @Test
    void tidiesDedupesAndCaches() {
        val loads = new AtomicInteger();
        val catalog = new GraphCatalog(() -> {
            loads.incrementAndGet();
            return new GraphProfile(List.of("HistoryOfIBM.pdf", "historyofibm.PDF", "HistoryOfApple.pdf", " "),
                    List.of("Organization", "Person", "Organization"), List.of("IBM", "Apple Inc.", "IBM"));
        });
        val profile = catalog.get();
        assertThat(profile.documents()).containsExactly("HistoryOfIBM", "historyofibm", "HistoryOfApple");
        assertThat(profile.entityTypes()).containsExactly("Organization", "Person");
        assertThat(profile.topEntities()).containsExactly("IBM", "Apple Inc.");
        catalog.get();
        assertThat(loads).hasValue(1);
    }

    @Test
    void loaderFailureYieldsEmptyProfile() {
        val catalog = new GraphCatalog(() -> {
            throw new IllegalStateException("neo4j down");
        });
        assertThat(catalog.get().isEmpty()).isTrue();
    }
}
