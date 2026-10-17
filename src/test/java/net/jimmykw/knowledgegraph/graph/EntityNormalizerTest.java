package net.jimmykw.knowledgegraph.graph;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.val;
import org.junit.jupiter.api.Test;

class EntityNormalizerTest {

    @Test
    void nullBecomesEmpty() {
        assertThat(EntityNormalizer.normalize(null)).isEmpty();
    }

    @Test
    void trimsAndLowercases() {
        assertThat(EntityNormalizer.normalize("  Thomas J. Watson ")).isEqualTo("thomas j. watson");
    }

    @Test
    void lowercasesWithRootLocale() {
        // Under a Turkish locale 'I' would lowercase to a dotless i; ROOT keeps it a plain 'i'.
        assertThat(EntityNormalizer.normalize("IBM")).isEqualTo("ibm");
        assertThat(EntityNormalizer.normalize("İBM")).isEqualTo("i̇bm");
    }

    @Test
    void isIdempotent() {
        val once = EntityNormalizer.normalize("  Big BLUE ");
        assertThat(EntityNormalizer.normalize(once)).isEqualTo(once);
    }
}
