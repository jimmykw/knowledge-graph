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

    @Test
    void matchKeyStripsPunctuationAndCorporateSuffixes() {
        assertThat(EntityNormalizer.matchKey("Apple Inc.")).isEqualTo("apple");
        assertThat(EntityNormalizer.matchKey("Apple")).isEqualTo("apple");
        assertThat(EntityNormalizer.matchKey("International Business Machines Corporation")).isEqualTo("international business machines");
        assertThat(EntityNormalizer.matchKey("Acme Co., Ltd.")).isEqualTo("acme");
        assertThat(EntityNormalizer.matchKey("Foo Holdings PLC")).isEqualTo("foo holdings");
    }

    @Test
    void matchKeyRemovesALeadingTheAndCollapsesWhitespace() {
        assertThat(EntityNormalizer.matchKey("  The   Coca-Cola  Company ")).isEqualTo("coca cola company");
        assertThat(EntityNormalizer.matchKey("Theranos")).isEqualTo("theranos");
    }

    @Test
    void matchKeyKeepsSuffixWordsThatAreNotTrailing() {
        assertThat(EntityNormalizer.matchKey("Co-op Bank")).isEqualTo("co op bank");
        assertThat(EntityNormalizer.matchKey("Limited Edition")).isEqualTo("limited edition");
    }

    @Test
    void matchKeyFallsBackToTheNormalizedNameWhenNothingIsLeft() {
        assertThat(EntityNormalizer.matchKey("Inc.")).isEqualTo("inc.");
        assertThat(EntityNormalizer.matchKey("The")).isEqualTo("the");
        assertThat(EntityNormalizer.matchKey("&")).isEqualTo("&");
        assertThat(EntityNormalizer.matchKey(null)).isEmpty();
    }
}
