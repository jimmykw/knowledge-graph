package net.jimmykw.knowledgegraph.graph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PreferredLabelsTest {

    @Test
    void preferredLabelsRankByPosition() {
        assertThat(PreferredLabels.rank("Person")).isZero();
        assertThat(PreferredLabels.rank("Organization")).isEqualTo(1);
        assertThat(PreferredLabels.rank("Technology")).isEqualTo(9);
    }

    @Test
    void customLabelsRankJustAboveConcept() {
        assertThat(PreferredLabels.rank("Medical Instrument")).isEqualTo(10);
        assertThat(PreferredLabels.rank("Concept")).isEqualTo(11);
        assertThat(PreferredLabels.rank("Medical Instrument")).isGreaterThan(PreferredLabels.rank("Technology"));
    }

    @Test
    void betterPicksTheLowerRankAndTheCurrentLabelOnATie() {
        assertThat(PreferredLabels.better("Technology", "Product")).isEqualTo("Product");
        assertThat(PreferredLabels.better("Product", "Technology")).isEqualTo("Product");
        assertThat(PreferredLabels.better("Company", "Firm")).isEqualTo("Company");
        assertThat(PreferredLabels.better("Concept", "Firm")).isEqualTo("Firm");
    }
}
