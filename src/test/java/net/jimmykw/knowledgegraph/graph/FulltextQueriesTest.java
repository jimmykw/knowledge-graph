package net.jimmykw.knowledgegraph.graph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FulltextQueriesTest {

    @Test
    void luceneSpecialCharactersAreEscaped() {
        assertThat(FulltextQueries.forName("AS/400")).contains("as\\/400");
        assertThat(FulltextQueries.forName("C++")).contains("c\\+\\+");
        assertThat(FulltextQueries.forName("Q&A (beta): \"x\"*?")).contains("q\\&a \\(beta\\)\\: \\\"x\\\"\\*\\?");
    }

    @Test
    void lowercasingDefusesBooleanOperators() {
        assertThat(FulltextQueries.forName("  Research AND Development ")).contains("research and development");
    }

    @Test
    void namesWithoutALetterOrDigitHaveNoQuery() {
        assertThat(FulltextQueries.forName("+-/")).isEmpty();
        assertThat(FulltextQueries.forName(" ")).isEmpty();
    }
}
